package net.irisshaders.iris.vulkan.render;
import net.irisshaders.iris.vulkan.*;
import net.irisshaders.iris.vulkan.pack.PackResourcePlan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.*;
import static org.lwjgl.vulkan.VK13.*;
import static net.irisshaders.iris.vulkan.VulkanContextBridge.check;
public final class VulkanSamplers implements AutoCloseable {
    private record Key(Sampler spec,int levels,int format,boolean depth){}
    private final VulkanContextBridge context;
    private final Map<Key,Long> samplers=new HashMap<>();
    private boolean closed;
    public VulkanSamplers(VulkanContextBridge context){this.context=context;}
    public long get(Sampler spec,VulkanRenderTargets.Image image,boolean mipmapped){
        context.checkThread();if(closed)throw new IllegalStateException("Samplers closed");boolean depth=image.aspect==VK_IMAGE_ASPECT_DEPTH_BIT;
        if(spec.compare()&&!depth)throw new IllegalArgumentException("Comparison sampler requires depth texture");
        var key=new Key(spec,mipmapped?image.mipLevels:1,image.format,depth);Long found=samplers.get(key);if(found!=null)return found;
        try(var s=MemoryStack.stackPush()){
            var props=VkFormatProperties.calloc(s);vkGetPhysicalDeviceFormatProperties(context.physicalDevice(),image.format,props);
            if((!spec.nearest()||!spec.magNearest()||mipmapped&&!spec.mipNearest())&&(props.optimalTilingFeatures()&VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)==0)
                throw new IllegalArgumentException("Format "+image.format+" lacks linear filtering requested by pack");
            var info=VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(spec.magNearest()?VK_FILTER_NEAREST:VK_FILTER_LINEAR).minFilter(spec.nearest()?VK_FILTER_NEAREST:VK_FILTER_LINEAR)
                .mipmapMode(spec.mipNearest()?VK_SAMPLER_MIPMAP_MODE_NEAREST:VK_SAMPLER_MIPMAP_MODE_LINEAR).addressModeU(wrap(spec.s())).addressModeV(wrap(spec.t())).addressModeW(wrap(spec.r()))
                .compareEnable(spec.compare()).compareOp(VK_COMPARE_OP_LESS_OR_EQUAL).minLod(0).maxLod(key.levels()-1).borderColor(net.irisshaders.iris.vulkan.pack.TextureFormat.integerVk(image.format)?VK_BORDER_COLOR_INT_OPAQUE_WHITE:VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE);
            var out=s.mallocLong(1);check(vkCreateSampler(context.device(),info,null,out),"pack sampler");samplers.put(key,out.get(0));return out.get(0);
        }
    }
    private static int wrap(Wrap wrap){return switch(wrap){case REPEAT->VK_SAMPLER_ADDRESS_MODE_REPEAT;case CLAMP_TO_EDGE->VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;case MIRRORED_REPEAT->VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;case CLAMP_TO_BORDER->VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;};}
    public int size(){return samplers.size();}
    public void close(){context.checkThread();if(closed)return;closed=true;var release=List.copyOf(samplers.values());samplers.clear();context.retire(()->release.forEach(s->vkDestroySampler(context.device(),s,null)));}
}
