package net.irisshaders.iris.vulkan.fabric;

import com.mojang.renderpearl.backend.vulkan.*;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import net.irisshaders.iris.vulkan.VulkanContextBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.atomic.AtomicReference;

/** All GPU mutations run on the host render thread; reload/world callbacks only request resets. */
public final class IrisVulkanLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger("IrisVulkan");
    private static final AtomicReference<String> RESET = new AtomicReference<>();
    private static final AtomicReference<String> NOTIFICATION = new AtomicReference<>();
    private static RenderPearlHost host;
    private static VulkanContextBridge context;
    private static LiveMainTargetCompositor compositor;
    private static LiveShaderPacks packs;
    private static boolean disabled;
    private static long window, surface, swapchain;
    private static long renderedFrames, generations;
    private static float exposure = 1f;
    private static boolean showDepth;
    private static boolean showNativeTextures;
    public static void showNativeTextures(boolean value) { showNativeTextures=value; }
    public static boolean showNativeTextures() { return showNativeTextures; }
    private static final LiveUniformProvider UNIFORMS = new LiveUniformProvider();
    public static LiveUniformProvider uniforms() { return UNIFORMS; }
    public static void captureWorld(GameRenderer renderer, org.joml.Matrix4fc projection) {
        if (context == null || disabled) return;
        try { UNIFORMS.capture(renderer, projection); if(UNIFORMS.ready())LiveDrawState.beginWorld(); } catch (Exception failure) { disable(failure); }
    }
    public static void worldChanged() { UNIFORMS.reset(); LiveDrawState.reset(); }

    public static void attach(VulkanDevice device, FeatureSet enabledFeatures) {
        if (host != null) throw new IllegalStateException("Previous RenderPearl device has not shut down");
        host = new RenderPearlHost(device); disabled = false;
        try {
            context = new VulkanContextBridge(device.instance().vkInstance(), device.vkDevice(), device.vma(), device.graphicsQueue().queueFamilyIndex(), host,
                enabledFeatures.features().stream().map(f->f.name()).collect(java.util.stream.Collectors.toSet()));
            var capabilities = VulkanCapabilities.query(context, enabledFeatures);
            LOG.info("Iris Vulkan device capabilities: {}", capabilities);
            if (!capabilities.missing().isEmpty()) throw new IllegalStateException("Missing GPU features: " + String.join(", ", capabilities.missing()));
            VulkanContextBridge.attach(context);
            requestReset("renderer startup");
            LOG.info("Iris Vulkan attached: instance={}, physicalDevice={}, device={}, queue={}, commandPool={}, framesInFlight={}",
                context.instance().address(), context.physicalDevice().address(), context.device().address(), host.graphicsQueue().address(), host.commandPool(), host.submissionsInFlight());
        } catch (Exception failure) { disable(failure); }
    }
    public static void requestReset(String reason) { RESET.set(reason); LOG.info("Iris lifecycle reset requested: {}", reason); }
    public static void surfaceCreated(long sdlWindow, long vkSurface) {
        window = sdlWindow; surface = vkSurface;
        LOG.info("Iris borrowed SDL3 window={} Vulkan surface={}", window, surface);
    }
    public static void surfaceAcquired(long chain, int format, int width, int height, int image) {
        if (context == null || disabled || image < 0) return;
        if (chain != swapchain) { swapchain = chain; requestReset("swapchain recreation"); }
        context.updateSurface(new VulkanContextBridge.Surface(format, width, height, image));
    }
    public static void beforeGui(GameRenderer renderer) {
        LiveDrawState.endWorld();
        var message = NOTIFICATION.getAndSet(null);
        if (message != null) Minecraft.getInstance().execute(() -> SystemToast.add(Minecraft.getInstance().gui.toastManager(),
            new SystemToast.SystemToastId(15000), Component.literal("Iris Vulkan shaders disabled"), Component.literal(message)));
        if (context == null || disabled) return;
        context.checkThread();
        try {
            String reset = RESET.getAndSet(null);
            if (reset != null) { releaseCompositor(); if(packs!=null)packs.reload(); }
            if(packs==null)packs=new LiveShaderPacks(context,Minecraft.getInstance().gameDirectory.toPath());
            if(renderer.gameRenderState().shouldRenderLevel&&!UNIFORMS.ready())return;
            packs.frame(renderer);
            if (!renderer.gameRenderState().shouldRenderLevel) return;
            var main = renderer.mainRenderTarget();
            if (compositor != null && !compositor.matches(main)) releaseRenderer();
            if (compositor == null) {
                compositor = new LiveMainTargetCompositor(context, main, Minecraft.getInstance().gameDirectory.toPath());
                generations++;
                LOG.info("Iris live main targets: generation={} color={} depth={} size={}x{} format={}",
                    generations, compositor.colorHandle, compositor.depthHandle, compositor.width, compositor.height, compositor.format);
            }
            if (compositor.render(main, exposure, showDepth)) {
                if (renderedFrames++ == 0) LOG.info("Iris final pass recorded before GUI: commandBuffer={}, submission={}, slot={}",
                    context.frame().commands().address(), host.submissionIndex(), context.frame().slot());
            }
        } catch (Exception failure) { disable(failure); }
    }
    private static void disable(Exception failure) {
        disabled = true;
        LOG.error("Iris Vulkan shader rendering disabled; native rendering continues", failure);
        NOTIFICATION.set(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        releaseRenderer();
    }
    public static void releaseRenderer() {
        if(packs!=null)packs.releaseWorld();
        releaseCompositor();
    }
    private static void releaseCompositor() {
        if (compositor != null) {
            var retiring = compositor; compositor = null; retiring.close();
            LOG.info("Iris compositor retired at submission {}", host.submissionIndex());
        }
    }
    public static void collect(VulkanCommandEncoder encoder) {
        if (host != null && host.encoder == encoder) host.retirement.collect(host.completedSubmissionIndex());
    }
    public static void beforeDeviceClose(VulkanDevice device) {
        if (host == null || host.device != device) return;
        releaseRenderer();
        if(packs!=null){packs.close();packs=null;}
    }
    public static void afterQueueIdle(VulkanCommandEncoder encoder) {
        if (host == null || host.encoder != encoder) return;
        host.retirement.drainAfterQueueIdle();
        VulkanContextBridge.detach(host.device.vkDevice());
        LOG.info("Iris Vulkan clean shutdown: renderedFrames={}, generations={}, pendingRetirements={}", renderedFrames, generations, host.retirement.pendingCount());
        context = null; host = null; swapchain = 0; window = 0; surface = 0;
    }
    public record Diagnostics(boolean attached, boolean disabled, long renderedFrames, long generations, long sdlWindow,
        long surface, long swapchain, long commandPool, long queue, long submission, long completedSubmission, int retirements) {}
    public static Diagnostics diagnostics() {
        return new Diagnostics(context != null, disabled, renderedFrames, generations, window, surface, swapchain,
            host == null ? 0 : host.commandPool(), host == null ? 0 : host.graphicsQueue().address(),
            host == null ? 0 : host.submissionIndex(), host == null ? 0 : host.completedSubmissionIndex(),
            host == null ? 0 : host.retirement.pendingCount());
    }
    /** Final-pass exposure, including a measurable diagnostic setting for live client verification. */
    public static void exposure(float value) {
        if (!Float.isFinite(value) || value < 0 || value > 8) throw new IllegalArgumentException("Exposure");
        exposure = value;
    }
    public static void showDepth(boolean value) { showDepth = value; }
}
