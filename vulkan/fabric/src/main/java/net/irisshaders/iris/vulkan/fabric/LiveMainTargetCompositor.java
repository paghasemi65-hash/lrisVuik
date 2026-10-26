package net.irisshaders.iris.vulkan.fabric;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.*;
import net.irisshaders.iris.vulkan.VulkanContextBridge;
import net.irisshaders.iris.vulkan.render.*;
import net.irisshaders.iris.vulkan.shader.*;
import org.lwjgl.system.MemoryStack;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static org.lwjgl.vulkan.VK13.*;
import static net.irisshaders.iris.vulkan.VulkanContextBridge.check;

/** Final pass between native world post-effects and GUI. Never owns Minecraft's textures. */
public final class LiveMainTargetCompositor implements AutoCloseable {
    private final VulkanContextBridge context;
    private final VulkanRenderTargets targets;
    private final VulkanPipelineManager pipelines;
    private final SpirvCompiler compiler;
    private final CompletableFuture<VulkanPipelineManager.Compiled> compiled;
    private final CompletableFuture<VulkanPipelineManager.Compiled> depthCompiled;
    private FrameDescriptors descriptors;
    private VulkanPipelineManager.Pipeline pipeline;
    private VulkanPipelineManager.Pipeline depthPipeline;
    private VulkanRenderTargets.Image convertedDepth;
    private VulkanRenderTargets.Image snapshot;
    private long sampler;
    private boolean closed;
    public final int width, height, format;
    public final long colorHandle, depthHandle;

