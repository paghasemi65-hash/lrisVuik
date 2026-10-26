package net.irisshaders.iris.vulkan.fabric.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import net.irisshaders.iris.vulkan.fabric.IrisVulkanLifecycle;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=VulkanGpuSurface.class,remap=false)
public abstract class VulkanSurfaceMixin {
    @Shadow @Final private long surface;
    @Shadow private long swapchain;
    @Shadow @Final private int swapchainImageFormat;
    @Shadow private int swapchainWidth;
    @Shadow private int swapchainHeight;
    @Shadow private int currentImageIndex;
    @Inject(method="<init>",at=@At("RETURN"))
    private void iris$window(com.mojang.renderpearl.backend.vulkan.VulkanDevice device, long window, CallbackInfo ci) {
        IrisVulkanLifecycle.surfaceCreated(window, surface);
    }
    @Inject(method="acquireNextTexture()V",at=@At("RETURN"))
    private void iris$captureSurface(CallbackInfo ci){
        IrisVulkanLifecycle.surfaceAcquired(swapchain,swapchainImageFormat,swapchainWidth,swapchainHeight,currentImageIndex);
    }
}
