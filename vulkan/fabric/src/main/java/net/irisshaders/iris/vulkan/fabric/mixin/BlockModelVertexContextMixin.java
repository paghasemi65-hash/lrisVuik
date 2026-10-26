package net.irisshaders.iris.vulkan.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.*;
import net.irisshaders.iris.vulkan.fabric.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value=BlockModelFeatureRenderer.class,remap=false)
public abstract class BlockModelVertexContextMixin {
    @WrapOperation(method="buildGroup",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/feature/BlockModelFeatureRenderer;putPartQuads(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModelPart;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/QuadInstance;I[ILcom/mojang/blaze3d/vertex/VertexConsumer;)V"))
    private void iris$part(BlockStateModelPart part,PoseStack.Pose pose,QuadInstance quad,int tint,int[] layers,VertexConsumer buffer,Operation<Void> original,@Local BlockModelFeatureRenderer.Submit submit){
        try(var scope=VertexDataContext.restore(((VertexContextCarrier)(Object)submit).iris$vertexContext())){original.call(part,pose,quad,tint,layers,buffer);}
    }
}
