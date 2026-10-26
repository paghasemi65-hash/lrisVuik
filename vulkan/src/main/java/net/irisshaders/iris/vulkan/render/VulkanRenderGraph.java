package net.irisshaders.iris.vulkan.render;

import java.util.*;

/** Ordered pass dependency graph. Native operations are supplied by the command recorders, not executed while planning. */
public final class VulkanRenderGraph {
    public enum Phase { FRAME_START, PREPARE, SHADOW, SHADOWCOMP, GBUFFERS_OPAQUE, DEPTH_BEFORE_TRANSLUCENT, DEFERRED, GBUFFERS_TRANSLUCENT, DEPTH_BEFORE_HAND, HAND, COMPOSITE, FINAL }
    public record Pass(String name,Phase phase,Set<String> reads,Set<String> writes,Set<String> after,Runnable record) {
        public Pass {
            Objects.requireNonNull(name);Objects.requireNonNull(phase);Objects.requireNonNull(record);
            reads=Set.copyOf(reads);writes=Set.copyOf(writes);after=Set.copyOf(after);
            if(name.isBlank()||after.contains(name))throw new IllegalArgumentException("Invalid pass name/dependency");
        }
    }
    private final List<Pass> ordered;
    public VulkanRenderGraph(List<Pass> passes,Set<String> importedResources) {
        var byName=new LinkedHashMap<String,Pass>();
        for(var p:passes)if(byName.putIfAbsent(p.name,p)!=null)throw new IllegalArgumentException("Duplicate pass "+p.name);
        var dependencies=new LinkedHashMap<String,Set<String>>();
        var writers=new HashMap<String,String>();var readers=new HashMap<String,Set<String>>();
        Phase previous=null;
        for(var pass:passes){
            if(previous!=null&&pass.phase.ordinal()<previous.ordinal())throw new IllegalArgumentException("Pass phase order regresses at "+pass.name);
            previous=pass.phase;
            var deps=new LinkedHashSet<>(pass.after);dependencies.put(pass.name,deps);
            for(String dependency:deps)if(!byName.containsKey(dependency))throw new IllegalArgumentException("Missing pass "+dependency);
            for(String resource:pass.reads){
                var writer=writers.get(resource);
                if(writer==null&&!importedResources.contains(resource))throw new IllegalArgumentException(pass.name+" reads uninitialized "+resource);
                if(writer!=null)deps.add(writer);
                readers.computeIfAbsent(resource,k->new LinkedHashSet<>()).add(pass.name);
            }
            for(String resource:pass.writes){
                var writer=writers.put(resource,pass.name);if(writer!=null)deps.add(writer);
                deps.addAll(readers.getOrDefault(resource,Set.of()));deps.remove(pass.name);readers.remove(resource);
            }
            // Preserve the shaderpack's ordered semantics even for passes without resource edges.
            if(dependencies.size()>1)deps.add(passes.get(dependencies.size()-2).name);
        }
        var result=new ArrayList<Pass>();var done=new HashSet<String>();
        while(result.size()<passes.size()){
            boolean progress=false;
            for(var pass:passes)if(!done.contains(pass.name)&&done.containsAll(dependencies.get(pass.name))){result.add(pass);done.add(pass.name);progress=true;}
            if(!progress)throw new IllegalArgumentException("Render graph contains a dependency cycle");
        }
        ordered=List.copyOf(result);
    }
    public List<String> order(){return ordered.stream().map(Pass::name).toList();}
    public void execute(){for(var pass:ordered)pass.record.run();}
}
