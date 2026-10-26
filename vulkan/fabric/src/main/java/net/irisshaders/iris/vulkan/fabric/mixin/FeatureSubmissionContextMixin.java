package net.irisshaders.iris.vulkan.fabric.mixin;
import net.irisshaders.iris.vulkan.fabric.*;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value={ModelFeatureRenderer.Submit.class,BlockModelFeatureRenderer.Submit.class},remap=false)
public abstract class FeatureSubmissionContextMixin implements VertexContextCarrier {
    @Unique private VertexDataContext.Value iris$context;
    @Inject(method="<init>",at=@At("RETURN"))
    private void iris$capture(CallbackInfo ci){iris$context=VertexDataContext.current();}
    public VertexDataContext.Value iris$vertexContext(){return iris$context;}
}
