package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.vulkan.render.*;
import net.irisshaders.iris.vulkan.shader.*;
import net.irisshaders.iris.vulkan.pack.*;
import java.nio.file.Files;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import org.lwjgl.util.vma.*;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.util.vma.Vma.*;
import static net.irisshaders.iris.vulkan.VulkanContextBridge.check;

@Tag("gpu")
class VulkanGpuTest {
    @TempDir Path temp;
    @Test void multidimensionalUniformRingGrowthReuseAndDiskCache() throws Exception {
        try(var gpu=new Gpu();var compiler=new SpirvCompiler(temp,1);var targets=new VulkanRenderTargets(gpu.context);
            var descriptors=new FrameDescriptors(gpu.context,8,1)) {
            var program=new ShaderVulkanTransformer().transformProgram(Map.of(
                ShaderStage.VERTEX,"#version 460\nvoid main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);}",
                ShaderStage.FRAGMENT,"#version 460\nuniform vec4 weights[4][256];void main(){gl_FragColor=weights[3][255];}"));
            assertEquals(List.of(4,256),program.uniforms().member("weights").field().dimensions());
            var binary=VulkanPipelineManager.compile(compiler,"uniform-ring-growth",program).join();
            var state=VulkanPipelineManager.GraphicsState.fullscreen(List.of(VK_FORMAT_R8G8B8A8_UNORM));
            var image=targets.allocate(4,4,VK_FORMAT_R8G8B8A8_UNORM,false,false);
            var depth=targets.allocate(4,4,VK_FORMAT_D32_SFLOAT,true,false);targets.clear(gpu.cmd,depth);
            var first=targets.allocate(4,4,VK_FORMAT_R8G8B8A8_UNORM,false,false);
            var data=ByteBuffer.allocate(program.uniforms().size()).order(ByteOrder.nativeOrder());
            var renderer=new VulkanCompositeRenderer(gpu.context,targets,descriptors);
            Path cache=temp.resolve("pipelines");
            try(var manager=new VulkanPipelineManager(gpu.context,cache)) {
                var pipeline=manager.cached(binary,state);
                for(int pass=0;pass<6;pass++) {
                    float[] values=new float[4096];values[4092]=pass/5f;values[4093]=.25f;values[4094]=.5f;values[4095]=1;
                    program.uniforms().putFloats(data,"weights",values);
                    renderer.draw(pipeline,List.of(pass==0?first:image),null,Map.of(),data,VulkanCompositeRenderer.Draw.fullscreen(),false);
                }
                assertEquals(2,descriptors.allocatedBufferCount(),"Grow with pages, never replace a buffer referenced by recorded commands");
                byte[] pixels=gpu.readback(targets,first,depth);
                int[] expected={0,64,128,255};for(int i=0;i<64;i++)assertEquals(expected[i%4],pixels[i]&255,1);
            }
            try(var files=Files.list(cache)){var file=files.findFirst().orElseThrow();assertTrue(Files.size(file)>=32);}
            try(var manager=new VulkanPipelineManager(gpu.context,cache)){assertNotEquals(0,manager.cached(binary,state).handle);}
            // Re-enter the same completed frame slot: both pages must be reused, not reallocated.
            gpu.restart();
            try(var manager=new VulkanPipelineManager(gpu.context,cache)) {
                renderer.draw(manager.cached(binary,state),List.of(image),null,Map.of(),data,VulkanCompositeRenderer.Draw.fullscreen(),false);
                assertEquals(2,descriptors.allocatedBufferCount());
                byte[] pixels=gpu.readback(targets,image,depth);
                int[] expected={255,64,128,255};for(int i=0;i<64;i++)assertEquals(expected[i%4],pixels[i]&255,1);
            }
            assertTrue(gpu.errors.isEmpty(),String.join("\n",gpu.errors));
        }
    }
    @Test void invalidUploadsAndImageUsesAreRejectedBeforeRecording() throws Exception {
        try(var gpu=new Gpu();var targets=new VulkanRenderTargets(gpu.context)) {
            var color=targets.allocate(8,8,VK_FORMAT_R8G8B8A8_UNORM,false,false);
            assertThrows(IllegalArgumentException.class,()->targets.upload(gpu.cmd,color,new byte[255]));
            assertThrows(IllegalArgumentException.class,()->targets.upload(gpu.cmd,color,new byte[257]));
            assertEquals(VK_IMAGE_LAYOUT_UNDEFINED,color.layout());
            assertFalse(color.initialized());
            var sampled=targets.allocate(new VulkanRenderTargets.Spec(8,8,1,1,1,VK_FORMAT_R8G8B8A8_UNORM,PackResourcePlan.Shape.D2,false,false,false));
            assertThrows(IllegalArgumentException.class,()->targets.transition(gpu.cmd,sampled,VulkanRenderTargets.Use.COLOR));
            assertThrows(IllegalArgumentException.class,()->targets.transition(gpu.cmd,color,VulkanRenderTargets.Use.DEPTH));
            assertEquals(VK_IMAGE_LAYOUT_UNDEFINED,sampled.layout());
            targets.upload(gpu.cmd,color,new byte[256]);
            assertTrue(color.initialized());
        }
    }
    @Test void reversedDepthConversionPreservesRowsAndNonlinearValues() throws Exception {
        try(var gpu=new Gpu();var compiler=new SpirvCompiler(temp,1);var targets=new VulkanRenderTargets(gpu.context);
            var descriptors=new FrameDescriptors(gpu.context,4,4);var pipelines=new VulkanPipelineManager(gpu.context);var samplers=new VulkanSamplers(gpu.context)) {
            var input=targets.allocate(8,8,VK_FORMAT_D32_SFLOAT,true,false);
            var output=targets.allocate(8,8,VK_FORMAT_D32_SFLOAT,true,false);
            var color=targets.allocate(8,8,VK_FORMAT_R8G8B8A8_UNORM,false,false);
            var bytes=ByteBuffer.allocate(8*8*4).order(ByteOrder.nativeOrder());
            for(int i=0;i<64;i++)bytes.putFloat(i/63f);
            targets.upload(gpu.cmd,input,bytes.array());targets.clear(gpu.cmd,color);
            var pipeline=pipelines.create(VulkanPipelineManager.compile(compiler,"depth-conversion",VulkanDepthConverter.program()).join(),VulkanDepthConverter.state(output.format));
            var spec=new PackResourcePlan.Sampler(PackResourcePlan.Wrap.CLAMP_TO_EDGE,PackResourcePlan.Wrap.CLAMP_TO_EDGE,PackResourcePlan.Wrap.CLAMP_TO_EDGE,true,true,false);
            VulkanDepthConverter.record(new VulkanCompositeRenderer(gpu.context,targets,descriptors),pipeline,input,output,samplers.get(spec,input,false));
            var pixels=ByteBuffer.wrap(gpu.readback(targets,color,output)).order(ByteOrder.nativeOrder());
            for(int i=0;i<64;i++)assertEquals(1-i/63f,pixels.getFloat(256+i*4),1e-6,"depth row/index "+i);
            assertTrue(gpu.errors.isEmpty(),String.join("\n",gpu.errors));
        }
    }
    @Test void packResourcesMipmapsTemporalClearsAndMultidimensionalDescriptors() throws Exception {
        Path root=temp.resolve("shaderpacks/Resources/shaders");Files.createDirectories(root);
        Files.writeString(root.resolve("shaders.properties"),"""
            size.buffer.colortex0=8 8
            size.buffer.colortex3=0.5 0.5
            flip.composite.colortex3=false
            texture.composite.lut=lut.raw TEXTURE_1D RGBA8 4 RGBA UNSIGNED_BYTE
            texture.composite.volume=volume.raw TEXTURE_3D RGBA8 4 4 4 RGBA UNSIGNED_BYTE
            texture.composite.sky=cube.raw TEXTURE_CUBE_MAP RGBA8 4 4 RGBA UNSIGNED_BYTE
            texture.composite.layers=layers.raw TEXTURE_2D_ARRAY RGBA8 4 4 2 RGBA UNSIGNED_BYTE
            """);
        for(var entry:Map.of("lut",4,"volume",64,"cube",96,"layers",32).entrySet()){
            byte[] data=new byte[entry.getValue()*4];for(int i=0;i<data.length;i+=4){data[i]=102;data[i+1]=51;data[i+2]=(byte)204;data[i+3]=(byte)255;}
            Files.write(root.resolve(entry.getKey()+".raw"),data);
        }
        Files.writeString(root.resolve("composite.csh"),"""
            #version 460
            layout(local_size_x=1,local_size_y=1) in;
            layout(rgba8) uniform image2D colorimg0;
            uniform sampler2D colortex3;
            uniform sampler2D depthtex0;
            uniform sampler2DShadow shadowtex0;
            uniform usampler2D colortex7;
            uniform sampler1D lut;
            uniform sampler3D volume;
            uniform samplerCube sky;
            uniform sampler2DArray layers;
            const bool colortex3Clear=false;
            const vec4 colortex3ClearColor=vec4(0.1,0.1,0.1,1);
            const bool colortex3MipmapEnabled=true;
            const int colortex4Format=RGBA16F;
            const int colortex5Format=R11F_G11F_B10F;
            const int colortex6Format=RGBA32F;
            const int colortex7Format=RGBA32UI;
            const vec4 colortex7ClearColor=vec4(2,3,4,5);
            const int shadowMapResolution=8;
            void main(){
                vec4 value=texelFetch(colortex3,ivec2(0),3)+texelFetch(lut,0,0)+texture(volume,vec3(0.5))+texture(sky,vec3(1,0,0))+texelFetch(layers,ivec3(0,0,1),0);
                bool checks=texture(shadowtex0,vec3(0.5,0.5,0.5))==1.0&&all(equal(texelFetch(colortex7,ivec2(0),0),uvec4(2,3,4,5)));
                imageStore(colorimg0,ivec2(gl_GlobalInvocationID.xy),checks?value/5.0:vec4(0));
            }
            """);
        try(var gpu=new Gpu();var compiler=new SpirvCompiler(temp,2);var pipelines=new VulkanPipelineManager(gpu.context);var descriptors=new FrameDescriptors(gpu.context,16,16)) {
            var loaded=new ShaderPackLoader(temp).load(new ShaderPackLoader.Selection("Resources","",Map.of()),"minecraft:overworld",Map.of(),compiler,null);
            try(var resources=new PackGpuResources(gpu.context,loaded,16,16)) {
                resources.beginFrame(new PackResourcePlan.Color(0,0,0,1));
                assertEquals(8,resources.read("colortex3").width);assertEquals(4,resources.read("colortex3").mipLevels);
                assertEquals(VK_FORMAT_R16G16B16A16_SFLOAT,resources.read("colortex4").format);
                assertEquals(VK_FORMAT_B10G11R11_UFLOAT_PACK32,resources.read("colortex5").format);
                assertEquals(VK_FORMAT_R32G32B32A32_SFLOAT,resources.read("colortex6").format);
                byte[] temporal=new byte[8*8*4];for(int i=0;i<temporal.length;i+=4){temporal[i]=102;temporal[i+1]=51;temporal[i+2]=(byte)204;temporal[i+3]=(byte)255;}
                resources.targets().upload(gpu.cmd,resources.read("colortex3"),temporal);
                resources.beginFrame(new PackResourcePlan.Color(0,0,0,1));
                long before=resources.read("colortex3").handle;resources.beforePass("composite.compute");
                assertTrue(resources.read("colortex3").mipmapsValid());
                var program=loaded.programs().get("composite.compute");var pipeline=pipelines.create(program.compiled(),null);
                var bindings=resources.descriptors("composite.compute",program.compiled().program().descriptors());
                new VulkanCompositeRenderer(gpu.context,resources.targets(),descriptors).dispatch(pipeline,bindings,null,8,8,1);
                resources.afterPass("composite.compute");assertEquals(before,resources.read("colortex3").handle);
                byte[] result=gpu.readback(resources.targets(),resources.read("colortex0"),resources.read("depthtex0"));
                int[] expected={102,51,204,255};for(int i=0;i<8*8*4;i++)assertEquals(expected[i%4],result[i]&255,1,"pack pixel "+i);
                assertTrue(gpu.errors.isEmpty(),String.join("\n",gpu.errors));
            }
        }
    }
    @Test void graphicsToComputeToReadbackAndDepthCopy() throws Exception {
        try(var gpu=new Gpu();var compiler=new SpirvCompiler(temp,2);
            var targets=new VulkanRenderTargets(gpu.context);var descriptors=new FrameDescriptors(gpu.context,16,32);
            var pipelines=new VulkanPipelineManager(gpu.context)) {
            var transform=new ShaderVulkanTransformer();
            var graphics=transform.transformProgram(Map.of(
                ShaderStage.VERTEX,"""
                    #version 460
                    void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.0,1.0);}
                    """,
                ShaderStage.FRAGMENT,"""
                    #version 120
                    uniform vec4 tint;
                    void main(){gl_FragColor=tint;}
                    """));
            var compute=transform.transformProgram(Map.of(ShaderStage.COMPUTE,"""
                #version 430
                layout(local_size_x=1,local_size_y=1) in;
                uniform sampler2D colortex0;
                layout(rgba8) uniform image2D colorimg0;
                void main(){ivec2 p=ivec2(gl_GlobalInvocationID.xy);imageStore(colorimg0,p,texelFetch(colortex0,p,0)*0.5);}
                """));
            var gp=pipelines.create(VulkanPipelineManager.compile(compiler,"gpu-graphics",graphics).join(),VulkanPipelineManager.GraphicsState.fullscreen(List.of(VK_FORMAT_R8G8B8A8_UNORM)));
            var cp=pipelines.create(VulkanPipelineManager.compile(compiler,"gpu-compute",compute).join(),null);
            var a=targets.allocate(8,8,VK_FORMAT_R8G8B8A8_UNORM,false,false);
            var laterDraw=targets.allocate(8,8,VK_FORMAT_R8G8B8A8_UNORM,false,false);
            var b=targets.allocate(8,8,VK_FORMAT_R8G8B8A8_UNORM,false,true);
            var depth=targets.allocate(8,8,VK_FORMAT_D32_SFLOAT,true,false);
            var snapshot=targets.allocate(8,8,VK_FORMAT_D32_SFLOAT,true,false);
            var renderer=new VulkanCompositeRenderer(gpu.context,targets,descriptors);
            var uniform=ByteBuffer.allocate(graphics.uniforms().size()).order(ByteOrder.nativeOrder());
            graphics.uniforms().putFloats(uniform,"tint",0.25f,0.5f,0.75f,1f);
            targets.clear(gpu.cmd,b);targets.clear(gpu.cmd,depth);targets.copyDepth(gpu.cmd,depth,snapshot);
            renderer.draw(gp,List.of(a),null,Map.of(),uniform,VulkanCompositeRenderer.Draw.fullscreen(),false);
            graphics.uniforms().putFloats(uniform,"tint",1f,0f,0f,1f);
            renderer.draw(gp,List.of(laterDraw),null,Map.of(),uniform,VulkanCompositeRenderer.Draw.fullscreen(),false);
            assertEquals(1,descriptors.allocatedBufferCount(),"Two pass snapshots share one mapped ring page");
            long sampler;
            try(var s=MemoryStack.stackPush()) {
                var out=s.mallocLong(1);check(vkCreateSampler(gpu.device,VkSamplerCreateInfo.calloc(s).sType$Default().magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),null,out),"sampler");sampler=out.get(0);
            }
            try {
                renderer.dispatch(cp,Map.of("colortex0",List.of(new FrameDescriptors.Texture(a,sampler)),"colorimg0",List.of(new FrameDescriptors.Texture(b,0))),null,8,8,1);
                // Same-layout GENERAL->GENERAL barrier between compute passes must also be legal.
                renderer.dispatch(cp,Map.of("colortex0",List.of(new FrameDescriptors.Texture(a,sampler)),"colorimg0",List.of(new FrameDescriptors.Texture(b,0))),null,8,8,1);
                byte[] pixels=gpu.readback(targets,b,snapshot);
                int[] expected={32,64,96,128};
                for(int p=0;p<64;p++)for(int c=0;c<4;c++)assertEquals(expected[c],pixels[p*4+c]&255,1,"pixel "+p+" component "+c);
                var depthBytes=ByteBuffer.wrap(pixels).order(ByteOrder.nativeOrder());
                for(int p=0;p<64;p++)assertEquals(1f,depthBytes.getFloat(256+p*4),0f,"copied depth "+p);
                assertTrue(snapshot.initialized());
                assertTrue(gpu.errors.isEmpty(),String.join("\n",gpu.errors));
            } finally {vkDeviceWaitIdle(gpu.device);vkDestroySampler(gpu.device,sampler,null);}
        }
    }

    @Test void pipelineCachePreservesBlendOperationsWriteMasksAndBaseInstance() throws Exception {
        try(var gpu=new Gpu();var compiler=new SpirvCompiler(temp,1);var targets=new VulkanRenderTargets(gpu.context);
            var descriptors=new FrameDescriptors(gpu.context,8,1);var pipelines=new VulkanPipelineManager(gpu.context)) {
            var program=new ShaderVulkanTransformer().transformProgram(Map.of(
                ShaderStage.VERTEX,"#version 460\nflat out float instance;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2-1,0,1);instance=float(gl_InstanceID);}",
                ShaderStage.FRAGMENT,"#version 460\nflat in float instance;void main(){gl_FragColor=vec4(instance/10.0,0.9,0.6,1);}"));
            var binary=VulkanPipelineManager.compile(compiler,"state-and-instance",program).join();
            var formats=List.of(VK_FORMAT_R8G8B8A8_UNORM);
            var disabled=VulkanPipelineManager.GraphicsState.fullscreen(formats);
            var baseline=pipelines.cached(binary,disabled);assertSame(baseline,pipelines.cached(binary,disabled));
            var masked=new VulkanPipelineManager.GraphicsState(formats,VK_FORMAT_UNDEFINED,false,false,VK_COMPARE_OP_ALWAYS,VK_CULL_MODE_NONE,VK_FRONT_FACE_COUNTER_CLOCKWISE,
                List.of(new VulkanPipelineManager.Blend(true,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ONE,VK_BLEND_OP_REVERSE_SUBTRACT,VK_BLEND_OP_ADD,5)),IrisVertexFormats.FULLSCREEN);
            var variant=pipelines.cached(binary,masked);assertNotSame(baseline,variant);assertSame(variant,pipelines.cached(binary,masked));
            assertEquals(baseline.uniformLayout,variant.uniformLayout);assertEquals(baseline.imageLayout,variant.imageLayout);
            var image=targets.allocate(4,4,VK_FORMAT_R8G8B8A8_UNORM,false,false);var depth=targets.allocate(4,4,VK_FORMAT_D32_SFLOAT,true,false);
            targets.clear(gpu.cmd,image,new float[]{.8f,.4f,.8f,1},false,false,1);targets.clear(gpu.cmd,depth);
            new VulkanCompositeRenderer(gpu.context,targets,descriptors).draw(variant,List.of(image),null,Map.of(),null,
                new VulkanCompositeRenderer.Draw(0,0,0,0,VK_INDEX_TYPE_UINT32,3,1,0,0,5),false);
            var pixels=gpu.readback(targets,image,depth);
            // Reverse subtraction uses the real firstInstance=5: .8-.5=.3; green/alpha writes are masked out.
            int[] expected={77,102,51,255};for(int p=0;p<16;p++)for(int c=0;c<4;c++)assertEquals(expected[c],pixels[p*4+c]&255,1);
            assertTrue(gpu.errors.isEmpty(),String.join("\n",gpu.errors));
        }
    }

    private static final class Gpu implements AutoCloseable {
        VkInstance instance;VkDevice device;VkQueue queue;VkCommandBuffer cmd;
        long allocator,pool,messenger;VulkanContextBridge context;
        VkDebugUtilsMessengerCallbackEXT callback;
        final List<String> errors=new ArrayList<>();
        final List<Runnable> retired=new ArrayList<>();
        long submission=2,completed=1;
        Gpu(){
            try(var s=MemoryStack.stackPush()){
                var count=s.mallocInt(1);check(vkEnumerateInstanceLayerProperties(count,null),"layers");var layers=VkLayerProperties.calloc(count.get(0),s);check(vkEnumerateInstanceLayerProperties(count,layers),"layers");
                boolean validation=layers.stream().anyMatch(p->p.layerNameString().equals("VK_LAYER_KHRONOS_validation"));
                var info=VkInstanceCreateInfo.calloc(s).sType$Default().pApplicationInfo(VkApplicationInfo.calloc(s).sType$Default().pApplicationName(s.UTF8("Iris Vulkan Test")).apiVersion(VK_API_VERSION_1_3));
                if(validation)info.ppEnabledLayerNames(s.pointers(s.UTF8("VK_LAYER_KHRONOS_validation"))).ppEnabledExtensionNames(s.pointers(s.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME)));
                var pointer=s.mallocPointer(1);check(vkCreateInstance(info,null,pointer),"instance");instance=new VkInstance(pointer.get(0),info);
                if(validation){
                    callback=VkDebugUtilsMessengerCallbackEXT.create((severity,type,data,user)->{String message=VkDebugUtilsMessengerCallbackDataEXT.create(data).pMessageString();if((severity&VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)!=0)errors.add(message);return VK_FALSE;});
                    var debug=VkDebugUtilsMessengerCreateInfoEXT.calloc(s).sType$Default().messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT|VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT).pfnUserCallback(callback);
                    var out=s.mallocLong(1);check(vkCreateDebugUtilsMessengerEXT(instance,debug,null,out),"debug messenger");messenger=out.get(0);
                }
                check(vkEnumeratePhysicalDevices(instance,count,null),"physical devices");if(count.get(0)==0)throw new IllegalStateException("No Vulkan GPU");
                var physicals=s.mallocPointer(count.get(0));check(vkEnumeratePhysicalDevices(instance,count,physicals),"physical devices");
                var physical=new VkPhysicalDevice(physicals.get(0),instance);
                var properties=VkPhysicalDeviceProperties.calloc(s);vkGetPhysicalDeviceProperties(physical,properties);
                System.out.println("GPU: "+properties.deviceNameString()+"; Khronos validation: "+validation);
                vkGetPhysicalDeviceQueueFamilyProperties(physical,count,null);var queues=VkQueueFamilyProperties.calloc(count.get(0),s);vkGetPhysicalDeviceQueueFamilyProperties(physical,count,queues);
                int family=-1;for(int i=0;i<queues.capacity();i++)if((queues.get(i).queueFlags()&(VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT))==(VK_QUEUE_GRAPHICS_BIT|VK_QUEUE_COMPUTE_BIT)){family=i;break;}
                if(family<0)throw new IllegalStateException("No graphics+compute queue");
                var feature13=VkPhysicalDeviceVulkan13Features.calloc(s).sType$Default().dynamicRendering(true).synchronization2(true);
                var deviceInfo=VkDeviceCreateInfo.calloc(s).sType$Default().pNext(feature13.address()).pQueueCreateInfos(VkDeviceQueueCreateInfo.calloc(1,s).sType$Default().queueFamilyIndex(family).pQueuePriorities(s.floats(1)));
                check(vkCreateDevice(physical,deviceInfo,null,pointer),"device");device=new VkDevice(pointer.get(0),physical,deviceInfo);
                vkGetDeviceQueue(device,family,0,pointer);queue=new VkQueue(pointer.get(0),device);
                var functions=VmaVulkanFunctions.calloc(s).set(instance,device);
                var allocatorInfo=VmaAllocatorCreateInfo.calloc(s).instance(instance).physicalDevice(physical).device(device).vulkanApiVersion(VK_API_VERSION_1_3).pVulkanFunctions(functions);
                check(vmaCreateAllocator(allocatorInfo,pointer),"VMA allocator");allocator=pointer.get(0);
                var out=s.mallocLong(1);check(vkCreateCommandPool(device,VkCommandPoolCreateInfo.calloc(s).sType$Default().queueFamilyIndex(family),null,out),"command pool");pool=out.get(0);
                check(vkAllocateCommandBuffers(device,VkCommandBufferAllocateInfo.calloc(s).sType$Default().commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1),pointer),"command buffer");cmd=new VkCommandBuffer(pointer.get(0),device);
                check(vkBeginCommandBuffer(cmd,VkCommandBufferBeginInfo.calloc(s).sType$Default()),"begin commands");
                context=new VulkanContextBridge(instance,device,allocator,family,new VulkanContextBridge.Host(){
                    public VkCommandBuffer commandBufferOutsideRenderPass(){return cmd;}public long submissionIndex(){return submission;}public long completedSubmissionIndex(){return completed;}
                    public int submissionsInFlight(){return 2;}public void retireAfterGpu(Runnable destructor){retired.add(destructor);}
                });
            }catch(Throwable t){close();throw t;}
        }
        void restart(){
            completed=submission;submission+=2;
            check(vkResetCommandPool(device,pool,0),"reset completed command pool");
            try(var stack=MemoryStack.stackPush()){check(vkBeginCommandBuffer(cmd,VkCommandBufferBeginInfo.calloc(stack).sType$Default()),"restart command recording");}
        }
        byte[] readback(VulkanRenderTargets targets,VulkanRenderTargets.Image image,VulkanRenderTargets.Image depth){
            try(var s=MemoryStack.stackPush()){
                int colorBytes=image.width*image.height*4,size=colorBytes+depth.width*depth.height*4;var out=s.mallocLong(1);var allocation=s.mallocPointer(1);var ai=VmaAllocationInfo.calloc(s);
                check(vmaCreateBuffer(allocator,VkBufferCreateInfo.calloc(s).sType$Default().size(size).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT),
                    VmaAllocationCreateInfo.calloc(s).usage(VMA_MEMORY_USAGE_AUTO).flags(VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT|VMA_ALLOCATION_CREATE_MAPPED_BIT),out,allocation,ai),"readback buffer");
                long buffer=out.get(0),alloc=allocation.get(0);
                try{
                    targets.transition(cmd,image,VulkanRenderTargets.Use.COPY_SOURCE);
                    var region=VkBufferImageCopy.calloc(1,s).imageSubresource(r->r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1)).imageExtent(e->e.set(image.width,image.height,1));
                    vkCmdCopyImageToBuffer(cmd,image.handle,image.layout(),buffer,region);
                    targets.transition(cmd,depth,VulkanRenderTargets.Use.COPY_SOURCE);
                    var depthRegion=VkBufferImageCopy.calloc(1,s).bufferOffset(colorBytes).imageSubresource(r->r.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).layerCount(1)).imageExtent(e->e.set(depth.width,depth.height,1));
                    vkCmdCopyImageToBuffer(cmd,depth.handle,depth.layout(),buffer,depthRegion);
                    var barrier=VkBufferMemoryBarrier2.calloc(1,s).sType$Default().srcStageMask(VK_PIPELINE_STAGE_2_TRANSFER_BIT).srcAccessMask(VK_ACCESS_2_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_2_HOST_BIT).dstAccessMask(VK_ACCESS_2_HOST_READ_BIT).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).buffer(buffer).size(size);
                    vkCmdPipelineBarrier2(cmd,VkDependencyInfo.calloc(s).sType$Default().pBufferMemoryBarriers(barrier));
                    check(vkEndCommandBuffer(cmd),"end commands");check(vkQueueSubmit(queue,VkSubmitInfo.calloc(1,s).sType$Default().pCommandBuffers(s.pointers(cmd)),0),"submit");check(vkQueueWaitIdle(queue),"wait GPU");
                    vmaInvalidateAllocation(allocator,alloc,0,size);byte[] bytes=new byte[size];MemoryUtil.memByteBuffer(ai.pMappedData(),size).get(bytes);return bytes;
                }finally{vkDeviceWaitIdle(device);vmaDestroyBuffer(allocator,buffer,alloc);}
            }
        }
        @Override public void close(){
            if(device!=null){vkDeviceWaitIdle(device);retired.forEach(Runnable::run);if(pool!=0)vkDestroyCommandPool(device,pool,null);if(allocator!=0)vmaDestroyAllocator(allocator);vkDestroyDevice(device,null);device=null;}
            if(messenger!=0){vkDestroyDebugUtilsMessengerEXT(instance,messenger,null);messenger=0;}
            if(callback!=null){callback.free();callback=null;}if(instance!=null){vkDestroyInstance(instance,null);instance=null;}
        }
    }
}
