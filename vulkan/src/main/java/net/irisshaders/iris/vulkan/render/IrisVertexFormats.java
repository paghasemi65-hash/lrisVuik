package net.irisshaders.iris.vulkan.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.*;
import static org.lwjgl.vulkan.VK10.*;

/** Explicit expanded ABI. Terrain/entity encoders must actually write these values before using it. */
public final class IrisVertexFormats {
    public record Attribute(String name,int location,int format,int offset) {}
    public record Layout(int stride,List<Attribute> attributes) {
        public Layout {
            attributes=List.copyOf(attributes);
            if(stride<0||!attributes.isEmpty()&&stride==0)throw new IllegalArgumentException("Vertex binding stride");
            var locations=new HashSet<Integer>();
            for(var attribute:attributes)if(attribute.location()<0||attribute.offset()<0||attribute.offset()>=stride||!locations.add(attribute.location()))throw new IllegalArgumentException("Invalid/duplicate vertex attribute: "+attribute.name());
        }
        public VkPipelineVertexInputStateCreateInfo nativeInfo(MemoryStack s) {
            var result=VkPipelineVertexInputStateCreateInfo.calloc(s).sType$Default();
            if(attributes.isEmpty()) return result;
            var bindings=VkVertexInputBindingDescription.calloc(1,s).binding(0).stride(stride).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            var attrs=VkVertexInputAttributeDescription.calloc(attributes.size(),s);
            for(int i=0;i<attributes.size();i++){var a=attributes.get(i);attrs.get(i).binding(0).location(a.location).format(a.format).offset(a.offset);}
            return result.pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attrs);
        }
    }
    public static final Layout FULLSCREEN = new Layout(0,List.of());
    public static final Layout EXTENDED = new Layout(144,List.of(
        new Attribute("iris_Position",0,VK_FORMAT_R32G32B32A32_SFLOAT,0),
        new Attribute("iris_Color",1,VK_FORMAT_R32G32B32A32_SFLOAT,16),
        new Attribute("iris_UV0",2,VK_FORMAT_R32G32B32A32_SFLOAT,32),
        new Attribute("iris_UV1",3,VK_FORMAT_R32G32B32A32_SFLOAT,48),
        new Attribute("iris_Normal",4,VK_FORMAT_R32G32B32_SFLOAT,64),
        new Attribute("mc_Entity",5,VK_FORMAT_R32G32B32A32_SFLOAT,80),
        new Attribute("mc_midTexCoord",6,VK_FORMAT_R32G32_SFLOAT,96),
        new Attribute("at_tangent",7,VK_FORMAT_R32G32B32A32_SFLOAT,112),
        new Attribute("at_midBlock",8,VK_FORMAT_R32G32B32A32_SFLOAT,128)));
    private IrisVertexFormats() {}
}
