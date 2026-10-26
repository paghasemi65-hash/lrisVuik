package net.irisshaders.iris.vulkan.verify.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.IndexType;
import net.irisshaders.iris.vulkan.fabric.LiveDrawState;
import org.spongepowered.asm.mixin.Mixin;
import java.util.Collection;
@Mixin(value=FrontendRenderPass.class,remap=false)
public abstract class DrawVerificationMixin {
    @WrapMethod(method="drawMultipleIndexed")
    private void iris$batch(Collection<RenderPass.Draw<Object>> draws,GpuBuffer indices,IndexType type,Collection<String> uniforms,Object argument,Operation<Void> original){
        boolean terrain=LiveDrawState.capturingWorld()&&uniforms.contains("ChunkSection");long before=LiveDrawState.chunkDraws();
        original.call(draws,indices,type,uniforms,argument);
        if(terrain&&LiveDrawState.chunkDraws()-before!=draws.size())throw new IllegalStateException("Per-chunk capture missed native drawMultipleIndexed uploads: expected "+draws.size()+", captured "+(LiveDrawState.chunkDraws()-before));
    }
}
