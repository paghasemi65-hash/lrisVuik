package net.irisshaders.iris.vulkan.render;

import net.irisshaders.iris.vulkan.VulkanContextBridge;
import net.irisshaders.iris.vulkan.pack.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import java.util.*;
import static org.lwjgl.vulkan.VK13.*;
import static net.irisshaders.iris.vulkan.VulkanDispatch.vkCmdPipelineBarrier2;
import static org.lwjgl.util.vma.Vma.*;
import static net.irisshaders.iris.vulkan.VulkanContextBridge.check;

/** Owned VMA images and frame-scoped host imports, with independent state for every mip level. */
public final class VulkanRenderTargets implements AutoCloseable {
    public enum Use {
        HOST_GENERAL(VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,VK_ACCESS_2_MEMORY_READ_BIT|VK_ACCESS_2_MEMORY_WRITE_BIT),
        COLOR(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,VK_ACCESS_2_COLOR_ATTACHMENT_READ_BIT|VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT),
        DEPTH(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,VK_PIPELINE_STAGE_2_EARLY_FRAGMENT_TESTS_BIT|VK_PIPELINE_STAGE_2_LATE_FRAGMENT_TESTS_BIT,VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_READ_BIT|VK_ACCESS_2_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT),
        SAMPLED(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT|VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT|VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,VK_ACCESS_2_SHADER_SAMPLED_READ_BIT),
        STORAGE(VK_IMAGE_LAYOUT_GENERAL,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,VK_ACCESS_2_SHADER_STORAGE_READ_BIT|VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT),
        COPY_SOURCE(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_TRANSFER_READ_BIT),
        COPY_DESTINATION(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_PIPELINE_STAGE_2_TRANSFER_BIT,VK_ACCESS_2_TRANSFER_WRITE_BIT);
        public final int layout; public final long stage,access;
        Use(int layout,long stage,long access){this.layout=layout;this.stage=stage;this.access=access;}
    }
    public record Spec(int width,int height,int depth,int layers,int mipLevels,int format,PackResourcePlan.Shape shape,boolean depthFormat,boolean storage,boolean attachment) {
        public Spec {
            if(width<1||height<1||depth<1||layers<1||mipLevels<1||mipLevels>fullMipLevels(width,height,depth))throw new IllegalArgumentException("Invalid image dimensions/mips");
            if(depthFormat&&storage)throw new IllegalArgumentException("Depth storage images are not supported by Vulkan");
            if((shape==PackResourcePlan.Shape.CUBE||shape==PackResourcePlan.Shape.CUBE_ARRAY)&&(width!=height||layers%6!=0))throw new IllegalArgumentException("Cube extent/layers");
            if(shape==PackResourcePlan.Shape.CUBE&&layers!=6)throw new IllegalArgumentException("A cube view requires exactly six layers");
            if((shape==PackResourcePlan.Shape.D1||shape==PackResourcePlan.Shape.D2)&&layers!=1)throw new IllegalArgumentException("Non-array view requires one layer");
            if(shape==PackResourcePlan.Shape.D3&&layers!=1||shape!=PackResourcePlan.Shape.D3&&depth!=1)throw new IllegalArgumentException("Image depth/layers");
            if((shape==PackResourcePlan.Shape.D1||shape==PackResourcePlan.Shape.D1_ARRAY)&&height!=1)throw new IllegalArgumentException("1D height");
        }
    }
    public static int fullMipLevels(int width,int height,int depth){return 32-Integer.numberOfLeadingZeros(Math.max(width,Math.max(height,depth)));}
    public static final class Image {
        public final long handle,view,baseView,allocation;
        public final int format,width,height,depth,layers,mipLevels,aspect,usage,viewType;
        private final int[] layouts;
        private final long[] stages,accesses;
        private boolean initialized,mipsValid;
        private Image(long handle,long view,long baseView,long allocation,Spec spec,int aspect,int usage,int viewType){
            this.handle=handle;this.view=view;this.baseView=baseView;this.allocation=allocation;format=spec.format();width=spec.width();height=spec.height();depth=spec.depth();layers=spec.layers();mipLevels=spec.mipLevels();this.aspect=aspect;this.usage=usage;this.viewType=viewType;
            layouts=new int[mipLevels];stages=new long[mipLevels];accesses=new long[mipLevels];
        }
        public int layout(){int first=layouts[0];for(int layout:layouts)if(layout!=first)throw new IllegalStateException("Image has mixed mip layouts");return first;}
        public int layout(int mip){return layouts[mip];}
        public boolean initialized(){return initialized;}
        public boolean mipmapsValid(){return mipsValid;}
        public void markWritten(){initialized=true;mipsValid=mipLevels==1;}
    }
    private final VulkanContextBridge context;
    private final List<Image> owned=new ArrayList<>();
    private final Set<Image> borrowed=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;
    public VulkanRenderTargets(VulkanContextBridge context){this.context=context;}
    public Image borrow(long image,long view,int format,int width,int height,int aspect,int usage){
        return borrow(image,view,format,width,height,aspect,usage,1);
    }
    public Image borrow(long image,long view,int format,int width,int height,int aspect,int usage,int mipLevels){
        context.checkThread();if(closed||image==0||view==0)throw new IllegalArgumentException("Invalid host target");
        if(borrowed.stream().anyMatch(existing->existing.handle==image))throw new IllegalArgumentException("Host image already borrowed; reuse its tracked import");
        var spec=new Spec(width,height,1,1,mipLevels,format,PackResourcePlan.Shape.D2,aspect==VK_IMAGE_ASPECT_DEPTH_BIT,false,true);
        var result=new Image(image,view,view,0,spec,aspect,usage,VK_IMAGE_VIEW_TYPE_2D);
        Arrays.fill(result.layouts,Use.HOST_GENERAL.layout);Arrays.fill(result.stages,Use.HOST_GENERAL.stage);Arrays.fill(result.accesses,Use.HOST_GENERAL.access);
        result.markWritten();result.mipsValid=true;borrowed.add(result);return result;
    }
    public void returnToHost(VkCommandBuffer commands,Image image){if(!borrowed.contains(image))throw new IllegalArgumentException("Not borrowed");transition(commands,image,Use.HOST_GENERAL);borrowed.remove(image);}
    public Image allocate(int width,int height,int format,boolean depth,boolean storage){return allocate(new Spec(width,height,1,1,1,format,PackResourcePlan.Shape.D2,depth,storage,true));}
    public Image allocate(Spec spec){
        context.checkThread();if(closed)throw new IllegalStateException("Targets closed");
        if(spec.shape()==PackResourcePlan.Shape.CUBE_ARRAY&&!context.enabledFeature("imageCubeArray"))throw new IllegalArgumentException("Pack requires imageCubeArray enabled on the logical device");
        int type=switch(spec.shape()){case D1,D1_ARRAY->VK_IMAGE_TYPE_1D;case D3->VK_IMAGE_TYPE_3D;default->VK_IMAGE_TYPE_2D;};
        int viewType=switch(spec.shape()){case D1->VK_IMAGE_VIEW_TYPE_1D;case D2->VK_IMAGE_VIEW_TYPE_2D;case D3->VK_IMAGE_VIEW_TYPE_3D;case CUBE->VK_IMAGE_VIEW_TYPE_CUBE;case D1_ARRAY->VK_IMAGE_VIEW_TYPE_1D_ARRAY;case D2_ARRAY->VK_IMAGE_VIEW_TYPE_2D_ARRAY;case CUBE_ARRAY->VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;};
        int flags=spec.shape()==PackResourcePlan.Shape.CUBE||spec.shape()==PackResourcePlan.Shape.CUBE_ARRAY?VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT:0;
        int aspect=spec.depthFormat()?VK_IMAGE_ASPECT_DEPTH_BIT:VK_IMAGE_ASPECT_COLOR_BIT;
        int usage=VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if(spec.attachment())usage|=spec.depthFormat()?VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT:VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        if(spec.storage())usage|=VK_IMAGE_USAGE_STORAGE_BIT;
        try(var stack=MemoryStack.stackPush()){
            var properties=VkImageFormatProperties.calloc(stack);
            check(vkGetPhysicalDeviceImageFormatProperties(context.physicalDevice(),spec.format(),type,VK_IMAGE_TILING_OPTIMAL,usage,flags,properties),"Image format/usage support: "+spec);
            if(spec.width()>properties.maxExtent().width()||spec.height()>properties.maxExtent().height()||spec.depth()>properties.maxExtent().depth()||spec.layers()>properties.maxArrayLayers()||spec.mipLevels()>properties.maxMipLevels())throw new IllegalArgumentException("Image exceeds device limits: "+spec);
            var info=VkImageCreateInfo.calloc(stack).sType$Default().flags(flags).imageType(type).format(spec.format()).extent(e->e.set(spec.width(),spec.height(),spec.depth())).mipLevels(spec.mipLevels()).arrayLayers(spec.layers()).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            var image=stack.mallocLong(1);var allocation=stack.mallocPointer(1);
            check(vmaCreateImage(context.allocator(),info,VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE),image,allocation,null),"vmaCreateImage");
            long view=0,base=0;
            try{
                var viewInfo=VkImageViewCreateInfo.calloc(stack).sType$Default().image(image.get(0)).viewType(viewType).format(spec.format()).subresourceRange(r->r.aspectMask(aspect).levelCount(spec.mipLevels()).layerCount(spec.layers()));
                var out=stack.mallocLong(1);check(vkCreateImageView(context.device(),viewInfo,null,out),"full mip view");view=out.get(0);base=view;
                if(spec.mipLevels()>1){viewInfo.subresourceRange().levelCount(1);check(vkCreateImageView(context.device(),viewInfo,null,out),"base mip view");base=out.get(0);}
                var result=new Image(image.get(0),view,base,allocation.get(0),spec,aspect,usage,viewType);owned.add(result);return result;
            }catch(Throwable t){if(base!=0&&base!=view)vkDestroyImageView(context.device(),base,null);if(view!=0)vkDestroyImageView(context.device(),view,null);vmaDestroyImage(context.allocator(),image.get(0),allocation.get(0));throw t;}
        }
    }
    public void transition(VkCommandBuffer cmd,Image image,Use next){transition(cmd,image,next,0,image.mipLevels);}
    public void transition(VkCommandBuffer cmd,Image image,Use next,int baseMip,int count){
        context.checkThread();requireRegistered(image);if(baseMip<0||count<1||baseMip+count>image.mipLevels)throw new IllegalArgumentException("Mip range");
        if(next==Use.SAMPLED&&(!image.initialized||(image.usage&VK_IMAGE_USAGE_SAMPLED_BIT)==0))throw new IllegalStateException("Image not initialized/sampleable");
        if(next==Use.STORAGE&&(image.usage&VK_IMAGE_USAGE_STORAGE_BIT)==0)throw new IllegalArgumentException("Image lacks storage usage");
        int required=switch(next){
            case COLOR->VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;case DEPTH->VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
            case COPY_SOURCE->VK_IMAGE_USAGE_TRANSFER_SRC_BIT;case COPY_DESTINATION->VK_IMAGE_USAGE_TRANSFER_DST_BIT;default->0;
        };
        if((image.usage&required)!=required)throw new IllegalArgumentException("Image lacks usage for "+next);
        if(next==Use.COLOR&&image.aspect!=VK_IMAGE_ASPECT_COLOR_BIT||next==Use.DEPTH&&image.aspect!=VK_IMAGE_ASPECT_DEPTH_BIT)
            throw new IllegalArgumentException("Image aspect incompatible with "+next);
        try(var stack=MemoryStack.stackPush()){
            var barriers=VkImageMemoryBarrier2.calloc(count,stack);
            for(int i=0;i<count;i++){int mip=baseMip+i;barriers.get(i).sType$Default().srcStageMask(image.stages[mip]).srcAccessMask(image.accesses[mip]).dstStageMask(next.stage).dstAccessMask(next.access).oldLayout(image.layouts[mip]).newLayout(next.layout).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image.handle).subresourceRange(r->r.aspectMask(image.aspect).baseMipLevel(mip).levelCount(1).layerCount(image.layers));}
            vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barriers));
        }
        for(int mip=baseMip;mip<baseMip+count;mip++){image.layouts[mip]=next.layout;image.stages[mip]=next.stage;image.accesses[mip]=next.access;}
    }
    public void clear(VkCommandBuffer cmd,Image image){clear(cmd,image,new float[]{0,0,0,0},false,false,1);}
    public void clear(VkCommandBuffer cmd,Image image,float[] color,boolean signedInteger,boolean unsignedInteger,float depth){
        if(color.length!=4)throw new IllegalArgumentException("Clear color");transition(cmd,image,Use.COPY_DESTINATION);
        try(var stack=MemoryStack.stackPush()){
            var range=VkImageSubresourceRange.calloc(1,stack).aspectMask(image.aspect).levelCount(image.mipLevels).layerCount(image.layers);
            if(image.aspect==VK_IMAGE_ASPECT_DEPTH_BIT)vkCmdClearDepthStencilImage(cmd,image.handle,image.layout(),VkClearDepthStencilValue.calloc(stack).depth(depth),range);
            else{var value=VkClearColorValue.calloc(stack);for(int i=0;i<4;i++){if(unsignedInteger)value.uint32(i,(int)(long)color[i]);else if(signedInteger)value.int32(i,(int)color[i]);else value.float32(i,color[i]);}vkCmdClearColorImage(cmd,image.handle,image.layout(),value,range);}
        }
        image.markWritten();image.mipsValid=true;
    }
    public void copyColor(VkCommandBuffer cmd,Image source,Image destination){copy(cmd,source,destination,VK_IMAGE_ASPECT_COLOR_BIT);}
    private void copy(VkCommandBuffer cmd,Image source,Image destination,int aspect){
        requireRegistered(source);requireRegistered(destination);
        if(source.handle==destination.handle||!source.initialized||source.format!=destination.format||source.width!=destination.width||source.height!=destination.height||source.depth!=destination.depth||source.layers!=destination.layers||source.aspect!=aspect||destination.aspect!=aspect)throw new IllegalArgumentException("Image copy format/extent/initialization mismatch");
        transition(cmd,source,Use.COPY_SOURCE,0,1);transition(cmd,destination,Use.COPY_DESTINATION,0,1);
        try(var stack=MemoryStack.stackPush()){
            var region=VkImageCopy.calloc(1,stack).srcSubresource(s->s.aspectMask(aspect).layerCount(source.layers)).dstSubresource(s->s.aspectMask(aspect).layerCount(destination.layers)).extent(e->e.set(source.width,source.height,source.depth));
            vkCmdCopyImage(cmd,source.handle,source.layout(0),destination.handle,destination.layout(0),region);
        }destination.markWritten();
    }
    public void copyDepth(VkCommandBuffer cmd,Image source,Image destination){copy(cmd,source,destination,VK_IMAGE_ASPECT_DEPTH_BIT);transition(cmd,destination,Use.SAMPLED);transition(cmd,source,Use.DEPTH);}
    public void upload(VkCommandBuffer cmd,Image image,byte[] pixels){
        context.checkThread();requireRegistered(image);Objects.requireNonNull(pixels,"pixels");
        long expected=Math.multiplyExact(Math.multiplyExact((long)image.width,image.height),image.depth);
        expected=Math.multiplyExact(Math.multiplyExact(expected,image.layers),TextureFormat.bytesPerTexel(image.format));
        if(pixels.length!=expected)throw new IllegalArgumentException("Texture upload requires "+expected+" bytes, supplied "+pixels.length);
        transition(cmd,image,Use.COPY_DESTINATION,0,1);
        try(var stack=MemoryStack.stackPush()){
            var buffer=stack.mallocLong(1);var allocation=stack.mallocPointer(1);var allocationInfo=VmaAllocationInfo.calloc(stack);
            check(vmaCreateBuffer(context.allocator(),VkBufferCreateInfo.calloc(stack).sType$Default().size(pixels.length).usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT),VmaAllocationCreateInfo.calloc(stack).usage(VMA_MEMORY_USAGE_AUTO).flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT|VMA_ALLOCATION_CREATE_MAPPED_BIT),buffer,allocation,allocationInfo),"texture staging buffer");
            long handle=buffer.get(0),memory=allocation.get(0);
            try{
                org.lwjgl.system.MemoryUtil.memByteBuffer(allocationInfo.pMappedData(),pixels.length).put(pixels);
                vmaFlushAllocation(context.allocator(),memory,0,pixels.length);
                var region=VkBufferImageCopy.calloc(1,stack).imageSubresource(s->s.aspectMask(image.aspect).layerCount(image.layers)).imageExtent(e->e.set(image.width,image.height,image.depth));
                vkCmdCopyBufferToImage(cmd,handle,image.handle,image.layout(0),region);
            }finally{context.retire(()->vmaDestroyBuffer(context.allocator(),handle,memory));}
        }image.markWritten();
    }
    /** Each destination level becomes the next source only after a transfer-write -> transfer-read dependency. */
    public void generateMipmaps(VkCommandBuffer cmd,Image image,boolean linear){
        requireRegistered(image);if(!image.initialized)throw new IllegalStateException("Mip source is uninitialized");if(image.mipsValid)return;
        try(var stack=MemoryStack.stackPush()){
            var support=VkFormatProperties.calloc(stack);vkGetPhysicalDeviceFormatProperties(context.physicalDevice(),image.format,support);
            int required=VK_FORMAT_FEATURE_BLIT_SRC_BIT|VK_FORMAT_FEATURE_BLIT_DST_BIT|(linear?VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT:0);
            if((support.optimalTilingFeatures()&required)!=required)throw new IllegalArgumentException("Format "+image.format+" cannot generate requested mipmaps by blit");
            for(int mip=1;mip<image.mipLevels;mip++){
                transition(cmd,image,Use.COPY_SOURCE,mip-1,1);transition(cmd,image,Use.COPY_DESTINATION,mip,1);
                var blit=VkImageBlit.calloc(1,stack).srcSubresource(s->s.aspectMask(image.aspect).layerCount(image.layers)).dstSubresource(s->s.aspectMask(image.aspect).layerCount(image.layers));
                blit.srcSubresource().mipLevel(mip-1);blit.dstSubresource().mipLevel(mip);
                blit.srcOffsets(1).set(Math.max(1,image.width>>(mip-1)),Math.max(1,image.height>>(mip-1)),Math.max(1,image.depth>>(mip-1)));
                blit.dstOffsets(1).set(Math.max(1,image.width>>mip),Math.max(1,image.height>>mip),Math.max(1,image.depth>>mip));
                vkCmdBlitImage(cmd,image.handle,image.layout(mip-1),image.handle,image.layout(mip),blit,linear?VK_FILTER_LINEAR:VK_FILTER_NEAREST);
            }
        }
        image.mipsValid=true;transition(cmd,image,Use.SAMPLED);
    }
    private void requireRegistered(Image image){if(closed||!(owned.contains(image)||borrowed.contains(image)))throw new IllegalStateException("Image not registered with live targets");}
    public static int format(String name){return TextureFormat.of(name).vk();}
    public void close(){
        context.checkThread();if(closed)return;if(!borrowed.isEmpty())throw new IllegalStateException("Host targets must be returned before closing");closed=true;
        var release=List.copyOf(owned);owned.clear();context.retire(()->{for(var image:release){if(image.baseView!=image.view)vkDestroyImageView(context.device(),image.baseView,null);vkDestroyImageView(context.device(),image.view,null);vmaDestroyImage(context.allocator(),image.handle,image.allocation);}});
    }
}
