package net.irisshaders.iris.vulkan.render;

import net.irisshaders.iris.vulkan.VulkanContextBridge;
import net.irisshaders.iris.vulkan.shader.DescriptorSchema;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.util.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.util.vma.Vma.*;
import static net.irisshaders.iris.vulkan.VulkanContextBridge.check;

/** Unique descriptors and uniform bytes for each recorded pass; pools reset only after submission completion. */
public final class FrameDescriptors implements AutoCloseable {
    public record Texture(VulkanRenderTargets.Image image, long sampler) {}
    public record Binding(long uniformSet, long imageSet, int dynamicOffset, boolean hasUniforms) {
        public long[] sets() { return new long[]{uniformSet,imageSet}; }
    }
    private static final class Buffer {
        final long handle, allocation, address;
        final int capacity;
        int used;
        Buffer(long handle,long allocation,long address,int capacity){this.handle=handle;this.allocation=allocation;this.address=address;this.capacity=capacity;}
    }
    private static final class Slot {
        long pool, submission = -1;
        final List<Buffer> buffers = new ArrayList<>();
    }
    private final VulkanContextBridge context;
    private final Slot[] slots;
    private final long alignment;
    private final int maxUniformRange;
    private int allocatedBuffers;
    private boolean closed;
    public FrameDescriptors(VulkanContextBridge context, int maxPasses, int maxImagesPerPass) {
        this.context = context; slots = new Slot[context.framesInFlight()];
        if (maxPasses < 1 || maxImagesPerPass < 1) throw new IllegalArgumentException();
        try (var s=MemoryStack.stackPush()) {
            var properties=VkPhysicalDeviceProperties.calloc(s);vkGetPhysicalDeviceProperties(context.physicalDevice(),properties);
            alignment=Math.max(1,properties.limits().minUniformBufferOffsetAlignment());
            maxUniformRange=properties.limits().maxUniformBufferRange();
            var sizes=VkDescriptorPoolSize.calloc(3,s);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(maxPasses);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(Math.multiplyExact(maxPasses,maxImagesPerPass));
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(Math.multiplyExact(maxPasses,maxImagesPerPass));
            var info=VkDescriptorPoolCreateInfo.calloc(s).sType$Default().maxSets(Math.multiplyExact(maxPasses,2)).pPoolSizes(sizes);
            var out=s.mallocLong(1);
            try { for(int i=0;i<slots.length;i++) { check(vkCreateDescriptorPool(context.device(),info,null,out),"descriptor pool"); slots[i]=new Slot();slots[i].pool=out.get(0); } }
            catch(Throwable t) { for(var slot:slots) if(slot!=null) vkDestroyDescriptorPool(context.device(),slot.pool,null); throw t; }
        }
    }
    public Binding allocate(VulkanContextBridge.Frame frame, long uniformLayout, long imageLayout, int uniformSize,
                           ByteBuffer uniformData, DescriptorSchema schema, Map<String,List<Texture>> textures) {
        context.checkThread(); if(closed) throw new IllegalStateException("Descriptors closed");
        Slot slot=slots[frame.slot()];
        if(slot.submission!=frame.submission()) {
            if(slot.submission>frame.completedSubmission()) throw new IllegalStateException("Descriptor pool still in flight");
            check(vkResetDescriptorPool(context.device(),slot.pool,0),"reset descriptors");
            // A completed slot can reuse its mapped pages. Other slots retain their own immutable bytes.
            for(var b:slot.buffers) b.used=0;
            slot.submission=frame.submission();
        }
        if (uniformSize > 0 && (uniformData == null || uniformData.remaining() != uniformSize)) throw new IllegalArgumentException("Uniform ABI size mismatch");
        if (uniformSize < 0 || uniformSize > maxUniformRange) throw new IllegalArgumentException("Uniform range exceeds device limit");
        try(var s=MemoryStack.stackPush()) {
            var sets=s.mallocLong(2);
            check(vkAllocateDescriptorSets(context.device(),VkDescriptorSetAllocateInfo.calloc(s).sType$Default()
                .descriptorPool(slot.pool).pSetLayouts(s.longs(uniformLayout,imageLayout)),sets),"allocate pass descriptors");
            int n=schema.bindings().size()+(uniformSize>0?1:0);
            if(n==0) return new Binding(sets.get(0),sets.get(1),0,false);
            var writes=VkWriteDescriptorSet.calloc(n,s); int index=0;
            int offset=0;
            if(uniformSize>0) {
                Buffer buffer=null;
                for(var candidate:slot.buffers) {
                    long aligned=align(candidate.used);
                    if(aligned+uniformSize<=candidate.capacity){buffer=candidate;offset=(int)aligned;break;}
                }
                if(buffer==null){buffer=allocatePage(s,Math.max(65536,uniformSize));slot.buffers.add(buffer);}
                org.lwjgl.system.MemoryUtil.memByteBuffer(buffer.address+offset,uniformSize).put(uniformData.duplicate());
                vmaFlushAllocation(context.allocator(),buffer.allocation,offset,uniformSize);
                buffer.used=Math.addExact(offset,uniformSize);
                writes.get(index++).sType$Default().dstSet(sets.get(0)).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1,s).buffer(buffer.handle).offset(0).range(uniformSize));
            }
            for(var b:schema.bindings()) {
                var values=textures.get(b.resource().name());
                if(values==null||values.size()!=b.resource().count()) throw new IllegalArgumentException("Missing descriptor resource: "+b.resource().name());
                var images=VkDescriptorImageInfo.calloc(values.size(),s);
                for(int i=0;i<values.size();i++) {
                    var t=values.get(i); int expected=b.resource().storage()?VK_IMAGE_LAYOUT_GENERAL:VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                    if(t.image.layout()!=expected||!b.resource().storage()&&t.sampler==0) throw new IllegalStateException("Resource not prepared for descriptor "+b.resource().name());
                    images.get(i).imageView(b.resource().storage()?t.image.baseView:t.image.view).sampler(b.resource().storage()?0:t.sampler).imageLayout(expected);
                }
                writes.get(index++).sType$Default().dstSet(sets.get(1)).dstBinding(b.binding()).descriptorCount(values.size())
                    .descriptorType(b.resource().storage()?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(images);
            }
            vkUpdateDescriptorSets(context.device(),writes,null);
            return new Binding(sets.get(0),sets.get(1),offset,uniformSize>0);
        }
    }
    private long align(long value){return Math.multiplyExact((Math.addExact(value,alignment-1)/alignment),alignment);}
    private Buffer allocatePage(MemoryStack s,int size) {
        var info=VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        var alloc=VmaAllocationCreateInfo.calloc(s).usage(VMA_MEMORY_USAGE_AUTO).flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT|VMA_ALLOCATION_CREATE_MAPPED_BIT);
        var result=VmaAllocationInfo.calloc(s);var buffer=s.mallocLong(1);var allocation=s.mallocPointer(1);
        check(vmaCreateBuffer(context.allocator(),info,alloc,buffer,allocation,result),"uniform ring page");
        allocatedBuffers++;
        return new Buffer(buffer.get(0),allocation.get(0),result.pMappedData(),size);
    }
    public int allocatedBufferCount(){return allocatedBuffers;}
    @Override public void close() {
        context.checkThread();if(closed)return;closed=true;
        context.retire(()->{for(var slot:slots){vkDestroyDescriptorPool(context.device(),slot.pool,null);for(var b:slot.buffers)vmaDestroyBuffer(context.allocator(),b.handle,b.allocation);}});
    }
}
