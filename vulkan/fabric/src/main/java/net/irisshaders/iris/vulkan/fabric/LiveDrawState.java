package net.irisshaders.iris.vulkan.fabric;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.*;
import net.irisshaders.iris.vulkan.shader.DepthConvention;
import net.irisshaders.iris.vulkan.uniform.*;
import net.minecraft.client.renderer.DynamicGpuData;
import org.joml.*;
import java.util.*;

/** CPU values captured from actual native uploads, indexed by the exact buffer slices bound by a draw. */
public final class LiveDrawState {
    public record Texture(GpuTextureView view,GpuSampler sampler){}
    public record Draw(Map<String,UniformValue> uniforms,Map<String,Texture> textures) {
        public Draw {uniforms=Map.copyOf(uniforms);textures=Map.copyOf(textures);}
    }
    private static final Map<GpuBufferSlice,Map<String,UniformValue>> uploaded=new HashMap<>();
    private static Draw latest;
    private static long capturedDraws,objectDraws,terrainDraws,chunkDraws;
    private static boolean world;
    public static void beginWorld(){world=true;}
    public static boolean capturingWorld(){return world;}
    public static void endWorld(){world=false;}
    public static void reset(){uploaded.clear();latest=null;world=false;}
    public static long capturedDraws(){return capturedDraws;}
    public static long objectDraws(){return objectDraws;}
    public static long terrainDraws(){return terrainDraws;}
    public static long chunkDraws(){return chunkDraws;}
    public static Draw latest(){if(latest==null)throw new IllegalStateException("No captured world draw");return latest;}
    public static void transform(GpuBufferSlice slice,DynamicGpuData.Transform transform) {
        if(!transform.modelView().isFinite()||!transform.textureMatrix().isFinite()){uploaded.remove(slice);return;}
        var offset=transform.modelOffset();var color=transform.colorModulator();
        // Native shaders add ModelOffset to vertex position before applying ModelViewMat.
        var modelView=new Matrix4f(transform.modelView()).translate(offset);
        uploaded.put(slice,Map.of("iris_ModelViewMatrix",matrix(modelView),
            "iris_TextureMatrix0",matrix(transform.textureMatrix()),"iris_ColorModulator",UniformValue.vector(color.x(),color.y(),color.z(),color.w()),
            "modelOffset",UniformValue.vector(offset.x(),offset.y(),offset.z())));
    }
    public static void terrain(GpuBufferSlice slice,Matrix4fc matrix,int width,int height) {
        if(!matrix.isFinite()){uploaded.remove(slice);return;}
        uploaded.put(slice,Map.of("iris_ModelViewMatrix",matrix(matrix),"atlasSize",UniformValue.vector(width,height).cast("ivec2")));
    }
    public static void projection(GpuBufferSlice slice,Matrix4fc projection) {
        if(!projection.isFinite()){uploaded.remove(slice);return;}
        uploaded.put(slice,Map.of("iris_ProjectionMatrix",UniformValue.floats("mat4",DepthConvention.reversedProjectionToOpenGl(projection.get(new float[16])))));
    }
    public static void section(GpuBufferSlice slice,DynamicGpuData.ChunkSectionInfo section) {
        uploaded.put(slice,Map.of("chunkOffset",UniformValue.vector(section.x(),section.y(),section.z()),"chunkFade",UniformValue.scalar(section.visibility())));
    }
    public static void draw(Map<String,GpuBufferSlice> buffers,Map<String,Texture> textures,Set<String> declared) {
        if(!world)return;
        var values=new HashMap<String,UniformValue>();
        // FrontendRenderPass retains old bindings across pipeline changes. Only declared bindings are live.
        for(var entry:buffers.entrySet())if(declared.contains(entry.getKey())){var supplied=uploaded.get(entry.getValue());if(supplied!=null)values.putAll(supplied);}
        if(values.containsKey("iris_ModelViewMatrix")) {
            if(values.containsKey("atlasSize"))terrainDraws++;else objectDraws++;
            if(values.containsKey("chunkOffset"))chunkDraws++;
            var boundTextures=new HashMap<String,Texture>();textures.forEach((name,texture)->{if(declared.contains(name))boundTextures.put(name,texture);});
            latest=new Draw(values,boundTextures);capturedDraws++;
        }
    }
    private static UniformValue matrix(Matrix4fc matrix){return UniformValue.floats("mat4",matrix.get(new float[16]));}
}
