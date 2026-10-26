package net.irisshaders.iris.vulkan.render;

import net.irisshaders.iris.vulkan.VulkanContextBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.lwjgl.vulkan.VK13.*;
import static net.irisshaders.iris.vulkan.VulkanDispatch.vkCmdBeginRendering;
import static net.irisshaders.iris.vulkan.VulkanDispatch.vkCmdEndRendering;

/** Shared command recorder for gbuffers, deferred, composite, final, and compute passes. */
public final class VulkanCompositeRenderer {
    public record Draw(long vertexBuffer,long vertexOffset,long indexBuffer,long indexOffset,int indexType,
                       int count,int instances,int firstVertex,int vertexBase,int firstInstance) {
        public Draw(long vertexBuffer,long vertexOffset,long indexBuffer,long indexOffset,int indexType,int count,int instances,int firstVertex,int vertexBase){this(vertexBuffer,vertexOffset,indexBuffer,indexOffset,indexType,count,instances,firstVertex,vertexBase,0);}
        public Draw { if(count<1||instances<1||vertexOffset<0||indexOffset<0||firstVertex<0)throw new IllegalArgumentException("Draw range"); }
        public static Draw fullscreen(){return new Draw(0,0,0,0,VK_INDEX_TYPE_UINT32,3,1,0,0);}
    }
    private final VulkanContextBridge context;
    private final VulkanRenderTargets targets;
    private final FrameDescriptors descriptors;
    public VulkanCompositeRenderer(VulkanContextBridge context,VulkanRenderTargets targets,FrameDescriptors descriptors){this.context=context;this.targets=targets;this.descriptors=descriptors;}
    public void draw(VulkanPipelineManager.Pipeline pipeline,List<VulkanRenderTargets.Image> colors,VulkanRenderTargets.Image depth,
                     Map<String,List<FrameDescriptors.Texture>> resources,ByteBuffer uniformData,Draw draw,boolean presentFlipY){
        pipeline.requireLive();if(pipeline.compute())throw new IllegalArgumentException("Graphics pipeline required");
        if(colors.isEmpty()&&depth==null)throw new IllegalArgumentException("No rendering attachments");
        if(colors.size()!=pipeline.graphics.colorFormats().size())throw new IllegalArgumentException("DRAWBUFFERS/pipeline mismatch");
        var extent=colors.isEmpty()?depth:colors.getFirst();
        var attached=new HashSet<VulkanRenderTargets.Image>();
        for(int i=0;i<colors.size();i++){
            var image=colors.get(i);
            if(image.format!=pipeline.graphics.colorFormats().get(i)||image.width!=extent.width||image.height!=extent.height||!attached.add(image))throw new IllegalArgumentException("Color attachment format, extent, or alias mismatch");
        }
        if(depth!=null){
            if(depth.format!=pipeline.graphics.depthFormat()||depth.width!=extent.width||depth.height!=extent.height||!attached.add(depth))throw new IllegalArgumentException("Depth attachment mismatch");
        }else if(pipeline.graphics.depthFormat()!=VK_FORMAT_UNDEFINED)throw new IllegalArgumentException("Depth attachment missing");
        if(!pipeline.graphics.vertices().attributes().isEmpty()&&draw.vertexBuffer==0)throw new IllegalArgumentException("Vertex buffer required");
        validateResources(pipeline,resources,attached);
        var frame=context.frame();var cmd=frame.commands();
        prepareResources(cmd,pipeline,resources);
        for(var image:colors)targets.transition(cmd,image,VulkanRenderTargets.Use.COLOR);
        if(depth!=null)targets.transition(cmd,depth,VulkanRenderTargets.Use.DEPTH);
        var sets=descriptors.allocate(frame,pipeline.uniformLayout,pipeline.imageLayout,pipeline.program.uniforms().size(),uniformData,pipeline.program.descriptors(),resources);
        try(var s=MemoryStack.stackPush()){
            var attachments=VkRenderingAttachmentInfo.calloc(colors.size(),s);
            for(int i=0;i<colors.size();i++)attachment(attachments.get(i),colors.get(i),false);
            var info=VkRenderingInfo.calloc(s).sType$Default().renderArea(r->r.offset(o->o.set(0,0)).extent(e->e.set(extent.width,extent.height)))
                .layerCount(1).pColorAttachments(attachments);
            if(depth!=null)info.pDepthAttachment(attachment(VkRenderingAttachmentInfo.calloc(s),depth,true));
            vkCmdBeginRendering(cmd,info);
            try{
                vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.handle);
                vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_GRAPHICS,pipeline.layout,0,s.longs(sets.sets()),sets.hasUniforms()?s.ints(sets.dynamicOffset()):null);
                // Internal pack images use the same UV-to-row convention throughout. Flip only at presentation.
                var viewport=VkViewport.calloc(1,s).x(0).y(presentFlipY?extent.height:0).width(extent.width)
                    .height(presentFlipY?-extent.height:extent.height).minDepth(0).maxDepth(1);
                vkCmdSetViewport(cmd,0,viewport);
                vkCmdSetScissor(cmd,0,VkRect2D.calloc(1,s).offset(o->o.set(0,0)).extent(e->e.set(extent.width,extent.height)));
                if(draw.vertexBuffer!=0)vkCmdBindVertexBuffers(cmd,0,s.longs(draw.vertexBuffer),s.longs(draw.vertexOffset));
                if(draw.indexBuffer!=0){
                    vkCmdBindIndexBuffer(cmd,draw.indexBuffer,draw.indexOffset,draw.indexType);
                    vkCmdDrawIndexed(cmd,draw.count,draw.instances,draw.firstVertex,draw.vertexBase,draw.firstInstance);
                }else vkCmdDraw(cmd,draw.count,draw.instances,draw.firstVertex,draw.firstInstance);
            }finally{vkCmdEndRendering(cmd);}
        }
        for(var image:colors)image.markWritten();if(depth!=null)depth.markWritten();markStorageWritten(pipeline,resources);
    }
    private static VkRenderingAttachmentInfo attachment(VkRenderingAttachmentInfo out,VulkanRenderTargets.Image image,boolean depth){
        out.sType$Default().imageView(image.baseView).imageLayout(image.layout())
            .loadOp(image.initialized()?VK_ATTACHMENT_LOAD_OP_LOAD:VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        if(depth)out.clearValue().depthStencil().depth(1f).stencil(0);else out.clearValue().color().float32(0,0).float32(1,0).float32(2,0).float32(3,0);
        return out;
    }
    public void dispatch(VulkanPipelineManager.Pipeline pipeline,Map<String,List<FrameDescriptors.Texture>> resources,
                         ByteBuffer uniformData,int groupsX,int groupsY,int groupsZ){
        pipeline.requireLive();if(!pipeline.compute()||groupsX<1||groupsY<1||groupsZ<1)throw new IllegalArgumentException("Compute dispatch");
        validateResources(pipeline,resources,Set.of());
        try(var s=MemoryStack.stackPush()){
            var props=VkPhysicalDeviceProperties.calloc(s);vkGetPhysicalDeviceProperties(context.physicalDevice(),props);
            if(groupsX>props.limits().maxComputeWorkGroupCount(0)||groupsY>props.limits().maxComputeWorkGroupCount(1)||groupsZ>props.limits().maxComputeWorkGroupCount(2))throw new IllegalArgumentException("Compute dispatch exceeds device limits");
            var frame=context.frame();var cmd=frame.commands();prepareResources(cmd,pipeline,resources);
            var sets=descriptors.allocate(frame,pipeline.uniformLayout,pipeline.imageLayout,pipeline.program.uniforms().size(),uniformData,pipeline.program.descriptors(),resources);
            vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline.handle);
            vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline.layout,0,s.longs(sets.sets()),sets.hasUniforms()?s.ints(sets.dynamicOffset()):null);
            vkCmdDispatch(cmd,groupsX,groupsY,groupsZ);
        }
        markStorageWritten(pipeline,resources);
    }
    public void composite(VulkanPipelineManager.Pipeline pipeline,DescriptorFlipper<VulkanRenderTargets.Image> flipper,
                          List<Integer> drawBuffers,Map<String,List<FrameDescriptors.Texture>> resources,ByteBuffer uniformData,Draw draw){
        if(new HashSet<>(drawBuffers).size()!=drawBuffers.size())throw new IllegalArgumentException("Duplicate DRAWBUFFERS target");
        var writes=drawBuffers.stream().map(flipper::write).toList();
        draw(pipeline,writes,null,resources,uniformData,draw,false);
        flipper.commitWrites(drawBuffers);
    }
    private static void validateResources(VulkanPipelineManager.Pipeline pipeline,Map<String,List<FrameDescriptors.Texture>> resources,Set<VulkanRenderTargets.Image> attachments){
        var uses=new HashMap<VulkanRenderTargets.Image,Boolean>();
        for(var binding:pipeline.program.descriptors().bindings()){
            var r=binding.resource();var textures=resources.get(r.name());
            if(textures==null||textures.size()!=r.count())throw new IllegalArgumentException("Missing resource "+r.name());
            for(var t:textures){
                int expectedView=switch(r.type().replaceFirst("^[iu](?=sampler|image)", "").replace("sampler", "").replace("image", "").replace("Shadow", "")) {
                    case "1D"->VK_IMAGE_VIEW_TYPE_1D;case "2D"->VK_IMAGE_VIEW_TYPE_2D;case "3D"->VK_IMAGE_VIEW_TYPE_3D;
                    case "Cube"->VK_IMAGE_VIEW_TYPE_CUBE;case "1DArray"->VK_IMAGE_VIEW_TYPE_1D_ARRAY;case "2DArray"->VK_IMAGE_VIEW_TYPE_2D_ARRAY;case "CubeArray"->VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;
                    default->throw new IllegalArgumentException("Unsupported descriptor texture type "+r.type());
                };
                if(t.image().viewType!=expectedView)throw new IllegalArgumentException("Descriptor view type mismatch: "+r.name());
                if(attachments.contains(t.image()))throw new IllegalArgumentException("Attachment feedback loop: "+r.name());
                var previous=uses.putIfAbsent(t.image(),r.storage());
                if(previous!=null&&previous!=r.storage())throw new IllegalArgumentException("Simultaneous sampled/storage alias: "+r.name());
                if(!t.image().initialized())throw new IllegalArgumentException("Resource must be initialized before use: "+r.name());
            }
        }
    }
    private void prepareResources(VkCommandBuffer cmd,VulkanPipelineManager.Pipeline pipeline,Map<String,List<FrameDescriptors.Texture>> resources){
        var visited=new HashSet<VulkanRenderTargets.Image>();
        for(var b:pipeline.program.descriptors().bindings())for(var t:resources.get(b.resource().name()))
            if(visited.add(t.image()))targets.transition(cmd,t.image(),b.resource().storage()?VulkanRenderTargets.Use.STORAGE:VulkanRenderTargets.Use.SAMPLED);
    }
    private static void markStorageWritten(VulkanPipelineManager.Pipeline pipeline,Map<String,List<FrameDescriptors.Texture>> resources){
        for(var b:pipeline.program.descriptors().bindings())if(b.resource().storage())for(var t:resources.get(b.resource().name()))t.image().markWritten();
    }
}
