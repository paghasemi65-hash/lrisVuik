package net.irisshaders.iris.vulkan.neoforge.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;

/** Retire native loading-screen buffers omitted by earlydisplay 12.0.12's cleanup. */
@Mixin(targets="net.neoforged.neoforge.client.loading.earlydisplay.RenderPearlRenderBackend",remap=false)
public abstract class EarlyDisplayBufferRetirementMixin {
    // The superclass constructor invokes createBuffer: no field initializer may overwrite this list.
    @Unique private List<GpuBuffer> iris$buffers;
    @ModifyExpressionValue(method="createBuffer",at={
        @At(value="INVOKE",target="Lcom/mojang/renderpearl/api/device/GpuDevice;createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"),
        @At(value="INVOKE",target="Lcom/mojang/renderpearl/api/device/GpuDevice;createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;")})
    private GpuBuffer iris$rememberBuffer(GpuBuffer buffer){
        if(iris$buffers==null)iris$buffers=new ArrayList<>();
        iris$buffers.add(buffer);return buffer;
    }
    @Inject(method="close()V",at=@At("TAIL"))
    private void iris$closeBuffers(CallbackInfo callback){
        if(iris$buffers!=null){for(var buffer:iris$buffers)if(!buffer.isClosed())buffer.close();iris$buffers.clear();}
    }
}
