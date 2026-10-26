package net.irisshaders.iris.vulkan.pack;

import java.util.*;

/** Immutable pack selectors; Minecraft resolves these against its real registry and state properties. */
public final class MaterialIdMap {
    public record Selector(String identifier,Map<String,Set<String>> properties,int id) {
        public Selector {var copy=new TreeMap<String,Set<String>>();properties.forEach((k,v)->copy.put(k,Set.copyOf(v)));properties=Map.copyOf(copy);}
        public boolean matches(String name,Map<String,String> state){return identifier.equals(name)&&properties.entrySet().stream().allMatch(e->state.containsKey(e.getKey())&&e.getValue().contains(state.get(e.getKey())));}
    }
    private final List<Selector> selectors;
    private MaterialIdMap(List<Selector> selectors){this.selectors=List.copyOf(selectors);}
    public static MaterialIdMap parse(PackProperties source,String prefix) {
        var result=new ArrayList<Selector>();
        new TreeMap<>(source.all()).forEach((key,value)->{
            if(!key.startsWith(prefix+"."))return;
            int id=Integer.parseInt(key.substring(prefix.length()+1));
            for(String token:value.strip().split("\\s+")) {
                if(token.isEmpty())continue;
                String[] parts=token.split(":");int at=1;String identifier="minecraft:"+parts[0];
                if(parts.length>1&&!parts[1].contains("=")){identifier=parts[0]+":"+parts[1];at=2;}
                if(!identifier.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Invalid material selector "+token);
                var properties=new LinkedHashMap<String,Set<String>>();
                for(;at<parts.length;at++){String[] property=parts[at].split("=",2);if(property.length!=2||property[1].isEmpty())throw new IllegalArgumentException("Invalid state selector "+token);properties.put(property[0],Set.of(property[1].split(",")));}
                result.add(new Selector(identifier,properties,id));
            }
        });
        return new MaterialIdMap(result);
    }
    public int resolve(String identifier,Map<String,String> state){
        int id=-1,specificity=-1;
        for(var selector:selectors)if(selector.properties().size()>=specificity&&selector.matches(identifier,state)){
            if(selector.properties().size()==specificity&&id!=selector.id())throw new IllegalArgumentException("Conflicting material IDs for "+identifier+state);
            id=selector.id();specificity=selector.properties().size();
        }
        return id;
    }
}
