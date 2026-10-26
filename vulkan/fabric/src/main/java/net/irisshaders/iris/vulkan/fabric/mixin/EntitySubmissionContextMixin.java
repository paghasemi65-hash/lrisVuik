package net.irisshaders.iris.vulkan.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.irisshaders.iris.vulkan.fabric.VertexDataContext;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=EntityRenderDispatcher.class,remap=false)
public abstract class EntitySubmissionContextMixin {
    @WrapMethod(method="submit")
    private void iris$submit(EntityRenderState state,CameraRenderState camera,double x,double y,double z,PoseStack pose,SubmitNodeCollector collector,Operation<Void> original){
        try(var scope=VertexDataContext.model(state)){original.call(state,camera,x,y,z,pose,collector);}
    }
}
