package net.irisshaders.iris.vulkan.neoforge.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;

/** NeoForge 26.3.0.98 adds stencil pipelines but omits them from native deferred destruction. */
@Mixin(value=VulkanRenderPipeline.class,remap=false)
public abstract class StencilPipelineRetirementMixin {
    @Shadow @Final private VulkanDevice device;
    @Shadow @Final private long withDepthPipeline;
    @Shadow @Final private long withStencilPipeline;
    @Shadow @Final private long withDepthStencilPipeline;
    @Inject(method="destroy()V",at=@At("HEAD"))
    private void iris$destroyStencilVariants(CallbackInfo callback){
        // destroy(), unlike close(), runs through RenderPearl's completed-submission queue.
        if(withDepthPipeline!=0){
            vkDestroyPipeline(device.vkDevice(),withStencilPipeline,null);
            vkDestroyPipeline(device.vkDevice(),withDepthStencilPipeline,null);
        }
    }
}
