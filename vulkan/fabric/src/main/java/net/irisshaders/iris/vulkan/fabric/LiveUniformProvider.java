package net.irisshaders.iris.vulkan.fabric;

import net.irisshaders.iris.vulkan.shader.DepthConvention;
import net.irisshaders.iris.vulkan.render.ShadowCamera;
import net.irisshaders.iris.vulkan.uniform.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.*;
import java.lang.Math;
import java.util.*;

/** Samples the exact world projection at its native upload boundary, including bobbing and portal effects. */
public final class LiveUniformProvider {
    private ClientLevel world;
    private UniformSnapshot snapshot;
    private long frame, lastNanos;
    private double elapsed;
    private boolean ready;
    private ShadowCamera shadowCamera;
    public ShadowCamera shadowCamera(){if(shadowCamera==null)throw new IllegalStateException("No captured shadow camera");return shadowCamera;}
    private net.irisshaders.iris.vulkan.pack.ShaderPackLoader.Loaded capturedPack;
    public boolean ready(){return ready;}
    public net.irisshaders.iris.vulkan.pack.ShaderPackLoader.Loaded capturedPack(){return capturedPack;}
    private HalfLifeSmoother wetness=new HalfLifeSmoother(),blockBrightness=new HalfLifeSmoother(),skyBrightness=new HalfLifeSmoother();

