package net.irisshaders.iris.vulkan.render;

import net.irisshaders.iris.vulkan.VulkanContextBridge;
import java.nio.ByteBuffer;
import java.util.*;

/** Records opaque shadow geometry, an opaque-depth snapshot, then translucent shadow geometry. */
public final class VulkanShadowRenderer {
    public record Batch(VulkanPipelineManager.Pipeline pipeline,VulkanCompositeRenderer.Draw draw,ByteBuffer uniforms,
                        Map<String,List<FrameDescriptors.Texture>> resources) {}
    private final VulkanContextBridge context;
    private final VulkanRenderTargets targets;
    private final VulkanCompositeRenderer renderer;
    public VulkanShadowRenderer(VulkanContextBridge context,VulkanRenderTargets targets,VulkanCompositeRenderer renderer){this.context=context;this.targets=targets;this.renderer=renderer;}
    public void render(List<Batch> opaque,List<Batch> translucent,VulkanRenderTargets.Image shadowtex0,
                       VulkanRenderTargets.Image shadowtex1,List<VulkanRenderTargets.Image> shadowColors){
        var cmd=context.frame().commands();targets.clear(cmd,shadowtex0);for(var image:shadowColors)targets.clear(cmd,image);
        for(var b:opaque)renderer.draw(b.pipeline,shadowColors,shadowtex0,b.resources,b.uniforms,b.draw,false);
        targets.copyDepth(cmd,shadowtex0,shadowtex1);
        for(var b:translucent)renderer.draw(b.pipeline,shadowColors,shadowtex0,b.resources,b.uniforms,b.draw,false);
        targets.transition(cmd,shadowtex0,VulkanRenderTargets.Use.SAMPLED);
        for(var image:shadowColors)targets.transition(cmd,image,VulkanRenderTargets.Use.SAMPLED);
    }
}
