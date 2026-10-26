package net.irisshaders.iris.vulkan.verify;

import com.mojang.blaze3d.platform.InputConstants;
import net.irisshaders.iris.vulkan.fabric.*;
import net.irisshaders.iris.vulkan.pack.*;
import net.irisshaders.iris.vulkan.render.PackGpuResources;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.*;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.input.KeyEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Drives real registered key mappings, UI callbacks, failed installations and world transitions. */
final class ControlsVerification {
    private static int stage,frames,oitFrames;
    private static long installations;
    private static ShaderPackLoader.Loaded retainedPack;
    private static PackGpuResources retainedResources;
    private static CameraType camera;
    private static boolean fullscreen,improvedTransparency;
    private static CompletableFuture<Void> reload;
    private static final String FOLDER="iris-verification-folder",GPU_FAILURE="iris-verification-gpu-failure";
    static boolean frame(Minecraft mc)throws Exception {
        frames++;var packs=Objects.requireNonNull(LiveShaderPacks.active());
        var state=mc.gameRenderer.gameRenderState();
        boolean expectedOit=state.optionsRenderState.improvedTransparency&&!state.levelRenderState.renderWireframeTerrain;
        require(mc.gameRenderer.useImprovedTransparency()==expectedOit,"Native OIT changed without world routing");
        if(expectedOit)oitFrames++;
        switch(stage) {
            case 0 -> {
                require(Arrays.asList(mc.options.keyMappings).containsAll(List.of(ShaderControls.TOGGLE,ShaderControls.RELOAD)),"Native key registration");
                camera=mc.options.getCameraType();mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                fullscreen=mc.options.fullscreen().get();
                improvedTransparency=mc.options.improvedTransparency().get();mc.options.improvedTransparency().set(true);
                packs.select(new ShaderPackLoader.Selection(FOLDER,"high",Map.of()));next();
            }
            case 1 -> {
                if(!resident(packs)||frames<30||mc.gui.screen()!=null)return false;
                installations=packs.installations();click(ShaderControls.TOGGLE);next();
            }
            case 2 -> {
                if(!packs.selection().pack().isEmpty()||packs.resources()!=null)return false;
                var saved=new PackConfiguration(mc.gameDirectory.toPath()).read();
                require(!saved.enabled()&&saved.remembered().pack().equals(FOLDER),"Off persistence lost remembered pack");
                click(ShaderControls.TOGGLE);next();
            }
            case 3 -> {
                if(!resident(packs)||packs.installations()<=installations)return false;
                installations=packs.installations();retainedResources=packs.resources();click(ShaderControls.RELOAD);next();
            }
            case 4 -> {
                if(!resident(packs)||packs.installations()<=installations)return false;
                require(retainedResources!=packs.resources(),"R did not replace GPU resources");
                retainedResources=packs.resources();retainedPack=packs.loaded();
                Path shaders=mc.gameDirectory.toPath().resolve("shaderpacks/"+GPU_FAILURE+"/shaders");Files.createDirectories(shaders);
                Files.writeString(shaders.resolve("gbuffers_basic.vsh"),"#version 460\nvoid main(){gl_Position=vec4(0,0,0,1);}");
                Files.writeString(shaders.resolve("gbuffers_basic.fsh"),"#version 460\nvoid main(){gl_FragColor=vec4(1);}");
                // CPU compilation succeeds; resolving the real viewport-sized allocation must reject subpixel extents.
                Files.writeString(shaders.resolve("shaders.properties"),"size.buffer.colortex0=0.000001 0.000001\n");
                packs.select(new ShaderPackLoader.Selection(GPU_FAILURE,"",Map.of()));next();
            }
            case 5 -> {
                if(packs.diagnostics().isEmpty())return false;
                require(packs.diagnostics().getFirst().display().contains("Invalid image extent"),"Expected actual GPU preparation failure");
                retained(packs,mc);
                packs.select(new ShaderPackLoader.Selection("iris-verification-broken","",Map.of()));next();
            }
            case 6 -> {
                if(packs.diagnostics().isEmpty())return false;
                retained(packs,mc);packs.select(new ShaderPackLoader.Selection(FOLDER,"high",Map.of()));next();
            }
            case 7 -> {
                if(frames<=10){click(ShaderControls.RELOAD);return false;}
                click(ShaderControls.TOGGLE);next();
            }
            case 8 -> {
                if(frames<30)return false;
                require(packs.selection().pack().isEmpty()&&packs.resources()==null&&packs.loaded()==null,"Stale compilation installed after Off");
                click(ShaderControls.TOGGLE);next();
            }
            case 9 -> {
                if(!resident(packs)||frames<30)return false;
                mc.gui.setScreen(new VideoSettingsScreen(null,mc,mc.options));
                press(find(mc.gui.screen(),"Shaderpacks"));
                require(mc.gui.screen() instanceof ShaderPackScreen,"Video Settings entry");
                var before=packs.selection();press(find(mc.gui.screen(),"Off"));
                require(packs.selection().equals(before),"Draft selection applied before Apply");
                press(find(mc.gui.screen(),"Apply"));
                require(packs.selection().pack().isEmpty(),"Apply did not queue selection");
                press(find(mc.gui.screen(),"Done"));
                require(mc.gui.screen() instanceof VideoSettingsScreen,"Done did not return to Video Settings");
                mc.gui.setScreen(null);next();
            }
            case 10 -> {
                if(frames<5||packs.resources()!=null)return false;
                click(ShaderControls.TOGGLE);next();
            }
            case 11 -> {
                if(!resident(packs)||frames<30)return false;
                dimension(mc,"minecraft:the_nether",90);next();
            }
            case 12 -> {
                if(!dimensionReady(mc,packs,"minecraft:the_nether")||frames<60)return false;
                dimension(mc,"minecraft:the_end",90);next();
            }
            case 13 -> {
                if(!dimensionReady(mc,packs,"minecraft:the_end")||frames<60)return false;
                dimension(mc,"minecraft:overworld",4);next();
            }
            case 14 -> {
                if(!dimensionReady(mc,packs,"minecraft:overworld")||frames<60)return false;
                mc.options.fullscreen().set(!fullscreen);next();
            }
            case 15 -> {
                if(frames<60||!resident(packs))return false;
                mc.options.fullscreen().set(fullscreen);next();
            }
            case 16 -> {
                if(frames<60||!resident(packs))return false;
                installations=packs.installations();reload=mc.reloadResourcePacks();next();
            }
            case 17 -> {
                if(!reload.isDone()||!resident(packs)||packs.installations()<=installations||frames<60)return false;
                reload.join();require(oitFrames>0,"Native OIT was never active");
                mc.options.setCameraType(camera);mc.options.improvedTransparency().set(improvedTransparency);
                packs.select(new ShaderPackLoader.Selection("","",Map.of()));next();
            }
            case 18 -> {
                if(frames<30||packs.resources()!=null)return false;
                Files.writeString(mc.gameDirectory.toPath().resolve("iris-phase5-controls.json"),
                    "{\"passed\":true,\"keyToggleReload\":true,\"rapidReloadOff\":true,\"videoSettingsApplyDone\":true,\"cpuFailureRetained\":true,\"gpuFailureRetained\":true,\"dimensions\":3,\"fullscreenRoundTrip\":true,\"resourceReload\":true,\"nativeOitPreserved\":true,\"worldEffectsRendered\":false}");
                return true;
            }
            default -> throw new IllegalStateException("Controls stage "+stage);
        }
        return false;
    }
    private static void retained(LiveShaderPacks packs,Minecraft mc)throws Exception {
        require(packs.loaded()==retainedPack&&packs.resources()==retainedResources&&packs.uniformsReady(),"Failed replacement discarded working state");
        require(new PackConfiguration(mc.gameDirectory.toPath()).read().selection().pack().equals(FOLDER),"Failed replacement overwrote persisted working selection");
    }
    private static boolean resident(LiveShaderPacks p){return p.loaded()!=null&&p.loaded().name().equals(FOLDER)&&p.resources()!=null&&p.uniformsReady();}
    private static void click(KeyMapping key){KeyMapping.click(InputConstants.getKey(key.saveString()));}
    private static void dimension(Minecraft mc,String dimension,int y){var server=Objects.requireNonNull(mc.getSingleplayerServer());server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"execute in "+dimension+" run tp IrisVerification 0 "+y+" 0"));}
    private static boolean dimensionReady(Minecraft mc,LiveShaderPacks p,String dimension){return mc.level!=null&&mc.gui.screen()==null&&mc.level.dimension().identifier().toString().equals(dimension)&&resident(p)&&p.loaded().dimension().equals(dimension);}
    private static Button find(GuiEventListener root,String text) {
        if(root instanceof Button b&&b.getMessage().getString().equals(text))return b;
        if(root instanceof ContainerEventHandler container)for(var child:container.children()){var found=find(child,text);if(found!=null)return found;}
        return null;
    }
    private static void press(Button button){require(button!=null,"Missing GUI button");button.onPress(new KeyEvent(InputConstants.KEY_RETURN,0,0));}
    private static void next(){org.slf4j.LoggerFactory.getLogger("IrisVerification").info("Phase 5 controls stage {} complete",stage);stage++;frames=0;}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Phase 5 controls stage "+stage+": "+message);}
}
