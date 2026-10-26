package net.irisshaders.iris.vulkan.fabric.mixin;

import net.irisshaders.iris.vulkan.fabric.ShaderControls;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=Options.class,remap=false)
public abstract class OptionsKeyMappingMixin {
    @Shadow @Final @Mutable public KeyMapping[] keyMappings;
    // Append before Options.load so native options.txt persistence and the Controls screen include both keys.
    @Inject(method="<init>",at=@At(value="INVOKE",target="Lnet/minecraft/client/Options;load()V"))
    private void iris$register(CallbackInfo ci){keyMappings=ShaderControls.register(keyMappings);}
}