    public LiveMainTargetCompositor(VulkanContextBridge context, RenderTarget main, Path gameDirectory) throws Exception {
        this.context = context;
        var color = nativeView(main.getColorTextureView()); var depth = nativeView(main.getDepthTextureView());
        width = color.texture().getWidth(0); height = color.texture().getHeight(0);
        format = VulkanConst.toVk(color.texture().getFormat());
        colorHandle = color.texture().vkImage(); depthHandle = depth.texture().vkImage();
        compiler = new SpirvCompiler(gameDirectory, 2, context.device().getCapabilities().Vulkan13
            ? SpirvCompiler.Target.VULKAN_1_3 : SpirvCompiler.Target.VULKAN_1_2);
        targets = new VulkanRenderTargets(context); pipelines = new VulkanPipelineManager(context,gameDirectory.resolve("cache/iris_vulkan"));
        try {
            // Depth is supplied by the live host, preserving its reversed-Z convention.
            var program = new ShaderVulkanTransformer().transformProgram(Map.of(
                ShaderStage.VERTEX, """
                    #version 460
                    void main() { vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2); gl_Position=vec4(p*2.0-1.0,0.0,1.0); }
                    """,
                ShaderStage.FRAGMENT, """
                    #version 460
                    uniform sampler2D colortex0;
                    uniform sampler2D depthtex0;
                    uniform sampler2D gtexture;
                    uniform sampler2D lightmap;
                    uniform float exposure;
                    uniform float showDepth;
                    uniform float viewWidth;
                    uniform float viewHeight;
                    uniform float showNativeTextures;
                    void main() {
                        ivec2 p=clamp(ivec2(gl_FragCoord.xy),ivec2(0),ivec2(viewWidth,viewHeight)-1);
                        vec4 color=texelFetch(colortex0,p,0);
                        float depth=1.0-texelFetch(depthtex0,p,0).r;
                        // Optional raw reversed-Z display makes the imported depth inspectable.
                        gl_FragColor=vec4(mix(color.rgb * exposure, vec3(depth), showDepth), color.a);
                        if(showNativeTextures > 0.5) {
                            vec2 uv=gl_FragCoord.xy/vec2(viewWidth,viewHeight);
                            gl_FragColor=uv.x<0.5?texture(gtexture,vec2(uv.x*2.0,uv.y)):texture(lightmap,vec2((uv.x-0.5)*2.0,uv.y));
                        }
                    }
                    """));
            compiled = VulkanPipelineManager.compile(compiler, "iris-live-final", program);
            depthCompiled = VulkanPipelineManager.compile(compiler,"iris-live-depth",VulkanDepthConverter.program());
            descriptors = new FrameDescriptors(context, 16, 4);
            snapshot = targets.allocate(width, height, format, false, false);
            convertedDepth = targets.allocate(width,height,VulkanConst.toVk(depth.texture().getFormat()),true,false);
            try (var stack = MemoryStack.stackPush()) {
                var out = stack.mallocLong(1);
                check(vkCreateSampler(context.device(), org.lwjgl.vulkan.VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, out), "live target sampler");
                sampler = out.get(0);
            }
        } catch (Throwable failure) { close(); throw failure; }
    }
    public static VulkanGpuTextureView nativeView(com.mojang.renderpearl.api.textures.GpuTextureView view) {
        if (!(view instanceof VulkanGpuTextureView nativeView) || nativeView.isClosed() || nativeView.texture().isClosed()
            || nativeView.texture().getMipLevels() != 1 || nativeView.texture().getDepthOrLayers() != 1)
            throw new IllegalStateException("Expected a live single-mip/layer RenderPearl Vulkan target");
        return nativeView;
    }
    public boolean matches(RenderTarget main) {
        return nativeView(main.getColorTextureView()).texture().vkImage() == colorHandle
            && nativeView(main.getDepthTextureView()).texture().vkImage() == depthHandle;
    }
    private VulkanRenderTargets.Image borrow(VulkanGpuTextureView view) {
        var image = view.texture(); var f = image.getFormat();
        return targets.borrow(image.vkImage(), view.vkImageView(), VulkanConst.toVk(f), image.getWidth(0), image.getHeight(0),
            VulkanConst.formatAspectMask(f), VulkanConst.textureUsageToVk(image.usage(), f));
    }
    public boolean render(RenderTarget main, float exposure, boolean showDepth) {
        context.checkThread();
        if (closed || !matches(main)) throw new IllegalStateException("Stale live compositor");
        if (!compiled.isDone()||!depthCompiled.isDone()) return false;
        if (pipeline == null) pipeline = pipelines.create(compiled.join(), VulkanPipelineManager.GraphicsState.fullscreen(List.of(format)));
        if (depthPipeline == null) depthPipeline = pipelines.create(depthCompiled.join(),VulkanDepthConverter.state(convertedDepth.format));
        var colorView = nativeView(main.getColorTextureView()); var depthView = nativeView(main.getDepthTextureView());
        var commands = context.frame().commands();
        var color = borrow(colorView); var depth = borrow(depthView);
        try (var nativeTextures=new NativeTextureImports(context,targets)) {
            var mc=net.minecraft.client.Minecraft.getInstance();
            var atlas=mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            nativeTextures.bind(new LiveDrawState.Texture(atlas.getTextureView(),atlas.getSampler()),"gtexture");
            nativeTextures.bind(new LiveDrawState.Texture(mc.gameRenderer.levelLightmap(),
                com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(com.mojang.renderpearl.api.textures.FilterMode.LINEAR)),"lightmap");
            targets.copyColor(commands, color, snapshot);
            var renderer=new VulkanCompositeRenderer(context,targets,descriptors);
            VulkanDepthConverter.record(renderer,depthPipeline,depth,convertedDepth,sampler);
            var uniforms = IrisVulkanLifecycle.uniforms().snapshot().overlay(Map.of(
                "exposure",net.irisshaders.iris.vulkan.uniform.UniformValue.scalar(exposure),
                "showDepth",net.irisshaders.iris.vulkan.uniform.UniformValue.scalar(showDepth?1:0),
                "showNativeTextures",net.irisshaders.iris.vulkan.uniform.UniformValue.scalar(IrisVulkanLifecycle.showNativeTextures()?1:0))).pack(pipeline.program.uniforms());
            var textures=new HashMap<>(nativeTextures.bindings());
            textures.put("colortex0",List.of(new FrameDescriptors.Texture(snapshot,sampler)));
            textures.put("depthtex0",List.of(new FrameDescriptors.Texture(convertedDepth,sampler)));
            renderer.draw(pipeline, List.of(color), null,
                textures,
                uniforms, VulkanCompositeRenderer.Draw.fullscreen(), false);
            return true;
        } finally {
            try { targets.returnToHost(commands, depth); } finally { targets.returnToHost(commands, color); }
        }
    }
    public void close() {
        if (closed) return; closed = true;
        if (descriptors != null) descriptors.close();
        pipelines.close(); targets.close();
        long retiringSampler = sampler;
        if (retiringSampler != 0) context.retire(() -> vkDestroySampler(context.device(), retiringSampler, null));
        compiler.close();
    }
}
