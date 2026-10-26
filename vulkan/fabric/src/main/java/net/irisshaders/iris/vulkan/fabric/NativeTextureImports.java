package net.irisshaders.iris.vulkan.fabric;

import com.mojang.renderpearl.backend.vulkan.*;
import net.irisshaders.iris.vulkan.VulkanContextBridge;
import net.irisshaders.iris.vulkan.render.*;
import java.util.*;

/** Pass-scoped native textures. Alias names share one layout tracker; host views and samplers are never destroyed. */
public final class NativeTextureImports implements AutoCloseable {
    private final VulkanContextBridge context;
    private final VulkanRenderTargets targets;
    private final Map<Long,VulkanRenderTargets.Image> images=new LinkedHashMap<>();
    private final Map<String,List<FrameDescriptors.Texture>> bindings=new LinkedHashMap<>();
    private boolean closed;
    public NativeTextureImports(VulkanContextBridge context,VulkanRenderTargets targets){this.context=context;this.targets=targets;}
    public void bind(LiveDrawState.Texture texture,String... aliases) {
        if(closed)throw new IllegalStateException("Imports closed");
        if(!(texture.view() instanceof VulkanGpuTextureView view)||!(texture.sampler() instanceof VulkanGpuSampler sampler)
            ||view.isClosed()||view.texture().isClosed()||sampler.isClosed()||view.baseMipLevel()!=0||view.texture().getDepthOrLayers()!=1)
            throw new IllegalArgumentException("Expected a live native 2D view and sampler at mip zero");
        var nativeImage=view.texture();
        var image=images.get(nativeImage.vkImage());
        if(image==null){image=targets.borrow(nativeImage.vkImage(),view.vkImageView(),VulkanConst.toVk(nativeImage.getFormat()),view.getWidth(0),view.getHeight(0),
            VulkanConst.formatAspectMask(nativeImage.getFormat()),VulkanConst.textureUsageToVk(nativeImage.usage(),nativeImage.getFormat()),view.mipLevels());images.put(image.handle,image);}
        else if(image.view!=view.vkImageView())throw new IllegalArgumentException("Overlapping native image views require shared subresource tracking");
        var binding=List.of(new FrameDescriptors.Texture(image,sampler.vkSampler()));
        for(String alias:aliases)bindings.put(alias,binding);
    }
    public Map<String,List<FrameDescriptors.Texture>> bindings(){return Map.copyOf(bindings);}
    public void bindDraw(LiveDrawState.Draw draw) {
        var base=draw.textures().get("Sampler0");if(base!=null)bind(base,"gtexture","texture","iris_TextureSampler","tex");
        var lightmap=draw.textures().get("Sampler2");if(lightmap!=null)bind(lightmap,"lightmap");
    }
    @Override public void close(){
        if(closed)return;closed=true;
        var commands=context.frame().commands();
        RuntimeException failure=null;
        for(var image:images.values())try{targets.returnToHost(commands,image);}catch(RuntimeException e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        images.clear();bindings.clear();if(failure!=null)throw failure;
    }
}
