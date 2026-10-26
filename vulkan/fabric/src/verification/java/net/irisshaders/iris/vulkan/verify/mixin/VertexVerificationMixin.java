package net.irisshaders.iris.vulkan.verify.mixin;
import com.mojang.blaze3d.vertex.*;
import net.irisshaders.iris.vulkan.verify.VertexVerification;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=BufferBuilder.class,remap=false)
public abstract class VertexVerificationMixin {
    @Inject(method="build()Lcom/mojang/blaze3d/vertex/MeshData;",at=@At("RETURN"))
    private void iris$mesh(CallbackInfoReturnable<MeshData> ci){VertexVerification.mesh(ci.getReturnValue());}
}
