package net.irisshaders.iris.vulkan.verify.mixin;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.irisshaders.iris.vulkan.fabric.*;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value=LevelRenderer.class,remap=false)
public abstract class TerrainPathVerificationMixin {
    // Exercise the real non-indirect terrain path as well as the default indirect path. Development mod only.
    @ModifyExpressionValue(method="render",at=@At(value="FIELD",target="Lnet/minecraft/client/renderer/state/level/LevelRenderState;shouldUseMultiDrawIndirectForTerrain:Z"))
    private boolean iris$alternate(boolean requested){return requested&&(!VertexDataContext.active()||!IrisVulkanLifecycle.uniforms().ready()||(IrisVulkanLifecycle.uniforms().snapshot().frame()&1)==1);}
}
