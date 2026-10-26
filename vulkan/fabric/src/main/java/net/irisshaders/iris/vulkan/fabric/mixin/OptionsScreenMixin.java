package net.irisshaders.iris.vulkan.fabric.mixin;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.irisshaders.iris.vulkan.fabric.ShaderPackScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=OptionsScreen.class,remap=false)
public abstract class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin(Component title){super(title);}
    @Inject(method="init()V",at=@At("RETURN"))
    private void iris$shaderpacks(CallbackInfo ci){addRenderableWidget(Button.builder(Component.literal("Shaderpacks"),button->minecraft.gui.setScreen(new ShaderPackScreen((Screen)(Object)this))).bounds(width-108,4,104,20).build());}
}
