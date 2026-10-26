package net.irisshaders.iris.vulkan.pack;

import net.irisshaders.iris.vulkan.render.VulkanPipelineManager.Blend;
import java.util.*;
import static org.lwjgl.vulkan.VK10.*;

/** OptiFine/Iris blend.<program>[.<buffer>] directives; buffer numbers are target IDs, not output locations. */
public final class PackBlendOverrides {
    private record Key(String program,Integer buffer) {}
    private record Factors(boolean enabled,int source,int destination,int sourceAlpha,int destinationAlpha) {}
    private final Map<Key,Factors> overrides;
    public PackBlendOverrides(PackProperties properties) {
        var parsed=new HashMap<Key,Factors>();
        properties.all().forEach((key,value)->{
            if(!key.startsWith("blend."))return;
            String[] path=key.substring(6).split("\\.");
            if(path.length<1||path.length>2||path[0].isBlank())throw new IllegalArgumentException("Invalid blend key: "+key);
            Integer buffer=null;
            if(path.length==2){String name=PackResourcePlan.canonical(path[1]);if(!name.matches("colortex(?:[0-9]|1[0-5])"))throw new IllegalArgumentException("Invalid blend target: "+key);buffer=Integer.parseInt(name.substring(8));}
            String[] parts=value.strip().split("\\s+");Factors factors;
            if(value.strip().equals("off"))factors=new Factors(false,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO,VK_BLEND_FACTOR_ONE,VK_BLEND_FACTOR_ZERO);
            else {
                if(parts.length!=2&&parts.length!=4)throw new IllegalArgumentException("Expected 2 or 4 blend factors: "+key);
                int src=factor(parts[0]),dst=factor(parts[1]);
                factors=new Factors(true,src,dst,parts.length==4?factor(parts[2]):src,parts.length==4?factor(parts[3]):dst);
            }
            if(parsed.put(new Key(path[0],buffer),factors)!=null)throw new IllegalArgumentException("Duplicate blend alias: "+key);
        });
        overrides=Map.copyOf(parsed);
    }
    public Blend apply(String program,int target,Blend nativeState) {
        var factors=overrides.get(new Key(program,target));if(factors==null)factors=overrides.get(new Key(program,null));
        if(factors==null)return nativeState;
        // Blend directives change factors and enablement, not native attachment write masks.
        return new Blend(factors.enabled,factors.source,factors.destination,factors.sourceAlpha,factors.destinationAlpha,
            VK_BLEND_OP_ADD,VK_BLEND_OP_ADD,nativeState.writeMask());
    }
    private static int factor(String value){return switch(value){
        case "ZERO"->VK_BLEND_FACTOR_ZERO;case "ONE"->VK_BLEND_FACTOR_ONE;
        case "SRC_COLOR"->VK_BLEND_FACTOR_SRC_COLOR;case "ONE_MINUS_SRC_COLOR"->VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
        case "DST_COLOR"->VK_BLEND_FACTOR_DST_COLOR;case "ONE_MINUS_DST_COLOR"->VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
        case "SRC_ALPHA"->VK_BLEND_FACTOR_SRC_ALPHA;case "ONE_MINUS_SRC_ALPHA"->VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        case "DST_ALPHA"->VK_BLEND_FACTOR_DST_ALPHA;case "ONE_MINUS_DST_ALPHA"->VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
        case "SRC_ALPHA_SATURATE"->VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
        default->throw new IllegalArgumentException("Unsupported blend factor "+value);
    };}
}
