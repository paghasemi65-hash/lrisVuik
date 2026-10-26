package net.irisshaders.iris.vulkan.verify.mixin;
import net.irisshaders.iris.vulkan.verify.ClientVerification;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value = Minecraft.class, remap = false)
public abstract class VerificationMixin {
    @Inject(method = "runTick(Z)V", at = @At("RETURN"))
    private void iris$verify(CallbackInfo ci) { ClientVerification.frame((Minecraft) (Object) this); }
    @Inject(method = "close()V", at = @At("RETURN"))
    private void iris$closed(CallbackInfo ci) { ClientVerification.closed((Minecraft) (Object) this); }
}
