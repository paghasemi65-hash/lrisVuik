package net.irisshaders.iris.vulkan.render;

import net.irisshaders.iris.vulkan.*;
import net.irisshaders.iris.vulkan.pack.*;
import net.irisshaders.iris.vulkan.shader.*;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.*;
import static net.irisshaders.iris.vulkan.pack.PackResourcePlan.*;
import static org.lwjgl.vulkan.VK13.*;

/** Complete resident pack allocation. Neither missing assets nor missing render outputs receive dummy textures. */
public final class PackGpuResources implements AutoCloseable {
    private final VulkanContextBridge context;
    private final PackResourcePlan plan;
    private final VulkanRenderTargets targets;
    private final VulkanSamplers samplers;
    private final Map<String,List<VulkanRenderTargets.Image>> images=new LinkedHashMap<>();
    private final Map<String,TextureData> customData;
    private final Map<String,VulkanRenderTargets.Image> customImages=new LinkedHashMap<>();
    private final DescriptorFlipper<VulkanRenderTargets.Image> flipper;
    private final Map<String,FrameDescriptors.Texture> external=new HashMap<>();
    private boolean closed;
    public PackGpuResources(VulkanContextBridge context,ShaderPackLoader.Loaded loaded,int width,int height) {
        this.context=context;plan=loaded.resources();customData=loaded.textures();targets=new VulkanRenderTargets(context);samplers=new VulkanSamplers(context);
        var pairs=new TreeMap<Integer,DescriptorFlipper.Pair<VulkanRenderTargets.Image>>();
        try {
            for(var target:plan.targets().values()) {
                var extent=target.size().resolve(width,height,plan.constants());int levels=target.mipmaps()?VulkanRenderTargets.fullMipLevels(extent[0],extent[1],extent[2]):1;
                var spec=new VulkanRenderTargets.Spec(extent[0],extent[1],extent[2],1,levels,target.format().vk(),target.size().shape(),target.format().depth(),target.storage(),target.size().shape()==Shape.D2);
                var main=targets.allocate(spec);List<VulkanRenderTargets.Image> allocated;
                if(target.pingPong()){var alternate=targets.allocate(spec);pairs.put(Integer.parseInt(target.name().substring(8)),new DescriptorFlipper.Pair<>(main,alternate));allocated=List.of(main,alternate);}else allocated=List.of(main);
                images.put(target.name(),allocated);
                for(var image:allocated)samplers.get(target.sampler(),image,target.mipmaps());
            }
            for(var entry:customData.entrySet()) {
                var data=entry.getValue();int levels=data.mipmaps()?VulkanRenderTargets.fullMipLevels(data.width(),data.height(),data.depth()):1;
                var image=targets.allocate(new VulkanRenderTargets.Spec(data.width(),data.height(),data.depth(),data.layers(),levels,data.format().vk(),data.shape(),data.format().depth(),false,false));
                customImages.put(entry.getKey(),image);samplers.get(data.sampler(),image,data.mipmaps());
            }
            flipper=pairs.isEmpty()?null:new DescriptorFlipper<>(pairs);
            var cmd=context.frame().commands();
            for(var entry:customImages.entrySet()) {
                var data=customData.get(entry.getKey());targets.upload(cmd,entry.getValue(),data.pixels());
                if(data.mipmaps())targets.generateMipmaps(cmd,entry.getValue(),!data.sampler().nearest());
                else targets.transition(cmd,entry.getValue(),VulkanRenderTargets.Use.SAMPLED);
            }
        }catch(Throwable failure){close();throw failure;}
    }
    public VulkanRenderTargets targets(){return targets;}
    public Map<String,List<VulkanRenderTargets.Image>> allocations(){return Collections.unmodifiableMap(images);}
    public Map<String,VulkanRenderTargets.Image> customImages(){return Collections.unmodifiableMap(customImages);}
    public int samplerCount(){return samplers.size();}
    public VulkanRenderTargets.Image read(String original){String name=plan.aliases().getOrDefault(original,canonical(original));var allocated=images.get(name);if(allocated==null)throw new IllegalArgumentException("Unallocated target "+original);return allocated.size()==2?flipper.read(Integer.parseInt(name.substring(8))):allocated.getFirst();}
    public VulkanRenderTargets.Image write(int colortex){if(flipper==null)throw new IllegalArgumentException("No color targets");return flipper.write(colortex);}
    /** Frame clears reset both physical sides; temporal buffers are initialized once and retained afterwards. */
    public void beginFrame(Color fogColor){
        context.checkThread();if(closed)throw new IllegalStateException("Pack resources closed");var cmd=context.frame().commands();
        plan.targets().forEach((name,target)->{for(var image:images.get(name))if(target.clear()||!image.initialized()){
            Color clear=target.fogClear()?Objects.requireNonNull(fogColor,"Live fog clear color"):target.clearColor();
            targets.clear(cmd,image,clear.array(),target.format().encoding().equals("SINT"),target.format().encoding().equals("UINT"),1);
        }});
    }
    public void beforePass(String name){
        var pass=plan.passes().get(name);if(pass==null)return;
        for(String buffer:pass.mipmaps()){var image=read(buffer);var spec=plan.targets().get(plan.aliases().getOrDefault(buffer,canonical(buffer)));targets.generateMipmaps(context.frame().commands(),image,!spec.sampler().nearest()&&!spec.format().depth());}
    }
    /** Call after the pass has recorded its writes; previously bound descriptor sets remain immutable. */
    public void afterPass(String name){var pass=plan.passes().get(name);if(pass==null)throw new IllegalArgumentException("Unknown pass "+name);if(flipper!=null)flipper.commitWrites(pass.outputs(),pass.flips());}
    public void bindExternal(String name,FrameDescriptors.Texture texture){external.put(name,Objects.requireNonNull(texture));}
    public Map<String,List<FrameDescriptors.Texture>> descriptors(String program,DescriptorSchema schema){
        var out=new LinkedHashMap<String,List<FrameDescriptors.Texture>>();String stage=stage(program);
        for(var binding:schema.bindings()) {
            var r=binding.resource();var array=new ArrayList<FrameDescriptors.Texture>();
            for(int i=0;i<r.count();i++) {
                String key=stage+"."+r.name()+(r.count()==1?"":"."+i);if(!customImages.containsKey(key))key="*."+r.name()+(r.count()==1?"":"."+i);
                if(customImages.containsKey(key)){var data=customData.get(key);var image=customImages.get(key);array.add(new FrameDescriptors.Texture(image,samplers.get(data.sampler(),image,data.mipmaps())));continue;}
                if(external.containsKey(r.name())){if(r.count()!=1)throw new IllegalArgumentException("External descriptor array "+r.name());array.add(external.get(r.name()));continue;}
                if(r.count()!=1)throw new IllegalArgumentException("Missing texture array element "+r.name()+"["+i+"]");
                String canonical=plan.aliases().getOrDefault(r.name(),canonical(r.name()));var target=plan.targets().get(canonical);
                if(target==null)throw new IllegalArgumentException("Missing actual resource for "+program+": "+r.name());
                var image=read(r.name());var sampler=target.sampler();
                if(r.type().endsWith("Shadow")&&!sampler.compare())sampler=new Sampler(sampler.s(),sampler.t(),sampler.r(),sampler.nearest(),sampler.magNearest(),sampler.mipNearest(),true);
                boolean mip=plan.passes().getOrDefault(program,new Pass(List.of(),Map.of(),Set.of())).mipmaps().contains(canonical)||canonical.startsWith("shadow")&&target.mipmaps();
                if(mip)targets.generateMipmaps(context.frame().commands(),image,!sampler.nearest()&&!target.format().depth());
                array.add(new FrameDescriptors.Texture(image,r.storage()?0:samplers.get(sampler,image,mip)));
            }
            out.put(r.name(),List.copyOf(array));
        }
        return Map.copyOf(out);
    }
    private static String stage(String name){if(name.startsWith("shadowcomp"))return "shadowcomp";if(name.startsWith("shadow")||name.startsWith("gbuffers"))return "gbuffers";for(String s:List.of("setup","begin","prepare","deferred"))if(name.startsWith(s))return s;return "composite";}
    public void close(){if(closed)return;closed=true;samplers.close();targets.close();external.clear();}
}