    public UniformSnapshot snapshot() {
        if(snapshot==null)throw new IllegalStateException("World uniforms have not been captured");
        return snapshot;
    }
    public void reset() {
        world=null;snapshot=null;lastNanos=0;frame=0;elapsed=0;ready=false;capturedPack=null;shadowCamera=null;
        wetness=new HalfLifeSmoother();blockBrightness=new HalfLifeSmoother();skyBrightness=new HalfLifeSmoother();
    }
    public void capture(GameRenderer renderer,Matrix4fc nativeProjection) {
        ready=false;
        var mc=Minecraft.getInstance();
        if(mc.level==null||!renderer.gameRenderState().shouldRenderLevel)return;
        if(world!=mc.level){reset();world=mc.level;}
        if(!renderer.gameRenderState().levelRenderState.cameraRenderState.initialized||!nativeProjection.isFinite())return;
        long now=System.nanoTime();double delta=lastNanos==0?0:(now-lastNanos)*1e-9;lastNanos=now;elapsed+=delta;
        var previous=snapshot;
        var state=renderer.gameRenderState();var level=state.levelRenderState;var camera=level.cameraRenderState;
        var cameraEntity=renderer.mainCamera().entity();
        if(cameraEntity==null)throw new IllegalStateException("World camera has no entity");
        var values=new LinkedHashMap<String,UniformValue>();
        put(values,"cameraPosition",UniformValue.vector(camera.pos.x,camera.pos.y,camera.pos.z));
        put(values,"eyeAltitude",UniformValue.scalar(camera.pos.y));
        var eye=cameraEntity.getEyePosition(camera.cameraEntityPartialTicks);
        put(values,"eyePosition",UniformValue.vector(eye.x,eye.y,eye.z));
        put(values,"relativeEyePosition",UniformValue.vector(eye.x-camera.pos.x,eye.y-camera.pos.y,eye.z-camera.pos.z));
        var look=cameraEntity.getLookAngle();put(values,"playerLookVector",UniformValue.vector(look.x,look.y,look.z));
        float[] projection=DepthConvention.reversedProjectionToOpenGl(nativeProjection.get(new float[16]));
        put(values,"gbufferProjection",UniformValue.floats("mat4",projection));
        put(values,"gbufferProjectionInverse",matrix(new Matrix4f().set(projection).invert()));
        put(values,"gbufferModelView",matrix(camera.viewRotationMatrix));
        put(values,"gbufferModelViewInverse",matrix(new Matrix4f(camera.viewRotationMatrix).invert()));
        previous(values,previous,"cameraPosition","previousCameraPosition");
        previous(values,previous,"gbufferModelView","gbufferPreviousModelView");
        previous(values,previous,"gbufferProjection","gbufferPreviousProjection");
        splitPosition(values,"cameraPosition","cameraPositionInt","cameraPositionFract");
        splitPosition(values,"previousCameraPosition","previousCameraPositionInt","previousCameraPositionFract");
        put(values,"frameCounter",UniformValue.integer((int)(frame%720720)));
        put(values,"frameTime",UniformValue.scalar(delta));put(values,"frameTimeCounter",UniformValue.scalar(elapsed%3600));
        long clock=world.getDefaultClockTime();
        put(values,"worldTime",UniformValue.integer((int)Math.floorMod(clock,24000)));
        put(values,"worldDay",UniformValue.integer((int)Math.floorDiv(clock,24000)));
        var sky=level.skyRenderState;
        var probe=renderer.mainCamera().attributeProbe();float partial=camera.cameraEntityPartialTicks;
        float sunDegrees=probe.getValue(EnvironmentAttributes.SUN_ANGLE,partial),moonDegrees=probe.getValue(EnvironmentAttributes.MOON_ANGLE,partial);
        double sunAngle=positiveUnit((sunDegrees+90)/360.0),moonAngle=positiveUnit((moonDegrees+90)/360.0);
        put(values,"sunAngle",UniformValue.scalar(sunAngle));
        put(values,"shadowAngle",UniformValue.scalar(sunAngle<=.5?sunAngle:moonAngle));
        float path=(float)packConstant("sunPathRotation",0);
        var celestial=new Matrix4f(camera.viewRotationMatrix).rotateY((float)Math.toRadians(-90)).rotateZ((float)Math.toRadians(path));
        put(values,"sunPosition",skyPosition(celestial,sunDegrees));
        put(values,"moonPosition",skyPosition(celestial,moonDegrees));
        var packs=LiveShaderPacks.active();
        var constants=packs==null||packs.loaded()==null?Map.<String,String>of():packs.loaded().resources().constants();
        var light=new Matrix4f().rotateY((float)Math.toRadians(-90)).rotateZ((float)Math.toRadians(path))
            .rotateX((float)Math.toRadians(sunAngle<=.5?sunDegrees:moonDegrees)).transformDirection(new Vector3f(0,1,0));
        shadowCamera=new ShadowCamera(ShadowCamera.Settings.from(constants),new Vector3d(camera.pos.x,camera.pos.y,camera.pos.z),light,
            mc.options.renderDistance().get()*16.0);
        put(values,"shadowModelView",matrix(shadowCamera.view()));
        put(values,"shadowModelViewInverse",matrix(shadowCamera.view().invert()));
        put(values,"shadowProjection",matrix(shadowCamera.projection()));
        put(values,"shadowProjectionInverse",matrix(shadowCamera.projection().invert()));
        put(values,"shadowLightPosition",vector(new Matrix4f(camera.viewRotationMatrix).transformDirection(new Vector3f(light).mul(100))));
        put(values,"upPosition",vector(new Matrix4f(camera.viewRotationMatrix).transformDirection(new Vector3f(0,100,0))));
        // SkyRenderState is deliberately empty when vanilla skips the sky; the environment probe remains authoritative.
        put(values,"skyColor",vector(probe.getValue(EnvironmentAttributes.SKY_COLOR,partial)));
        put(values,"moonPhase",UniformValue.integer(probe.getValue(EnvironmentAttributes.MOON_PHASE,partial).index()));
        put(values,"endFlashIntensity",UniformValue.scalar(sky.endFlashIntensity));
        put(values,"cloudHeight",UniformValue.scalar(probe.getValue(EnvironmentAttributes.CLOUD_HEIGHT,partial)));
        double rain=world.getRainLevel(level.worldPartialTicks);
        put(values,"rainStrength",UniformValue.scalar(rain));
        put(values,"wetness",UniformValue.scalar(wetness.sample(frame,delta,rain,packConstant("wetnessHalflife",600)*.1,packConstant("drynessHalflife",200)*.1)));
        var eyeBlock=BlockPos.containing(eye);
        int block=world.getBrightness(LightLayer.BLOCK,eyeBlock)*16,skyLight=world.getBrightness(LightLayer.SKY,eyeBlock)*16;
        put(values,"eyeBrightness",UniformValue.vector(block,skyLight).cast("ivec2"));
        double brightnessHalf=packConstant("eyeBrightnessHalflife",10)*.1;
        put(values,"eyeBrightnessSmooth",UniformValue.vector(blockBrightness.sample(frame,delta,block,brightnessHalf,brightnessHalf),skyBrightness.sample(frame,delta,skyLight,brightnessHalf,brightnessHalf)).cast("ivec2"));
        put(values,"isEyeInWater",UniformValue.integer(switch(camera.fogType){case WATER->1;case LAVA->2;case POWDER_SNOW->3;default->0;}));
        var living=cameraEntity instanceof LivingEntity e?e:null;
        var blindness=living==null?null:living.getEffect(MobEffects.BLINDNESS);
        put(values,"blindness",UniformValue.scalar(blindness==null?0:blindness.isInfiniteDuration()?1:Math.min(blindness.getDuration()/20.0,1)));
        put(values,"darknessFactor",UniformValue.scalar(living==null?0:living.getEffectBlendFactor(MobEffects.DARKNESS,camera.cameraEntityPartialTicks)));
        put(values,"darknessLightFactor",UniformValue.scalar(state.lightmapRenderState.darknessEffectScale));
        put(values,"nightVision",UniformValue.scalar(state.lightmapRenderState.nightVisionEffectIntensity));
        put(values,"screenBrightness",UniformValue.scalar(mc.options.gamma().get()));
        put(values,"playerMood",UniformValue.scalar(mc.player==null?0:mc.player.getCurrentMood()));
        put(values,"isElytraFlying",UniformValue.bool(living!=null&&living.isFallFlying()));
        put(values,"is_invisible",UniformValue.bool(cameraEntity.isInvisible()));
        if(living!=null){held(values,living.getMainHandItem(),"");held(values,living.getOffhandItem(),"2");}
        var main=renderer.mainRenderTarget();int width=main.getColorTexture().getWidth(0),height=main.getColorTexture().getHeight(0);
        put(values,"viewWidth",UniformValue.scalar(width));put(values,"viewHeight",UniformValue.scalar(height));put(values,"aspectRatio",UniformValue.scalar((double)width/height));
        put(values,"near",UniformValue.scalar(net.minecraft.client.Camera.PROJECTION_Z_NEAR));put(values,"far",UniformValue.scalar(camera.depthFar));
        var fog=camera.fogData;put(values,"fogColor",UniformValue.vector(fog.color.x,fog.color.y,fog.color.z));
        put(values,"iris_FogColor",UniformValue.vector(fog.color.x,fog.color.y,fog.color.z,fog.color.w));
        boolean environmental=fog.environmentalEnd<fog.renderDistanceEnd;
        put(values,"fogStart",UniformValue.scalar(environmental?fog.environmentalStart:fog.renderDistanceStart));
        put(values,"fogEnd",UniformValue.scalar(environmental?fog.environmentalEnd:fog.renderDistanceEnd));
        // RenderPearl's fog is linear; legacy density is zero for linear fog, shape selects the limiting distance.
        put(values,"fogDensity",UniformValue.scalar(0));put(values,"fogShape",UniformValue.integer(environmental?0:1));
        put(values,"heavyFog",UniformValue.bool(environmental));
        var biomes=world.registryAccess().lookupOrThrow(Registries.BIOME);
        put(values,"biome",UniformValue.integer(biomes.getId(world.getBiome(camera.blockPos).value())));
        put(values,"biome_precipitation",UniformValue.integer(switch(world.getPrecipitationAt(camera.blockPos)){case NONE->0;case RAIN->1;case SNOW->2;}));
        snapshot=new UniformSnapshot(frame++,delta,values);
        capturedPack=LiveShaderPacks.active()==null?null:LiveShaderPacks.active().loaded();
        ready=true;
    }
    public Map<String,UniformValue> biomeConstants() {
        if(world==null)throw new IllegalStateException("No client world");
        var registry=world.registryAccess().lookupOrThrow(Registries.BIOME);var result=new HashMap<String,UniformValue>();
        for(var entry:registry.entrySet()) {
            var id=entry.getKey().identifier();
            String key="BIOME_"+(id.getNamespace().equals("minecraft")?"":id.getNamespace()+"_")+id.getPath();
            result.put(key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]","_"),UniformValue.integer(registry.getId(entry.getValue())));
        }
        return Map.copyOf(result);
    }
    private static void held(Map<String,UniformValue> values,ItemStack stack,String suffix) {
        var packs=LiveShaderPacks.active();var mappings=packs==null||packs.loaded()==null?Map.<String,Integer>of():packs.loaded().itemIds();
        int itemId=stack.isEmpty()?-1:mappings.getOrDefault(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),-1);
        put(values,"heldItemId"+suffix,UniformValue.integer(itemId));
        int emission=stack.getItem() instanceof BlockItem item?item.getBlock().defaultBlockState().getLightEmission():0;
        put(values,"heldBlockLightValue"+suffix,UniformValue.integer(emission));
    }
    private static double packConstant(String name,double fallback) {
        var packs=LiveShaderPacks.active();if(packs==null||packs.loaded()==null)return fallback;
        String value=packs.loaded().resources().constants().get(name);
        return value==null?fallback:net.irisshaders.iris.vulkan.pack.PackExpression.evaluate(value,packs.loaded().resources().constants());
    }
    private static void put(Map<String,UniformValue> values,String name,UniformValue value){values.put(name,value);}
    private static UniformValue matrix(Matrix4fc matrix){return UniformValue.floats("mat4",matrix.get(new float[16]));}
    private static UniformValue vector(Vector3fc value){return UniformValue.vector(value.x(),value.y(),value.z());}
    private static UniformValue skyPosition(Matrix4fc rotation,float angle){return vector(new Matrix4f(rotation).rotateX((float)Math.toRadians(angle)).transformPosition(new Vector3f(0,100,0)));}
    private static double positiveUnit(double value){return value-Math.floor(value);}
    private static void previous(Map<String,UniformValue> values,UniformSnapshot previous,String current,String name){put(values,name,previous==null?values.get(current):previous.require(current));}
    private static void splitPosition(Map<String,UniformValue> values,String source,String integer,String fraction){
        double[] pos=values.get(source).elements(),whole=new double[3],part=new double[3];
        for(int i=0;i<3;i++){whole[i]=Math.floor(pos[i]);part[i]=pos[i]-whole[i];}
        put(values,integer,new UniformValue("ivec3",whole));put(values,fraction,new UniformValue("vec3",part));
    }
}
