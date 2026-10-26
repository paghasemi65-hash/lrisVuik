package net.irisshaders.iris.vulkan.pack;
import java.util.*;

/** Shaderpack item IDs are independent of Minecraft's registry numbering. */
public final class ItemIdMap {
    private ItemIdMap(){}
    public static Map<String,Integer> parse(PackProperties properties) {
        var ids=new LinkedHashMap<String,Integer>();
        properties.all().forEach((key,value)->{
            if(!key.startsWith("item."))return;
            int id=Integer.parseInt(key.substring(5));
            for(String token:value.trim().split("\\s+")) {
                if(token.isEmpty())continue;
                String name=token.contains(":")?token:"minecraft:"+token;
                if(!name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Unsupported item mapping selector "+token);
                ids.put(name,id);
            }
        });
        return Map.copyOf(ids);
    }
}
