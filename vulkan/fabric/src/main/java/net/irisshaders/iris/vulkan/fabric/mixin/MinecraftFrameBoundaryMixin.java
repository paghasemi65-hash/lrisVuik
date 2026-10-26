package net.irisshaders.iris.vulkan.fabric.mixin;

import net.irisshaders.iris.vulkan.fabric.LiveShaderPacks;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Before extraction/uploads: GameRenderer.render entry is already too late for Sodium staging fences. */
@Mixin(value=Minecraft.class,remap=false)
public abstract class MinecraftFrameBoundaryMixin {
    @Inject(method="runTick(Z)V",at=@At("HEAD"))
    private void iris$frameBoundary(boolean advanceGameTime,CallbackInfo callback){
        var packs=LiveShaderPacks.active();if(packs!=null)packs.beforeFrame();
    }
}
