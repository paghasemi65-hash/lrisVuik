package net.irisshaders.iris.vulkan.render;

import net.irisshaders.iris.vulkan.VulkanContextBridge;
import net.irisshaders.iris.vulkan.shader.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import static org.lwjgl.vulkan.VK13.*;
import static net.irisshaders.iris.vulkan.VulkanContextBridge.check;

/** Native graphics/compute pipelines using dynamic rendering, with no OpenGL imports. */
public final class VulkanPipelineManager implements AutoCloseable {
    public record Blend(boolean enabled,int sourceRgb,int destinationRgb,int sourceAlpha,int destinationAlpha,int rgbOperation,int alphaOperation,int writeMask) {
        public Blend(boolean enabled,int sourceRgb,int destinationRgb,int sourceAlpha,int destinationAlpha){this(enabled,sourceRgb,destinationRgb,sourceAlpha,destinationAlpha,VK_BLEND_OP_ADD,VK_BLEND_OP_ADD,15);}
        public Blend {if((writeMask&~15)!=0)throw new IllegalArgumentException("Color write mask");}
        public static Blend disabled(){return new Blend(false,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO);}
        public static Blend alpha(){return new Blend(true,VK_BLEND_FACTOR_SRC_ALPHA,VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);}
    }
    public record GraphicsState(List<Integer> colorFormats,int depthFormat,boolean depthTest,boolean depthWrite,
                                int depthCompare,int cullMode,int frontFace,List<Blend> blends,IrisVertexFormats.Layout vertices,
                                int topology,int polygonMode,float depthBiasConstant,float depthBiasSlope) {
        public GraphicsState(List<Integer> formats,int depthFormat,boolean depthTest,boolean depthWrite,int depthCompare,int cullMode,int frontFace,List<Blend> blends,IrisVertexFormats.Layout vertices){
            this(formats,depthFormat,depthTest,depthWrite,depthCompare,cullMode,frontFace,blends,vertices,VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST,VK_POLYGON_MODE_FILL,0,0);
        }
        public GraphicsState {
            colorFormats=List.copyOf(colorFormats);blends=List.copyOf(blends);Objects.requireNonNull(vertices);
            if(colorFormats.size()!=blends.size()||colorFormats.size()>16||depthFormat==VK_FORMAT_UNDEFINED&&(depthTest||depthWrite))throw new IllegalArgumentException("Pipeline attachment state");
            if(!Float.isFinite(depthBiasConstant)||!Float.isFinite(depthBiasSlope))throw new IllegalArgumentException("Depth bias");
        }
        public static GraphicsState fullscreen(List<Integer> formats){return new GraphicsState(formats,VK_FORMAT_UNDEFINED,false,false,VK_COMPARE_OP_ALWAYS,VK_CULL_MODE_NONE,VK_FRONT_FACE_COUNTER_CLOCKWISE,Collections.nCopies(formats.size(),Blend.disabled()),IrisVertexFormats.FULLSCREEN);}
    }
    public record Compiled(ShaderVulkanTransformer.Program program,Map<ShaderStage,byte[]> spirv) {}
    public static final class Pipeline {
        public final long handle,layout,uniformLayout,imageLayout;
        public final ShaderVulkanTransformer.Program program;
        public final GraphicsState graphics;
        private boolean closed;
        private Pipeline(long h,long l,long u,long i,ShaderVulkanTransformer.Program p,GraphicsState g){handle=h;layout=l;uniformLayout=u;imageLayout=i;program=p;graphics=g;}
        public boolean compute(){return graphics==null;}
        public void requireLive(){if(closed)throw new IllegalStateException("Pipeline closed");}
    }
    private final VulkanContextBridge context;
    private final VulkanPipelineCache driverCache;
    private record LayoutBinding(int binding,int count,int type) {}
    private final Map<List<LayoutBinding>,Long> descriptorLayouts=new HashMap<>();
    private final List<Pipeline> pipelines=new ArrayList<>();
    // Identity is intentional: binaries from different pack compilations must not share native pipelines.
    private final Map<Compiled,Map<GraphicsState,Pipeline>> cache=new IdentityHashMap<>();
    private boolean closed;
    public VulkanPipelineManager(VulkanContextBridge context){this(context,null);}
    public VulkanPipelineManager(VulkanContextBridge context,java.nio.file.Path cacheDirectory){this.context=context;context.checkThread();driverCache=new VulkanPipelineCache(context,cacheDirectory);}
    private long descriptorLayout(List<LayoutBinding> bindings,MemoryStack stack){
        return descriptorLayouts.computeIfAbsent(List.copyOf(bindings),key->{
            var nativeBindings=VkDescriptorSetLayoutBinding.calloc(key.size(),stack);
            for(int i=0;i<key.size();i++){var b=key.get(i);nativeBindings.get(i).binding(b.binding).descriptorCount(b.count).descriptorType(b.type).stageFlags(VK_SHADER_STAGE_ALL);}
            var out=stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(context.device(),VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(nativeBindings),null,out),"cached descriptor layout");
            return out.get(0);
        });
    }
    public Pipeline cached(Compiled program,GraphicsState graphics){
        context.checkThread();if(closed)throw new IllegalStateException("Pipeline manager closed");
        var states=cache.computeIfAbsent(program,ignored->new HashMap<>());
        return states.computeIfAbsent(graphics,state->create(program,state));
    }
    public static CompletableFuture<Compiled> compile(SpirvCompiler compiler,String name,ShaderVulkanTransformer.Program program){
        var jobs=new EnumMap<ShaderStage,CompletableFuture<byte[]>>(ShaderStage.class);
        program.sources().forEach((stage,source)->jobs.put(stage,compiler.compile(stage,name,source)));
        return CompletableFuture.allOf(jobs.values().toArray(CompletableFuture[]::new)).thenApply(v->{
            var result=new EnumMap<ShaderStage,byte[]>(ShaderStage.class);jobs.forEach((s,j)->result.put(s,j.join()));return new Compiled(program,Map.copyOf(result));
        });
    }
    public Pipeline create(Compiled compiled,GraphicsState graphics){
        context.checkThread();if(closed)throw new IllegalStateException("Pipeline manager closed");
        boolean compute=compiled.spirv.containsKey(ShaderStage.COMPUTE);
        if(compute!=(graphics==null)||!compute&&!compiled.spirv.keySet().equals(Set.of(ShaderStage.VERTEX,ShaderStage.FRAGMENT)))throw new IllegalArgumentException("Pipeline shader stages");
        long uniformLayout=0,imageLayout=0,layout=0,handle=0;
        var modules=new ArrayList<Long>();
        try(var s=MemoryStack.stackPush()){
            var props=VkPhysicalDeviceProperties.calloc(s);vkGetPhysicalDeviceProperties(context.physicalDevice(),props);
            if(compiled.program.uniforms().size()>props.limits().maxUniformBufferRange())throw new IllegalArgumentException("Uniform block exceeds device limit");
            if(graphics!=null&&graphics.colorFormats.size()>props.limits().maxColorAttachments())throw new IllegalArgumentException("DRAWBUFFERS exceeds device attachment limit");
            if(graphics!=null){
                if(graphics.blends.stream().distinct().count()>1&&!context.enabledFeature("independentBlend"))throw new IllegalArgumentException("Per-buffer blend/write-mask overrides require enabled independentBlend");
                if(graphics.polygonMode!=VK_POLYGON_MODE_FILL&&!context.enabledFeature("fillModeNonSolid"))throw new IllegalArgumentException("Non-solid polygon modes require enabled fillModeNonSolid");
                if(graphics.vertices.stride()>props.limits().maxVertexInputBindingStride()||graphics.vertices.attributes().size()>props.limits().maxVertexInputAttributes())throw new IllegalArgumentException("Vertex layout exceeds enabled device limits");
                for(var attribute:graphics.vertices.attributes())if(attribute.location()>=props.limits().maxVertexInputAttributes()||attribute.offset()>props.limits().maxVertexInputAttributeOffset())throw new IllegalArgumentException("Vertex attribute exceeds device limits: "+attribute.name());
            }
            var out=s.mallocLong(1);
            uniformLayout=descriptorLayout(compiled.program.uniforms().size()==0?List.of():List.of(new LayoutBinding(0,1,VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)),s);
            imageLayout=descriptorLayout(compiled.program.descriptors().bindings().stream().map(b->new LayoutBinding(b.binding(),b.resource().count(),b.resource().storage()?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)).toList(),s);
            check(vkCreatePipelineLayout(context.device(),VkPipelineLayoutCreateInfo.calloc(s).sType$Default().pSetLayouts(s.longs(uniformLayout,imageLayout)),null,out),"pipeline layout");layout=out.get(0);
            var stages=VkPipelineShaderStageCreateInfo.calloc(compiled.spirv.size(),s);int index=0;
            for(var e:compiled.spirv.entrySet()){
                // Heap-sized native allocation avoids overflowing MemoryStack on large shaderpacks.
                var code=org.lwjgl.system.MemoryUtil.memAlloc(e.getValue().length);
                try{code.put(e.getValue()).flip();check(vkCreateShaderModule(context.device(),VkShaderModuleCreateInfo.calloc(s).sType$Default().pCode(code),null,out),"shader module");}
                finally{org.lwjgl.system.MemoryUtil.memFree(code);}
                modules.add(out.get(0));
                stages.get(index++).sType$Default().stage(switch(e.getKey()){case VERTEX->VK_SHADER_STAGE_VERTEX_BIT;case FRAGMENT->VK_SHADER_STAGE_FRAGMENT_BIT;case COMPUTE->VK_SHADER_STAGE_COMPUTE_BIT;}).module(out.get(0)).pName(s.UTF8("main"));
            }
            if(compute){
                var info=VkComputePipelineCreateInfo.calloc(1,s).sType$Default().stage(stages.get(0)).layout(layout);
                out.put(0,0);int result=vkCreateComputePipelines(context.device(),driverCache.handle,info,null,out);handle=out.get(0);check(result,"compute pipeline");
            }else{
                var formats=s.mallocInt(graphics.colorFormats.size());graphics.colorFormats.forEach(formats::put);formats.flip();
                var rendering=VkPipelineRenderingCreateInfo.calloc(s).sType$Default().pColorAttachmentFormats(formats).depthAttachmentFormat(graphics.depthFormat);
                var assembly=VkPipelineInputAssemblyStateCreateInfo.calloc(s).sType$Default().topology(graphics.topology);
                var viewport=VkPipelineViewportStateCreateInfo.calloc(s).sType$Default().viewportCount(1).scissorCount(1);
                var raster=VkPipelineRasterizationStateCreateInfo.calloc(s).sType$Default().polygonMode(graphics.polygonMode).cullMode(graphics.cullMode).frontFace(graphics.frontFace).lineWidth(1f)
                    .depthBiasEnable(graphics.depthBiasConstant!=0||graphics.depthBiasSlope!=0).depthBiasConstantFactor(graphics.depthBiasConstant).depthBiasSlopeFactor(graphics.depthBiasSlope);
                var multisample=VkPipelineMultisampleStateCreateInfo.calloc(s).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
                var depth=VkPipelineDepthStencilStateCreateInfo.calloc(s).sType$Default().depthTestEnable(graphics.depthTest).depthWriteEnable(graphics.depthWrite).depthCompareOp(graphics.depthCompare);
                var blends=VkPipelineColorBlendAttachmentState.calloc(graphics.blends.size(),s);
                for(int i=0;i<graphics.blends.size();i++){var b=graphics.blends.get(i);blends.get(i).blendEnable(b.enabled).srcColorBlendFactor(b.sourceRgb).dstColorBlendFactor(b.destinationRgb).colorBlendOp(b.rgbOperation).srcAlphaBlendFactor(b.sourceAlpha).dstAlphaBlendFactor(b.destinationAlpha).alphaBlendOp(b.alphaOperation).colorWriteMask(b.writeMask);}
                var blend=VkPipelineColorBlendStateCreateInfo.calloc(s).sType$Default().pAttachments(blends);
                var dynamic=VkPipelineDynamicStateCreateInfo.calloc(s).sType$Default().pDynamicStates(s.ints(VK_DYNAMIC_STATE_VIEWPORT,VK_DYNAMIC_STATE_SCISSOR));
                var info=VkGraphicsPipelineCreateInfo.calloc(1,s).sType$Default().pNext(rendering.address()).pStages(stages)
                    .pVertexInputState(graphics.vertices.nativeInfo(s)).pInputAssemblyState(assembly).pViewportState(viewport).pRasterizationState(raster)
                    .pMultisampleState(multisample).pDepthStencilState(depth).pColorBlendState(blend).pDynamicState(dynamic).layout(layout);
                out.put(0,0);int result=vkCreateGraphicsPipelines(context.device(),driverCache.handle,info,null,out);handle=out.get(0);check(result,"dynamic graphics pipeline");
            }
            var result=new Pipeline(handle,layout,uniformLayout,imageLayout,compiled.program,graphics);pipelines.add(result);return result;
        }catch(Throwable t){
            if(handle!=0)vkDestroyPipeline(context.device(),handle,null);if(layout!=0)vkDestroyPipelineLayout(context.device(),layout,null);
            throw t;
        }finally{for(long module:modules)vkDestroyShaderModule(context.device(),module,null);}
    }
    @Override public void close(){
        context.checkThread();if(closed)return;closed=true;var retired=List.copyOf(pipelines);pipelines.clear();cache.clear();retired.forEach(p->p.closed=true);
        var layouts=List.copyOf(descriptorLayouts.values());descriptorLayouts.clear();driverCache.close();
        context.retire(()->{for(var p:retired){vkDestroyPipeline(context.device(),p.handle,null);vkDestroyPipelineLayout(context.device(),p.layout,null);}for(long layout:layouts)vkDestroyDescriptorSetLayout(context.device(),layout,null);});
    }
}
