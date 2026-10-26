package net.irisshaders.iris.vulkan.fabric.mixin;

import net.irisshaders.iris.vulkan.fabric.ShaderPackScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=VideoSettingsScreen.class,remap=false)
public abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    protected VideoSettingsScreenMixin(Screen parent,Options options,Component title){super(parent,options,title);}
    @Inject(method="addOptions()V",at=@At("RETURN"))
    private void iris$shaderpacks(CallbackInfo ci){list.addBig(Button.builder(Component.literal("Shaderpacks"),button->minecraft.gui.setScreen(new ShaderPackScreen(this))).width(310).build());}
}
