package net.irisshaders.iris.vulkan.verify;

import net.irisshaders.iris.vulkan.fabric.*;
import net.irisshaders.iris.vulkan.pack.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.*;
import org.slf4j.LoggerFactory;
import static org.lwjgl.vulkan.VK10.*;

/** Development-only fixtures exercise the production loader on Minecraft's device and submission timeline. */
final class PackVerification {
    private static int stage,frames;
    private static long installation;
    private static CompletableFuture<?> pending=CompletableFuture.completedFuture(null);
    private static final String FOLDER="iris-verification-folder", ZIP="iris-verification-archive.zip", BROKEN="iris-verification-broken";
    static boolean frame(Minecraft mc,String world) {
        try {
            frames++;
            var packs=Objects.requireNonNull(LiveShaderPacks.active(),"Live pack controller");
            switch(stage) {
                case 0 -> {
                    fixtures(mc.gameDirectory.toPath());
                    require(packs.discover().containsAll(List.of(FOLDER,ZIP,BROKEN)),"Pack discovery");
                    packs.select(new ShaderPackLoader.Selection(FOLDER,"high",Map.of())); next();
                }
                case 1 -> {
                    if(!resident(packs,FOLDER))return false;
                    checkResources(packs,mc,2,VK_FORMAT_R16G16B16A16_SFLOAT);
                    require(packs.loaded().fallbacks().get("gbuffers_terrain").equals("gbuffers_basic"),"Live fallback resolution");
                    mc.gui.setScreen(new ShaderPackScreen(null));next();
                }
                case 2 -> {
                    if(frames<15)return false;
                    pending=screenshot(mc,"phase2-shaderpacks.png");next();
                }
                case 3 -> {
                    if(!pending.isDone())return false;pending.join();mc.gui.setScreen(null);
                    installation=packs.installations();packs.select(new ShaderPackLoader.Selection(FOLDER,"high",Map.of("QUALITY","1")));next();
                }
                case 4 -> {
                    if(packs.installations()<=installation||!resident(packs,FOLDER))return false;
                    checkResources(packs,mc,1,VK_FORMAT_R16G16B16A16_SFLOAT);
                    installation=packs.installations();mc.getWindow().setWindowed(1100,760);next();
                }
                case 5 -> {
                    if(packs.installations()<=installation||frames<30||!resident(packs,FOLDER))return false;
                    checkResources(packs,mc,1,VK_FORMAT_R16G16B16A16_SFLOAT);
                    installation=packs.installations();pending=mc.reloadResourcePacks();next();
                }
                case 6 -> {
                    if(!pending.isDone()||packs.installations()<=installation||frames<30||!resident(packs,FOLDER))return false;pending.join();
                    checkResources(packs,mc,1,VK_FORMAT_R16G16B16A16_SFLOAT);
                    installation=packs.installations();packs.select(new ShaderPackLoader.Selection(ZIP,"",Map.of()));next();
                }
                case 7 -> {
                    if(packs.installations()<=installation||!resident(packs,ZIP))return false;
                    checkResources(packs,mc,1,VK_FORMAT_R32G32B32A32_SFLOAT);
                    installation=packs.installations();mc.disconnect(new TitleScreen(),false);next();
                }
                case 8 -> {
                    if(mc.level!=null||frames<20)return false;
                    require(packs.resources()==null,"World unload retained GPU targets");next();
                    mc.createWorldOpenFlows().openWorld(world,()->{throw new IllegalStateException("Phase 2 world reentry failed");});
                }
                case 9 -> {
                    if(mc.level==null||packs.installations()<=installation||!resident(packs,ZIP))return false;
                    checkResources(packs,mc,1,VK_FORMAT_R32G32B32A32_SFLOAT);
                    packs.select(new ShaderPackLoader.Selection(BROKEN,"",Map.of()));next();
                }
                case 10 -> {
                    if(packs.diagnostics().isEmpty())return false;
                    var diagnostic=packs.diagnostics().getFirst();
                    require(diagnostic.file().equals("final.fsh")&&diagnostic.line()>0&&diagnostic.snippet().contains("missingVerificationSymbol"),"In-game compiler diagnostic lost location/source");
                    packs.select(new ShaderPackLoader.Selection("","",Map.of()));next();
                }
                case 11 -> {
                    if(frames<30)return false;
                    require(packs.resources()==null&&packs.loaded()==null,"Off did not release shaderpack state");
                    Files.writeString(mc.gameDirectory.toPath().resolve("iris-phase2-result.json"),"{\"passed\":true,\"stages\":12,\"installations\":"+packs.installations()+",\"folder\":true,\"zip\":true,\"profileAndOverrides\":true,\"resize\":true,\"reload\":true,\"reentry\":true,\"diagnostics\":true,\"off\":true}");
                    next();return System.getProperty("iris.benchmark.pack","").isEmpty();
                }
                case 12 -> {
                    Path source=Path.of(System.getProperty("iris.benchmark.pack"));
                    Files.copy(source,mc.gameDirectory.toPath().resolve("shaderpacks").resolve(source.getFileName()),StandardCopyOption.REPLACE_EXISTING);
                    packs.select(new ShaderPackLoader.Selection(source.getFileName().toString(),"",Map.of()));next();
                }
                case 13 -> {
                    String name=Path.of(System.getProperty("iris.benchmark.pack")).getFileName().toString();
                    if(!resident(packs,name)||frames<30)return false;
                    var values=packs.uniforms();
                    for(String uniform:List.of("framemod8","isEyeInCave","rainFactor","starter","inPaleGarden","inSulfurCaves","endFlashIntensityM"))
                        require(Double.isFinite(values.require(uniform).scalar()),"Benchmark custom uniform "+uniform);
                    require(packs.loaded().itemIds().get("minecraft:torch")==44002,"Complementary held item mapping");
                    Files.writeString(mc.gameDirectory.toPath().resolve("iris-phase3-benchmark.json"),"{\"pack\":\""+name+"\",\"loaded\":true,\"programs\":"+packs.loaded().programs().size()+",\"liveUniforms\":true,\"worldEffectsRendered\":false}");
                    packs.select(new ShaderPackLoader.Selection("","",Map.of()));next();
                }
                case 14 -> {if(frames<20)return false;require(packs.resources()==null,"Benchmark retirement");return true;}
                default -> {return true;}
            }
            return false;
        }catch(Exception e){throw new IllegalStateException("Phase 2 verification stage "+stage,e);}
    }
    private static boolean resident(LiveShaderPacks packs,String name){
        if(!packs.diagnostics().isEmpty())throw new IllegalStateException(packs.status()+" "+packs.diagnostics());
        return packs.loaded()!=null&&packs.loaded().name().equals(name)&&packs.resources()!=null&&packs.uniformsReady();
    }
    private static void checkResources(LiveShaderPacks packs,Minecraft mc,int quality,int format){
        var resources=packs.resources();var image=resources.read("colortex0");
        int width=mc.gameRenderer.mainRenderTarget().getColorTexture().getWidth(0),height=mc.gameRenderer.mainRenderTarget().getColorTexture().getHeight(0);
        require(image.format==format&&image.width==width/2&&image.height==height/2&&image.mipLevels>1,"Actual format/relative extent/mip chain");
        require(image.handle!=0&&image.view!=0&&resources.samplerCount()>0,"Missing native allocation/sampler");
        require(resources.read("shadowtex0").width==64,"Pack shadow map resolution");
        require(resources.customImages().containsKey("*.noisetex")&&resources.customImages().containsKey("composite.lookup"),"Real noise and custom upload");
        require(packs.loaded().values().get("QUALITY").equals(Integer.toString(quality)),"Profile/option result");
        var uniforms=packs.uniforms();
        require(uniforms.require("cameraHeight").scalar()==uniforms.require("cameraPosition").elements()[1],"Live custom uniform dependency");
        var layout=packs.loaded().programs().get("gbuffers_basic").compiled().program().uniforms();
        var bytes=packs.uniformsFor("gbuffers_basic",Map.of());
        require(bytes.getFloat(layout.member("cameraHeight").offset())==(float)uniforms.require("cameraHeight").scalar(),"Packed pass UBO");
    }
    private static void next(){LoggerFactory.getLogger("IrisVerification").info("Phase 2 verification stage {} complete",stage);stage++;frames=0;}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static CompletableFuture<Void> screenshot(Minecraft mc,String name){
        var future=new CompletableFuture<Void>();Screenshot.grab(mc.gameDirectory,name,mc.gameRenderer.mainRenderTarget(),1,message->{
            if(Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots").resolve(name)))future.complete(null);
            else future.completeExceptionally(new IllegalStateException(message.getString()));});return future;
    }
    private static void fixtures(Path game)throws Exception {
        String vertex="#version 460\nvoid main(){gl_Position=vec4(0,0,0,1);}\n";
        String fragment="""
            #version 460
            #define QUALITY 1 // [1 2]
            #ifndef RENDERPEARL_IS_ZERO_TO_ONE
            #error Missing live Vulkan environment
            #endif
            uniform sampler2D colortex0;
            uniform sampler2D depthtex0;
            uniform sampler2DShadow shadowtex0;
            uniform sampler2D noisetex;
            uniform float cameraHeight;
            const int colortex0Format=RGBA16F;
            const bool colortex0Clear=false;
            const bool colortex0MipmapEnabled=true;
            const int shadowMapResolution=64;
            const bool shadowHardwareFiltering0=true;
            const int noiseTextureResolution=16;
            void main(){gl_FragColor=vec4(float(QUALITY));}
            """;
        Path shaders=game.resolve("shaderpacks").resolve(FOLDER).resolve("shaders");Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("gbuffers_basic.vsh"),vertex);Files.writeString(shaders.resolve("gbuffers_basic.fsh"),fragment);
        Files.writeString(shaders.resolve("shaders.properties"),"profile.high=QUALITY:2\nsize.buffer.colortex0=0.5 0.5\ntexture.composite.lookup=lookup.bin TEXTURE_1D RGBA8 4 RGBA UNSIGNED_BYTE\nuniform.float.cameraHeight=cameraPosition.y\nuniform.float.rainFactor=smooth(1,rainStrength,3,3)\n");
        Files.write(shaders.resolve("lookup.bin"),new byte[]{0,0,0,-1,64,64,64,-1,-128,-128,-128,-1,-1,-1,-1,-1});
        try(var zip=new ZipOutputStream(Files.newOutputStream(game.resolve("shaderpacks").resolve(ZIP)));var files=Files.list(shaders)){
            for(Path file:files.toList()){zip.putNextEntry(new ZipEntry("shaders/"+file.getFileName()));zip.write(file.getFileName().toString().endsWith(".fsh")?fragment.replace("RGBA16F","RGBA32F").getBytes(java.nio.charset.StandardCharsets.UTF_8):Files.readAllBytes(file));zip.closeEntry();}
        }
        Path broken=game.resolve("shaderpacks").resolve(BROKEN).resolve("shaders");Files.createDirectories(broken);
        Files.writeString(broken.resolve("final.vsh"),vertex);Files.writeString(broken.resolve("final.fsh"),"#version 460\nvoid main(){gl_FragColor=vec4(missingVerificationSymbol);}\n");
    }
}
