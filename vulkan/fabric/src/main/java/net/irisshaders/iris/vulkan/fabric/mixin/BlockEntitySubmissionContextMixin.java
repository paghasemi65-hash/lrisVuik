package net.irisshaders.iris.vulkan.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.vulkan.fabric.VertexDataContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=BlockEntityRenderDispatcher.class,remap=false)
public abstract class BlockEntitySubmissionContextMixin {
    @WrapMethod(method="submit")
    private void iris$submit(BlockEntityRenderState state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera,Operation<Void> original){
        try(var scope=VertexDataContext.model(state)){original.call(state,pose,collector,camera);}
    }
}
