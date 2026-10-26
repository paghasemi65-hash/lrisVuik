package net.irisshaders.iris.vulkan.fabric;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import net.irisshaders.iris.vulkan.render.IrisVertexFormats;
import java.util.*;
import static org.lwjgl.vulkan.VK10.*;

/** Stable mesh ABI: native shaders ignore the appended fields; pack pipelines consume the same uploaded bytes. */
public final class NativeVertexFormats {
    private NativeVertexFormats() {}
    public static VertexFormat extend(VertexFormat original) {
        var builder=VertexFormat.builder(original.getStepRate());
        for(var e:original.getElements())builder.addAttribute(e.name(),e.offset(),e.format().blockSize(),e.format(),1);
        return builder.addAttribute("mc_Entity",GpuFormat.RGBA32_FLOAT)
            .addAttribute("mc_midTexCoord",GpuFormat.RG32_FLOAT).addAttribute("at_tangent",GpuFormat.RGBA32_FLOAT)
            .addAttribute("at_midBlock",GpuFormat.RGBA32_FLOAT).addAttribute("iris_Normal",GpuFormat.RGB32_FLOAT).build();
    }
    public static IrisVertexFormats.Layout layout(VertexFormat format) {
        var attributes=new ArrayList<IrisVertexFormats.Attribute>();
        for(var e:format.getElements()) {
            int location=switch(e.name()){case "Position"->0;case "Color"->1;case "UV0"->2;case "UV2"->3;
                case "iris_Normal"->4;case "Normal"->format.contains("iris_Normal")?-1:4;case "mc_Entity"->5;case "mc_midTexCoord"->6;case "at_tangent"->7;case "at_midBlock"->8;default->-1;};
            if(location>=0)attributes.add(new IrisVertexFormats.Attribute(e.name(),location,e.name().equals("UV2")?VK_FORMAT_R16G16_SSCALED:VulkanConst.toVk(e.format()),e.offset()));
        }
        return new IrisVertexFormats.Layout(format.getVertexSize(),attributes);
    }
}
