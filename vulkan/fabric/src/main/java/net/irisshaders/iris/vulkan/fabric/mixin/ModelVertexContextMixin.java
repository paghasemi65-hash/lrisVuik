package net.irisshaders.iris.vulkan.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.vulkan.fabric.VertexDataContext;
import net.irisshaders.iris.vulkan.fabric.VertexContextCarrier;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=ModelFeatureRenderer.class,remap=false)
public abstract class ModelVertexContextMixin {
    @WrapMethod(method="prepareModel")
    private void iris$model(ModelFeatureRenderer.Submit<?> submit,Operation<Void> original){
        try(var inherited=VertexDataContext.restore(((VertexContextCarrier)(Object)submit).iris$vertexContext());
            var scope=VertexDataContext.model(submit.state())){original.call(submit);}
    }
}
