package net.irisshaders.iris.vulkan.fabric.mixin;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.irisshaders.iris.vulkan.fabric.LiveDrawState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=ProjectionMatrixBuffer.class,remap=false)
public abstract class ProjectionMatrixBufferMixin {
    @Inject(method="getBuffer(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",at=@At("RETURN"))
    private void iris$projectionObject(net.minecraft.client.renderer.Projection projection,CallbackInfoReturnable<GpuBufferSlice> ci){LiveDrawState.projection(ci.getReturnValue(),projection.getMatrix(new Matrix4f()));}
    @Inject(method="getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",at=@At("RETURN"))
    private void iris$projection(Matrix4f projection,CallbackInfoReturnable<GpuBufferSlice> ci){LiveDrawState.projection(ci.getReturnValue(),projection);}
}
