package net.irisshaders.iris.vulkan.fabric.mixin;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import net.irisshaders.iris.vulkan.fabric.IrisVulkanLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value = VulkanCommandEncoder.class, remap = false)
public abstract class VulkanCommandEncoderMixin {
    @Inject(method = "submit()V", at = @At("RETURN"))
    private void iris$completed(CallbackInfo ci) { IrisVulkanLifecycle.collect((VulkanCommandEncoder) (Object) this); }
    @Inject(method = "destroy()V", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanQueue;waitIdle()V", shift = At.Shift.AFTER))
    private void iris$idle(CallbackInfo ci) { IrisVulkanLifecycle.afterQueueIdle((VulkanCommandEncoder) (Object) this); }
}
