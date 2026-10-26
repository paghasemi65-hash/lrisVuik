package net.irisshaders.iris.vulkan.fabric.mixin;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import net.irisshaders.iris.vulkan.fabric.IrisVulkanLifecycle;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value = VulkanDevice.class, remap = false)
public abstract class VulkanDeviceMixin {
    @Shadow @Final private FeatureSet enabledFeatures;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void iris$capture(CallbackInfo ci) {
        IrisVulkanLifecycle.attach((VulkanDevice) (Object) this, enabledFeatures);
    }
    @Inject(method = "close()V", at = @At("HEAD"))
    private void iris$close(CallbackInfo ci) { IrisVulkanLifecycle.beforeDeviceClose((VulkanDevice) (Object) this); }
}
