package net.irisshaders.iris.vulkan.render;

import net.irisshaders.iris.vulkan.shader.*;
import java.util.*;
import static org.lwjgl.vulkan.VK13.*;

/** Converts a real reversed-Z attachment into the non-reversed nonlinear depth expected by packs. */
public final class VulkanDepthConverter {
    private VulkanDepthConverter(){}
    public static ShaderVulkanTransformer.Program program(){return new ShaderVulkanTransformer().transformProgram(Map.of(
        ShaderStage.VERTEX,"""
            #version 460
            void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);gl_Position=vec4(p*2.0-1.0,0.0,1.0);}
            """,
        ShaderStage.FRAGMENT,"""
            #version 460
            uniform sampler2D iris_NativeDepth;
            void main(){gl_FragDepth=1.0-texelFetch(iris_NativeDepth,ivec2(gl_FragCoord.xy),0).r;}
            """));}
    public static VulkanPipelineManager.GraphicsState state(int depthFormat){
        return new VulkanPipelineManager.GraphicsState(List.of(),depthFormat,true,true,VK_COMPARE_OP_ALWAYS,
            VK_CULL_MODE_NONE,VK_FRONT_FACE_COUNTER_CLOCKWISE,List.of(),IrisVertexFormats.FULLSCREEN);
    }
    public static void record(VulkanCompositeRenderer renderer,VulkanPipelineManager.Pipeline pipeline,
                              VulkanRenderTargets.Image source,VulkanRenderTargets.Image destination,long sampler){
        if(source.handle==destination.handle||source.width!=destination.width||source.height!=destination.height
            ||source.aspect!=VK_IMAGE_ASPECT_DEPTH_BIT||destination.aspect!=VK_IMAGE_ASPECT_DEPTH_BIT)
            throw new IllegalArgumentException("Depth conversion requires distinct depth images of equal extent");
        renderer.draw(pipeline,List.of(),destination,Map.of("iris_NativeDepth",List.of(new FrameDescriptors.Texture(source,sampler))),null,
            VulkanCompositeRenderer.Draw.fullscreen(),false);
    }
}
