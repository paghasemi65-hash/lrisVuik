package net.irisshaders.iris.vulkan.fabric.mixin;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.DynamicGpuData;
import net.irisshaders.iris.vulkan.fabric.LiveDrawState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=DynamicGpuData.class,remap=false)
public abstract class DynamicGpuDataMixin {
    @Inject(method="reset()V",at=@At("HEAD"))
    private void iris$reset(CallbackInfo ci){LiveDrawState.reset();}
    @Inject(method="writeTransform(Lnet/minecraft/client/renderer/DynamicGpuData$Transform;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",at=@At("RETURN"))
    private void iris$transform(DynamicGpuData.Transform value,CallbackInfoReturnable<GpuBufferSlice> ci){LiveDrawState.transform(ci.getReturnValue(),value);}
    @Inject(method="writeTransforms([Lnet/minecraft/client/renderer/DynamicGpuData$Transform;)[Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",at=@At("RETURN"))
    private void iris$transforms(DynamicGpuData.Transform[] values,CallbackInfoReturnable<GpuBufferSlice[]> ci){var slices=ci.getReturnValue();for(int i=0;i<values.length;i++)LiveDrawState.transform(slices[i],values[i]);}
    @Inject(method="writeTerrainTransform(Lorg/joml/Matrix4fc;II)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",at=@At("RETURN"))
    private void iris$terrain(Matrix4fc matrix,int width,int height,CallbackInfoReturnable<GpuBufferSlice> ci){LiveDrawState.terrain(ci.getReturnValue(),matrix,width,height);}
    @Inject(method="writeChunkSections([Lnet/minecraft/client/renderer/DynamicGpuData$ChunkSectionInfo;)[Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;",at=@At("RETURN"))
    private void iris$sections(DynamicGpuData.ChunkSectionInfo[] values,CallbackInfoReturnable<GpuBufferSlice[]> ci){var slices=ci.getReturnValue();for(int i=0;i<values.length;i++)LiveDrawState.section(slices[i],values[i]);}
}
