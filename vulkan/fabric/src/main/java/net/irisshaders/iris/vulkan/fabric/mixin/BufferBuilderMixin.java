package net.irisshaders.iris.vulkan.fabric.mixin;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.irisshaders.iris.vulkan.fabric.VertexDataContext;
import net.irisshaders.iris.vulkan.render.QuadVertexData;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import static org.lwjgl.system.MemoryUtil.*;

@Mixin(value=BufferBuilder.class,remap=false)
public abstract class BufferBuilderMixin {
    @Shadow @Final private VertexFormat format;
    @Shadow @Final private int vertexSize;
    @Shadow @Final private PrimitiveTopology primitiveTopology;
    @Shadow private int vertices;
    @Shadow private long vertexPointer;
    @Unique private int iris$completed;
    @Unique private final VertexDataContext.Value[] iris$contexts=new VertexDataContext.Value[4];
    @Inject(method="beginVertex()J",at=@At("RETURN"))
    private void iris$begin(CallbackInfoReturnable<Long> ci){
        if(!format.contains("mc_Entity"))return;
        iris$contexts[(vertices-1)&3]=VertexDataContext.current();
        int offset=format.getElement("mc_Entity").offset();memSet(ci.getReturnValue()+offset,0,vertexSize-offset);
        memPutFloat(ci.getReturnValue()+offset,-1);memPutFloat(ci.getReturnValue()+offset+4,-1);
    }
    @Inject(method="endLastVertex()V",at=@At("RETURN"))
    private void iris$quad(CallbackInfo ci){
        if(!VertexDataContext.active()||!format.contains("mc_Entity")||primitiveTopology!=PrimitiveTopology.QUADS||vertices==0||vertices%4!=0||iris$completed==vertices)return;
        iris$completed=vertices;
        long first=vertexPointer-3L*vertexSize;
        var points=new QuadVertexData.Vertex[4];int pos=format.getElement("Position").offset(),uv=format.getElement("UV0").offset();
        for(int i=0;i<4;i++){long p=first+(long)i*vertexSize;points[i]=new QuadVertexData.Vertex(memGetFloat(p+pos),memGetFloat(p+pos+4),memGetFloat(p+pos+8),memGetFloat(p+uv),memGetFloat(p+uv+4));}
        var basis=QuadVertexData.compute(points[0],points[1],points[2],points[3]);
        for(int i=0;i<4;i++){
            long p=first+(long)i*vertexSize;var context=iris$contexts[i];
            if(context!=null){long entity=p+format.getElement("mc_Entity").offset();memPutFloat(entity,context.id());memPutFloat(entity+4,context.renderType());}
            long mid=p+format.getElement("mc_midTexCoord").offset();memPutFloat(mid,basis.u());memPutFloat(mid+4,basis.v());
            long tangent=p+format.getElement("at_tangent").offset();memPutFloat(tangent,basis.tx());memPutFloat(tangent+4,basis.ty());memPutFloat(tangent+8,basis.tz());memPutFloat(tangent+12,basis.handedness());
            long normal=p+format.getElement("iris_Normal").offset();
            if(format.contains("Normal")){long n=p+format.getElement("Normal").offset();for(int axis=0;axis<3;axis++)memPutFloat(normal+axis*4,Math.max(-1,memGetByte(n+axis)/127f));}
            else {memPutFloat(normal,basis.nx());memPutFloat(normal+4,basis.ny());memPutFloat(normal+8,basis.nz());}
            if(context!=null&&context.block()){
                long block=p+format.getElement("at_midBlock").offset();
                memPutFloat(block,QuadVertexData.midBlock(context.centerX(),points[i].x()));memPutFloat(block+4,QuadVertexData.midBlock(context.centerY(),points[i].y()));memPutFloat(block+8,QuadVertexData.midBlock(context.centerZ(),points[i].z()));memPutFloat(block+12,context.emission());
            }
        }
    }
}
