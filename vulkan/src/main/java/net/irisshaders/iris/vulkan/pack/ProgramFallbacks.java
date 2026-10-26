package net.irisshaders.iris.vulkan.pack;
import java.util.*;
public final class ProgramFallbacks {
    public static final Map<String,String> PARENTS;
    static {
        var p=new LinkedHashMap<String,String>();
        edge(p,"gbuffers_basic","line","textured","skybasic");
        edge(p,"gbuffers_textured","textured_lit","skytextured","clouds","beaconbeam","armor_glint","spidereyes");
        edge(p,"gbuffers_textured_lit","terrain","item","entities","particles","hand","weather");
        edge(p,"gbuffers_terrain","terrain_solid","terrain_cutout","damagedblock","block","water");
        edge(p,"gbuffers_block","block_translucent");edge(p,"gbuffers_entities","entities_translucent","entities_glowing","lightning");
        edge(p,"gbuffers_particles","particles_translucent");edge(p,"gbuffers_hand","hand_water");
        for(String s:List.of("solid","cutout","water","entities","block"))p.put("shadow_"+s,"shadow");
        p.put("shadow_lightning","shadow_entities");p.put("dh_water","dh_terrain");p.put("dh_generic","dh_terrain");
        PARENTS=Collections.unmodifiableMap(p);
    }
    private static void edge(Map<String,String> p,String parent,String... children){for(String child:children)p.put("gbuffers_"+child,parent);}
    public static Optional<String> resolve(String name,Set<String> available,Set<String> disabled) {
        for(String current=name;current!=null;current=PARENTS.get(current))if(available.contains(current)&&!disabled.contains(current))return Optional.of(current);
        return Optional.empty();
    }
    public static String dimensionDirectory(net.irisshaders.iris.vulkan.shader.ShaderPackSource source,PackProperties dimensions,String dimension) {
        if(!dimensions.all().isEmpty()) {
            String wildcard=null;
            for(var e:dimensions.all().entrySet())if(e.getKey().startsWith("dimension."))for(String id:e.getValue().trim().split("\\s+")) {
                String directory=e.getKey().substring(10);
                if(id.equals(dimension)){if(!source.directory(directory))throw new IllegalArgumentException("Missing dimension directory "+directory);return directory;}
                if(id.equals("*" )||id.equals("*:*"))wildcard=directory;
            }
            if(wildcard!=null){if(!source.directory(wildcard))throw new IllegalArgumentException("Missing dimension directory "+wildcard);return wildcard;}
            return "";
        }
        String directory=switch(dimension){case "minecraft:the_nether"->"world-1";case "minecraft:the_end"->"world1";default->"world0";};
        return source.directory(directory)?directory:"";
    }
}
