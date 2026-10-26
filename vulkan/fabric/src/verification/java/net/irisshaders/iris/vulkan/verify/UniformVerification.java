package net.irisshaders.iris.vulkan.verify;

import net.irisshaders.iris.vulkan.fabric.*;
import net.irisshaders.iris.vulkan.uniform.UniformSnapshot;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import java.nio.file.*;
import java.util.Arrays;

/** Compares production providers with the live extracted render state across resize/reload/world reentry. */
final class UniformVerification {
    private static Object world;
    private static UniformSnapshot previous;
    private static long samples,historyChecks;
    static void frame(Minecraft mc) {
        if(mc.level==null||!mc.gameRenderer.gameRenderState().shouldRenderLevel){world=null;previous=null;return;}
        if(!IrisVulkanLifecycle.uniforms().ready())return;
        var current=IrisVulkanLifecycle.uniforms().snapshot();
        var camera=mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        require(Arrays.equals(new double[]{camera.pos.x,camera.pos.y,camera.pos.z},current.require("cameraPosition").elements()),"Live camera position");
        require(current.require("viewWidth").scalar()==mc.gameRenderer.mainRenderTarget().getColorTexture().getWidth(0),"Live width after resize");
        require(current.require("viewHeight").scalar()==mc.gameRenderer.mainRenderTarget().getColorTexture().getHeight(0),"Live height after resize");
        require(current.require("worldTime").scalar()==Math.floorMod(mc.level.getDefaultClockTime(),24000),"World clock");
        var projection=matrix(current,"gbufferProjection");
        projection.mul(matrix(current,"gbufferProjectionInverse"));
        require(projection.equals(new Matrix4f(),.0001f),"Projection inverse");
        require(matrix(current,"shadowProjection").mul(matrix(current,"shadowProjectionInverse")).equals(new Matrix4f(),.0001f),"Shadow projection inverse");
        require(matrix(current,"shadowModelView").mul(matrix(current,"shadowModelViewInverse")).equals(new Matrix4f(),.0001f),"Shadow view inverse");
        require(IrisVulkanLifecycle.uniforms().shadowCamera().cullDistance()<=mc.options.renderDistance().get()*16.0,"Shadow distance clamp");
        if(world==mc.level&&previous!=null&&current.frame()==previous.frame()+1) {
            require(Arrays.equals(previous.require("cameraPosition").elements(),current.require("previousCameraPosition").elements()),"Previous camera history");
            require(Arrays.equals(previous.require("gbufferProjection").elements(),current.require("gbufferPreviousProjection").elements()),"Previous projection history");
            historyChecks++;
        }
        world=mc.level;previous=current;samples++;
    }
    static void finish(Minecraft mc)throws Exception {
        require(samples>100&&historyChecks>100,"Insufficient live uniform samples");
        require(LiveDrawState.objectDraws()>0&&LiveDrawState.terrainDraws()>0,"Missing actual object/terrain upload bindings");
        Files.writeString(mc.gameDirectory.toPath().resolve("iris-phase3-uniforms.json"),
            "{\"passed\":true,\"samples\":"+samples+",\"historyChecks\":"+historyChecks+",\"objectDraws\":"+LiveDrawState.objectDraws()+",\"terrainDraws\":"+LiveDrawState.terrainDraws()+",\"benchmarkVisualsVerified\":false}");
    }
    private static Matrix4f matrix(UniformSnapshot snapshot,String name){double[] v=snapshot.require(name).elements();float[] f=new float[16];for(int i=0;i<16;i++)f[i]=(float)v[i];return new Matrix4f().set(f);}
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Phase 3: "+message);}
}
