package net.irisshaders.iris.vulkan.fabric.mixin;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.irisshaders.iris.vulkan.fabric.NativeVertexFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(value=DefaultVertexFormat.class,remap=false)
public abstract class DefaultVertexFormatMixin {
    @Redirect(method="<clinit>",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/vertex/VertexFormat$Builder;build()Lcom/mojang/renderpearl/api/vertex/VertexFormat;",ordinal=0))
    private static VertexFormat iris$block(VertexFormat.Builder builder){return NativeVertexFormats.extend(builder.build());}
    @Redirect(method="<clinit>",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/vertex/VertexFormat$Builder;build()Lcom/mojang/renderpearl/api/vertex/VertexFormat;",ordinal=2))
    private static VertexFormat iris$entity(VertexFormat.Builder builder){return NativeVertexFormats.extend(builder.build());}
    @Redirect(method="<clinit>",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/vertex/VertexFormat$Builder;build()Lcom/mojang/renderpearl/api/vertex/VertexFormat;",ordinal=3))
    private static VertexFormat iris$glint(VertexFormat.Builder builder){return NativeVertexFormats.extend(builder.build());}
}
