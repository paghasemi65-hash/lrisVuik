package net.irisshaders.iris.vulkan.fabric.mixin;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.*;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.irisshaders.iris.vulkan.fabric.LiveDrawState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;
@Mixin(value=FrontendRenderPass.class,remap=false)
public abstract class FrontendRenderPassMixin {
    @Shadow private FrontendRenderPipeline boundPipeline;
    @Unique private final Map<String,GpuBufferSlice> iris$buffers=new HashMap<>();
    @Unique private final Map<String,LiveDrawState.Texture> iris$textures=new HashMap<>();
    @Inject(method="setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V",at=@At("RETURN"))
    private void iris$buffer(String name,GpuBufferSlice value,CallbackInfo ci){iris$buffers.put(name,value);}
    @Inject(method="setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V",at=@At("RETURN"))
    private void iris$texture(String name,GpuTextureView view,GpuSampler sampler,CallbackInfo ci){iris$textures.put(name,new LiveDrawState.Texture(view,sampler));}
    // drawMultipleIndexed updates ChunkSection inside its loop and bypasses FrontendRenderPass.drawIndexed.
    // Capture at the backend invocation, after each individual uniform uploader has run.
    @WrapOperation(method={"drawIndexed","drawMultipleIndexed"},at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/backend/api/RenderPassBackend;drawIndexed(IIIII)V"))
    private void iris$indexed(RenderPassBackend backend,int count,int instances,int first,int base,int firstInstance,Operation<Void> original){
        iris$capture();original.call(backend,count,instances,first,base,firstInstance);
    }
    @WrapOperation(method="draw",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/backend/api/RenderPassBackend;draw(IIII)V"))
    private void iris$direct(RenderPassBackend backend,int count,int instances,int first,int firstInstance,Operation<Void> original){
        iris$capture();original.call(backend,count,instances,first,firstInstance);
    }
    @Inject(method={"multiDrawIndexed","drawIndexedIndirect","multiDraw","drawIndirect"},at=@At("RETURN"))
    private void iris$batch(CallbackInfo ci){iris$capture();}
    @Unique private void iris$capture(){if(boundPipeline!=null)LiveDrawState.draw(iris$buffers,iris$textures,boundPipeline.uniformIndices().keySet());}
}
