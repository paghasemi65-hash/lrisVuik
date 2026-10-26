package net.irisshaders.iris.vulkan.fabric;

import net.irisshaders.iris.vulkan.*;
import net.irisshaders.iris.vulkan.pack.*;
import net.irisshaders.iris.vulkan.render.*;
import net.irisshaders.iris.vulkan.shader.*;
import net.irisshaders.iris.vulkan.uniform.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Asynchronous CPU loading with transactional render-thread installation and submission-safe retirement. */
public final class LiveShaderPacks implements AutoCloseable {
    private static volatile LiveShaderPacks active;
    private final VulkanContextBridge context;
    private final ShaderPackLoader loader;
    private final SpirvCompiler compiler;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("iris-pack-loader").factory());
    private final PackConfiguration configuration;
    private final Map<String,String> environment;
    private ShaderPackLoader.Selection selection=new ShaderPackLoader.Selection("","",Map.of());
    private ShaderPackLoader.Selection remembered=selection,jobSelection;
    private long revision,startedRevision=-1,jobRevision;
    private CompletableFuture<ShaderPackLoader.Loaded> job;
    private ShaderPackLoader.Loaded loaded;
    private PackGpuResources resources;
    private String dimension="",status="No shaderpack selected";
    private List<CompilationDiagnostic> diagnostics=List.of();
    private int width,height;
    private long installations;
    private boolean closed;
    private CustomUniforms customUniforms;
    private UniformSnapshot uniformSnapshot;
    public boolean uniformsReady(){return uniformSnapshot!=null;}
    public UniformSnapshot uniforms(){if(uniformSnapshot==null)throw new IllegalStateException("Pack uniforms have not been evaluated");return uniformSnapshot;}
    public java.nio.ByteBuffer uniformsFor(String program,Map<String,UniformValue> drawValues){
        if(loaded==null||!loaded.programs().containsKey(program))throw new IllegalArgumentException("Missing program "+program);
        return uniforms().overlay(drawValues).pack(loaded.programs().get(program).compiled().program().uniforms());
    }
    public LiveShaderPacks(VulkanContextBridge context,Path gameDirectory)throws IOException {
        this.context=context;loader=new ShaderPackLoader(gameDirectory);
        compiler=new SpirvCompiler(gameDirectory,2,context.device().getCapabilities().Vulkan13?SpirvCompiler.Target.VULKAN_1_3:SpirvCompiler.Target.VULKAN_1_2);
        configuration=new PackConfiguration(gameDirectory);environment=environment(context);
        var saved=configuration.read();remembered=saved.remembered();selection=saved.selection();
        active=this;
    }
    public static LiveShaderPacks active(){return active;}
    public ShaderPackLoader.Selection selection(){return selection;}
    public ShaderPackLoader.Loaded loaded(){return loaded;}
    public PackGpuResources resources(){return resources;}
    public String status(){return status;}
    public List<CompilationDiagnostic> diagnostics(){return diagnostics;}
    public long installations(){return installations;}
    public List<String> discover()throws IOException{return loader.discover();}
    public ShaderPackLoader.Selection savedSelection(String pack)throws IOException{return configuration.forPack(pack);}
    public void toggle()throws IOException {
        context.checkThread();
        if(!selection.pack().isEmpty())select(new ShaderPackLoader.Selection("","",Map.of()));
        else if(!remembered.pack().isEmpty())select(remembered);
        else throw new IOException("Choose a shaderpack in Video Settings first");
    }
    public void select(ShaderPackLoader.Selection value)throws IOException {
        context.checkThread();if(!value.pack().isEmpty())loader.resolve(value.pack());
        selection=value;reload();
    }
    private net.minecraft.client.multiplayer.ClientLevel geometryRefreshLevel;
    public void reload(){context.checkThread();revision++;diagnostics=List.of();status=selection.pack().isEmpty()?"No shaderpack selected":"Loading "+selection.pack();}
    public void beforeFrame(){
        context.checkThread();if(closed)return;
        var level=geometryRefreshLevel;geometryRefreshLevel=null;var mc=Minecraft.getInstance();
        // Sodium waits on its terrain fences during invalidation. The prior frame must have been submitted.
        if(level!=null&&mc.level==level)mc.levelRenderer.invalidateCompiledGeometry(level,mc.options,mc.gameRenderer.mainCamera(),mc.getBlockColors());
    }
    public void releaseWorld(){customUniforms=null;uniformSnapshot=null;if(resources!=null){resources.close();resources=null;}}
    public void frame(GameRenderer renderer) {
        context.checkThread();if(closed)return;var mc=Minecraft.getInstance();
        String current=mc.level==null?"minecraft:overworld":mc.level.dimension().identifier().toString();
        if(!dimension.equals(current)){dimension=current;releaseWorld();loaded=null;VertexDataContext.select(null);reload();}
        if(selection.pack().isEmpty()){
            if(startedRevision!=revision)try{configuration.save(new PackConfiguration.State(remembered,false));}catch(IOException failure){fail(failure);}
            releaseWorld();loaded=null;VertexDataContext.select(null);startedRevision=revision;
            // Consume completed obsolete work without ever installing it after an Off request.
            if(job!=null&&job.isDone())job=null;
            return;
        }
        if(job!=null&&job.isDone()) {
            var completed=job;job=null;
            if(jobRevision==revision)try{install(completed.join(),jobSelection,renderer);}
            catch(Exception failure){fail(failure);}
        }
        if(job==null&&startedRevision!=revision) {
            startedRevision=revision;jobRevision=revision;var requested=selection;jobSelection=requested;String requestedDimension=dimension;
            var resourceManager=mc.getResourceManager();
            job=CompletableFuture.supplyAsync(()->{
                try{return loader.load(requested,requestedDimension,environment,compiler,id->{try(var stream=resourceManager.open(Identifier.parse(id))){return stream.readAllBytes();}});}
                catch(Exception e){throw new CompletionException(e);}
            },worker);
        }
        if(!renderer.gameRenderState().shouldRenderLevel){releaseWorld();return;}
        if(loaded==null||!loaded.dimension().equals(dimension))return;
        var main=renderer.mainRenderTarget();int w=main.getColorTexture().getWidth(0),h=main.getColorTexture().getHeight(0);
        if(resources==null||width!=w||height!=h)try{install(loaded,remembered,renderer);}
        catch(Exception failure){fail(failure);return;}
        try {
            var fog=renderer.gameRenderState().levelRenderState.cameraRenderState.fogData.color;
            resources.beginFrame(new PackResourcePlan.Color(fog.x,fog.y,fog.z,1));
            // Installation happens after world extraction. Wait for a capture using this pack's IDs and celestial settings.
            if(IrisVulkanLifecycle.uniforms().capturedPack()!=loaded)return;
            if(customUniforms==null)customUniforms=new CustomUniforms(loaded.properties(),IrisVulkanLifecycle.uniforms().biomeConstants());
            uniformSnapshot=customUniforms.evaluate(IrisVulkanLifecycle.uniforms().snapshot());
        }catch(Exception failure){releaseWorld();loaded=null;VertexDataContext.select(null);fail(failure);}
    }
    /** Called only after the native world pass has ended; allocate and validate before retiring the old state. */
    private void install(ShaderPackLoader.Loaded candidate,ShaderPackLoader.Selection requested,GameRenderer renderer)throws IOException {
        PackGpuResources replacement=null;
        CustomUniforms candidateUniforms=null;
        int w=0,h=0;
        var mc=Minecraft.getInstance();
        try {
            ShadowCamera.Settings.from(candidate.resources().constants());
            if(renderer.gameRenderState().shouldRenderLevel) {
                var main=renderer.mainRenderTarget();w=main.getColorTexture().getWidth(0);h=main.getColorTexture().getHeight(0);
                replacement=new PackGpuResources(context,candidate,w,h);
                var fog=renderer.gameRenderState().levelRenderState.cameraRenderState.fogData.color;
                replacement.beginFrame(new PackResourcePlan.Color(fog.x,fog.y,fog.z,1));
                candidateUniforms=new CustomUniforms(candidate.properties(),IrisVulkanLifecycle.uniforms().biomeConstants());
                // Validate custom dependencies before committing; values are recaptured with candidate IDs next frame.
                candidateUniforms.evaluate(IrisVulkanLifecycle.uniforms().snapshot());
            }
            configuration.save(new PackConfiguration.State(requested,true));
        } catch(Exception failure) {if(replacement!=null)replacement.close();throw failure;}
        var retiring=resources;boolean changed=loaded!=candidate;
        resources=replacement;loaded=candidate;remembered=requested;customUniforms=candidateUniforms;uniformSnapshot=null;width=w;height=h;
        VertexDataContext.select(candidate);
        if(retiring!=null)retiring.close();
        if(replacement!=null) {
            installations++;
            status="Loaded "+candidate.name()+" — "+candidate.programs().size()+" programs, "+replacement.allocations().size()+" targets";
            LoggerFactory.getLogger("IrisVulkan").info("Pack resident: {} dimension={} targets={} customTextures={} samplers={} installation={}",
                candidate.name(),dimension,replacement.allocations().keySet(),replacement.customImages().keySet(),replacement.samplerCount(),installations);
        }else status="Compiled "+candidate.programs().size()+" programs: "+candidate.name();
        if(changed&&mc.level!=null)geometryRefreshLevel=mc.level;
    }
    private void fail(Exception failure) {
        Throwable cause=failure;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
        diagnostics=cause instanceof CompilationDiagnostic.Failure f?f.diagnostics():List.of(CompilationDiagnostic.of(selection.pack(),"loader","",cause,"pack configuration"));
        status="Could not load "+selection.pack()+": "+Objects.toString(cause.getMessage(),cause.toString()).lines().findFirst().orElse("Unknown error");
        for(var diagnostic:diagnostics)LoggerFactory.getLogger("IrisVulkan").error(diagnostic.display());
        var client=Minecraft.getInstance();client.execute(()->net.minecraft.client.gui.components.toasts.SystemToast.add(client.gui.toastManager(),new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(10000),net.minecraft.network.chat.Component.literal("Shaderpack could not load"),net.minecraft.network.chat.Component.literal(selection.pack()+" — see Shaderpacks > Diagnostics")));
    }
    private static Map<String,String> environment(VulkanContextBridge context) {
        var result=new LinkedHashMap<String,String>();
        result.put("MC_VERSION","260300");result.put("MC_GLSL_VERSION","460");result.put("IS_IRIS","1");result.put("IRIS_VULKAN","1");result.put("RENDERPEARL_IS_ZERO_TO_ONE","1");result.put("MAX_COLOR_BUFFERS","16");
        String os=System.getProperty("os.name").toLowerCase(Locale.ROOT);result.put(os.contains("win")?"MC_OS_WINDOWS":os.contains("mac")?"MC_OS_MAC":"MC_OS_LINUX","1");
        try(var stack=MemoryStack.stackPush()){
            var p=VkPhysicalDeviceProperties.calloc(stack);VK10.vkGetPhysicalDeviceProperties(context.physicalDevice(),p);
            String vendor=switch(p.vendorID()){case 0x10DE->"NVIDIA";case 0x1002,0x1022->"AMD";case 0x8086->"INTEL";default->"OTHER";};
            result.put("MC_GL_VENDOR_"+vendor,"1");if(vendor.equals("AMD"))result.put("MC_GL_VENDOR_ATI","1");
            String name=p.deviceNameString().toLowerCase(Locale.ROOT);result.put("MC_GL_RENDERER_"+(name.contains("geforce")?"GEFORCE":name.contains("quadro")?"QUADRO":name.contains("radeon")?"RADEON":vendor.equals("INTEL")?"INTEL":"OTHER"),"1");
        }
        return Map.copyOf(result);
    }
    public void close(){context.checkThread();if(closed)return;closed=true;releaseWorld();worker.close();compiler.close();VertexDataContext.select(null);active=null;}
}
