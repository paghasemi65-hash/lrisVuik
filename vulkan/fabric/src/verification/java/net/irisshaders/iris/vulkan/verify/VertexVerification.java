package net.irisshaders.iris.vulkan.verify;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import net.irisshaders.iris.vulkan.*;
import net.irisshaders.iris.vulkan.fabric.*;
import net.irisshaders.iris.vulkan.render.*;
import net.irisshaders.iris.vulkan.shader.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.*;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.util.vma.Vma.*;
import static net.irisshaders.iris.vulkan.VulkanContextBridge.check;

/** Development-only readback of attributes copied from a real worker-built terrain mesh. */
public final class VertexVerification {
    private record Sample(VertexFormat format,byte[] quad) {}
    private static final AtomicReference<Sample> SAMPLE=new AtomicReference<>();
    private static final LongAdder terrainVertices=new LongAdder(),entityVertices=new LongAdder();
    private static long submission=-1,buffer,allocation,mapped;
    private static boolean passed;
    public static void mesh(MeshData mesh) {
        if(mesh==null||!VertexDataContext.active())return;
        var state=mesh.drawState();var format=state.format();
        if(format==DefaultVertexFormat.ENTITY||format==DefaultVertexFormat.ENTITY_GLINT_SPECIAL)entityVertices.add(state.vertexCount());
        if(format!=DefaultVertexFormat.BLOCK||state.vertexCount()<4)return;
        terrainVertices.add(state.vertexCount());if(SAMPLE.get()!=null)return;
        byte[] quad=new byte[format.getVertexSize()*4];mesh.vertexBuffer().duplicate().get(quad);
        SAMPLE.compareAndSet(null,new Sample(format,quad));
    }
    static void frame(Minecraft mc)throws Exception {
        if(passed)return;var sample=SAMPLE.get();if(sample==null)return;
        var context=VulkanContextBridge.require();
        if(submission<0){verifyBytes(sample);record(context,mc,sample);return;}
        if(context.frame().completedSubmission()<submission)return;
        try {
            vmaInvalidateAllocation(context.allocator(),allocation,0,64);
            var bytes=MemoryUtil.memByteBuffer(mapped,64);
            for(int p=0;p<16;p++)require((bytes.get(p*4)&255)==0&&(bytes.get(p*4+1)&255)==255&&(bytes.get(p*4+2)&255)==0,"Vulkan vertex attribute readback pixel "+p);
            passed=true;
            org.slf4j.LoggerFactory.getLogger("IrisVerification").info("Phase 4 actual terrain vertex bytes and Vulkan attribute readback verified: stride={}",sample.format.getVertexSize());
        }finally{vmaDestroyBuffer(context.allocator(),buffer,allocation);buffer=allocation=mapped=0;}
    }
    private static void verifyBytes(Sample sample) {
        var f=sample.format;var b=ByteBuffer.wrap(sample.quad).order(ByteOrder.nativeOrder());int stride=f.getVertexSize();
        require(NativeVertexFormats.layout(f).stride()==stride,"Native pipeline stride");
        float u=0,v=0;for(int i=0;i<4;i++){u+=get(b,f,i,"UV0",0);v+=get(b,f,i,"UV0",1);}
        float[] center=new float[3];
        for(int i=0;i<4;i++) {
            require(Math.abs(get(b,f,i,"mc_midTexCoord",0)-u*.25f)<1e-6&&Math.abs(get(b,f,i,"mc_midTexCoord",1)-v*.25f)<1e-6,"Quad UV center");
            float length=0,dot=0;
            for(int c=0;c<3;c++){
                float t=get(b,f,i,"at_tangent",c);length+=t*t;dot+=t*get(b,f,i,"iris_Normal",c);
                float blockCenter=get(b,f,i,"Position",c)+get(b,f,i,"at_midBlock",c)/64;
                if(i==0)center[c]=blockCenter;else require(Math.abs(blockCenter-center[c])<=1f/64,"Per-quad block center");
            }
            require(Math.abs(length-1)<1e-4&&Math.abs(dot)<1e-4,"Finite orthonormal tangent");
            require(Math.abs(get(b,f,i,"at_tangent",3))==1,"Tangent handedness");
            require(get(b,f,i,"at_midBlock",3)>=0&&get(b,f,i,"at_midBlock",3)<=15,"Registry block emission");
            require(get(b,f,i,"mc_Entity",0)==get(b,f,0,"mc_Entity",0),"Quad material ID");
        }
    }
    private static float get(ByteBuffer b,VertexFormat f,int vertex,String attribute,int component){return b.getFloat(vertex*f.getVertexSize()+f.getElement(attribute).offset()+component*4);}
    private static void record(VulkanContextBridge context,Minecraft mc,Sample sample)throws Exception {
        String vertex="""
            #version 460
            attribute vec4 mc_Entity;
            attribute vec2 mc_midTexCoord;
            attribute vec4 at_tangent;
            attribute vec4 at_midBlock;
            uniform vec4 expectedEntity;
            uniform vec2 expectedMid;
            uniform vec4 expectedTangent;
            uniform vec4 expectedBlock[4];
            uniform vec3 expectedPosition[4];
            uniform vec3 expectedNormal;
            out float valid;
            void main(){
                int index=int[6](0,1,2,2,3,0)[gl_VertexID];
                valid=float(all(lessThan(abs(mc_Entity-expectedEntity),vec4(0.0001)))
                    &&all(lessThan(abs(mc_midTexCoord-expectedMid),vec2(0.0001)))
                    &&all(lessThan(abs(at_tangent-expectedTangent),vec4(0.0001)))
                    &&all(lessThan(abs(at_midBlock-expectedBlock[index]),vec4(0.0001)))
                    &&all(lessThan(abs(gl_Vertex.xyz-expectedPosition[index]),vec3(0.0001)))
                    &&all(lessThan(abs(gl_Normal-expectedNormal),vec3(0.0001))));
                gl_Position=vec4((index==1||index==2)?1:-1,index>=2?1:-1,0,1);
            }
            """;
        var program=new ShaderVulkanTransformer().transformProgram(Map.of(ShaderStage.VERTEX,vertex,ShaderStage.FRAGMENT,"#version 460\nin float valid;void main(){gl_FragColor=valid>0.9999?vec4(0,1,0,1):vec4(1,0,0,1);}"));
        var bytes=ByteBuffer.wrap(sample.quad).order(ByteOrder.nativeOrder());var uniforms=ByteBuffer.allocate(program.uniforms().size()).order(ByteOrder.nativeOrder());
        var names=Map.of("expectedEntity","mc_Entity","expectedMid","mc_midTexCoord","expectedTangent","at_tangent","expectedBlock","at_midBlock","expectedPosition","Position","expectedNormal","iris_Normal");
        for(var entry:names.entrySet()){
            int components=switch(entry.getKey()){case "expectedMid"->2;case "expectedPosition","expectedNormal"->3;default->4;};
            int count=entry.getKey().equals("expectedBlock")||entry.getKey().equals("expectedPosition")?4:1;
            float[] values=new float[count*components];for(int i=0;i<count;i++)for(int c=0;c<components;c++)values[i*components+c]=get(bytes,sample.format,i,entry.getValue(),c);
            program.uniforms().putFloats(uniforms,entry.getKey(),values);
        }
        var expanded=MemoryUtil.memAlloc(sample.format.getVertexSize()*6);
        try(var compiler=new SpirvCompiler(mc.gameDirectory.toPath(),1,context.device().getCapabilities().Vulkan13?SpirvCompiler.Target.VULKAN_1_3:SpirvCompiler.Target.VULKAN_1_2);
            var targets=new VulkanRenderTargets(context);var pipelines=new VulkanPipelineManager(context);var descriptors=new FrameDescriptors(context,1,1)) {
            int stride=sample.format.getVertexSize();for(int index:new int[]{0,1,2,2,3,0})expanded.put(sample.quad,index*stride,stride);expanded.flip();
            try(var nativeBuffer=RenderSystem.getDevice().createBuffer(()->"Iris vertex ABI verification",GpuBuffer.USAGE_VERTEX,expanded)){
                var state=new VulkanPipelineManager.GraphicsState(List.of(VK_FORMAT_R8G8B8A8_UNORM),VK_FORMAT_UNDEFINED,false,false,VK_COMPARE_OP_ALWAYS,VK_CULL_MODE_NONE,VK_FRONT_FACE_COUNTER_CLOCKWISE,List.of(VulkanPipelineManager.Blend.disabled()),NativeVertexFormats.layout(sample.format));
                var pipeline=pipelines.cached(VulkanPipelineManager.compile(compiler,"verify-native-vertex-abi",program).join(),state);
                var image=targets.allocate(4,4,VK_FORMAT_R8G8B8A8_UNORM,false,false);
                new VulkanCompositeRenderer(context,targets,descriptors).draw(pipeline,List.of(image),null,Map.of(),uniforms,
                    new VulkanCompositeRenderer.Draw(((VulkanGpuBuffer)nativeBuffer).vkBuffer(),0,0,0,VK_INDEX_TYPE_UINT32,6,1,0,0),false);
                var frame=context.frame();var commands=frame.commands();
                try(var stack=MemoryStack.stackPush()){
                    var out=stack.mallocLong(1);var alloc=stack.mallocPointer(1);var info=VmaAllocationInfo.calloc(stack);
                    check(vmaCreateBuffer(context.allocator(),VkBufferCreateInfo.calloc(stack).sType$Default().size(64).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT),
                        VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO).flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT|VMA_ALLOCATION_CREATE_MAPPED_BIT),out,alloc,info),"vertex readback allocation");
                    buffer=out.get(0);allocation=alloc.get(0);mapped=info.pMappedData();
                    targets.transition(commands,image,VulkanRenderTargets.Use.COPY_SOURCE);
                    vkCmdCopyImageToBuffer(commands,image.handle,image.layout(),buffer,VkBufferImageCopy.calloc(1,stack).imageSubresource(s->s.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e->e.set(4,4,1)));
                    var barrier=VkBufferMemoryBarrier2.calloc(1,stack).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(buffer).size(64);
                    VulkanDispatch.vkCmdPipelineBarrier2(commands,VkDependencyInfo.calloc(stack).sType$Default().pBufferMemoryBarriers(barrier));
                    submission=frame.submission();
                }
            }
        }finally{MemoryUtil.memFree(expanded);}
    }
    static void finish(Minecraft mc)throws Exception {
        long terrain=terrainVertices.sum(),entities=entityVertices.sum(),chunks=LiveDrawState.chunkDraws();
        boolean complete=passed&&terrain>0&&entities>0&&chunks>0;
        Files.writeString(mc.gameDirectory.toPath().resolve("iris-phase4-vertices.json"),"{\"passed\":"+complete+",\"terrainVertices\":"+terrain+",\"entityVertices\":"+entities+",\"batchedChunkDraws\":"+chunks+",\"nativeGpuReadback\":"+passed+",\"worldRoutingComplete\":false}");
        require(complete,"Actual terrain/entity meshes, batched chunk capture and GPU vertex readback: terrainVertices="+terrain+", entityVertices="+entities+", batchedChunkDraws="+chunks+", nativeGpuReadback="+passed);
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("Phase 4 vertex verification: "+message);}
}
