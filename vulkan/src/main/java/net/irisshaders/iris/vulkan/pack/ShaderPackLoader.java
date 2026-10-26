package net.irisshaders.iris.vulkan.pack;

import net.irisshaders.iris.vulkan.shader.*;
import net.irisshaders.iris.vulkan.render.VulkanPipelineManager;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Immutable CPU preparation; no VkDevice calls or ZIP handles escape the worker. */
public final class ShaderPackLoader {
    public record Selection(String pack,String profile,Map<String,String> overrides) {
        public Selection { Objects.requireNonNull(pack);profile=Objects.requireNonNullElse(profile,"");overrides=Map.copyOf(overrides); }
    }
    public record Program(String name,Map<ShaderStage,String> files,Map<ShaderStage,String> source,VulkanPipelineManager.Compiled compiled) {
        public Program {files=Map.copyOf(files);source=Map.copyOf(source);}
    }
    public record Loaded(String name,String dimension,String directory,PackOptions options,Map<String,String> values,
                         PackProperties properties,Map<String,Program> programs,Map<String,String> fallbacks,
                         PackResourcePlan resources,Map<String,TextureData> textures,Map<String,Integer> itemIds,MaterialIdMap blockIds,MaterialIdMap entityIds,PackBlendOverrides blends) {
        public Loaded {values=Map.copyOf(values);programs=Map.copyOf(programs);fallbacks=Map.copyOf(fallbacks);textures=Map.copyOf(textures);itemIds=Map.copyOf(itemIds);}
    }
    private final Path directory;
    public ShaderPackLoader(Path gameDirectory) throws IOException {directory=gameDirectory.resolve("shaderpacks");Files.createDirectories(directory);}
    public List<String> discover() throws IOException {
        try(var files=Files.list(directory)){return files.filter(p->Files.isDirectory(p)||p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
            .map(p->p.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).toList();}
    }
    public Path resolve(String name) throws IOException {
        if(name.isBlank()||name.contains("/")||name.contains("\\")||name.equals(".."))throw new IOException("Select a pack from shaderpacks/: "+name);
        Path path=directory.resolve(name).toRealPath();if(!path.startsWith(directory.toRealPath()))throw new IOException("Pack path escapes shaderpacks/");return path;
    }
    public Loaded load(Selection selection,String dimension,Map<String,String> environment,SpirvCompiler compiler,TextureData.ExternalResources external) throws IOException {
        environment=withStageMacros(environment);
        try(var pack=new ShaderPackSource(resolve(selection.pack()))) {
            var texts=new ArrayList<String>();for(String file:pack.files())if(file.matches(".*\\.(glsl|vsh|fsh|csh|gsh|tcs|tes)"))texts.add(pack.readText(file));
            var options=PackOptions.discover(texts);var defaults=options.values(Map.of());
            String rawProperties=pack.exists("shaders.properties")?new String(pack.readBytes("shaders.properties"),StandardCharsets.ISO_8859_1):"";
            var properties=PackProperties.parse(rawProperties,merge(environment,options.macros(defaults)));
            var selected=new HashMap<String,String>();var disabled=new HashSet<String>();
            if(!selection.profile().isEmpty()){var profile=properties.profile(selection.profile());selected.putAll(profile.options());disabled.addAll(profile.disabledPrograms());}
            selected.putAll(selection.overrides());var values=options.values(selected);
            properties=PackProperties.parse(rawProperties,merge(environment,options.macros(values)));
            var dimensions=PackProperties.parse(pack.exists("dimension.properties")?new String(pack.readBytes("dimension.properties"),StandardCharsets.ISO_8859_1):"",merge(environment,options.macros(values)));
            String directory=ProgramFallbacks.dimensionDirectory(pack,dimensions,dimension);
            var sources=new LinkedHashMap<String,Map<ShaderStage,String>>();var paths=new LinkedHashMap<String,Map<ShaderStage,String>>();
            var variables=merge(environment,values);
            for(String name:pack.programs(directory)) {
                // These entry points require those mods' injected GLSL and draw data; vanilla never schedules them.
                if(name.startsWith("dh_")||name.startsWith("clrwl_")||name.startsWith("voxy_"))continue;
                if(!properties.enabled(name,variables,disabled)||!properties.enabled(directory.isEmpty()?name:directory+"/"+name,variables,disabled)){disabled.add(name);continue;}
                String prefix=directory.isEmpty()?name:directory+"/"+name;
                var stages=new EnumMap<ShaderStage,String>(ShaderStage.class);var stagePaths=new EnumMap<ShaderStage,String>(ShaderStage.class);
                for(var stage:ShaderStage.values()) {
                    String path=prefix+switch(stage){case VERTEX->".vsh";case FRAGMENT->".fsh";case COMPUTE->".csh";};
                    if(!pack.exists(path))continue;
                    String source;
                    try {source=pack.preprocess(path,environment,text->options.apply(text,values));}
                    catch(Exception e){
                        String origin=path;
                        var location=Pattern.compile("(/[^\\r\\n:]+):\\d+:\\d+: error:").matcher(Objects.toString(e.getMessage(),""));
                        if(location.find())origin=location.group(1).replaceFirst("^/","");
                        throw new CompilationDiagnostic.Failure(List.of(CompilationDiagnostic.of(origin,stage.name(),pack.readText(origin),e,"source")));
                    }
                    if(stage==ShaderStage.COMPUTE){sources.put(name+".compute",Map.of(stage,source));paths.put(name+".compute",Map.of(stage,path));}
                    else {stages.put(stage,source);stagePaths.put(stage,path);}
                }
                if(stages.size()==2){sources.put(name,stages);paths.put(name,stagePaths);}
                // An incomplete graphics pair is missing for fallback purposes; stages never come from unrelated programs.
            }
            if(sources.isEmpty())throw new IOException("No enabled, complete shader programs in "+selection.pack()+"/"+directory);
            var plan=PackResourcePlan.parse(sources,properties,variables);
            var prepared=new LinkedHashMap<String,Program>();var diagnostics=new ArrayList<CompilationDiagnostic>();
            for(var entry:sources.entrySet()) {
                var files=paths.get(entry.getKey());ShaderVulkanTransformer.Program transformed;
                try {
                    var compileSources=new EnumMap<ShaderStage,String>(ShaderStage.class);
                    entry.getValue().forEach((stage,source)->compileSources.put(stage,replaceFormatConstants(source)));
                    transformed=new ShaderVulkanTransformer().transformProgram(compileSources);
                }catch(Exception e){
                    if(e instanceof ShaderVulkanTransformer.StageFailure stageFailure){var stage=stageFailure.stage();diagnostics.add(CompilationDiagnostic.of(files.get(stage),stage.name(),entry.getValue().get(stage),e,"preprocessed source"));}
                    else diagnostics.add(CompilationDiagnostic.of(String.join(" + ",files.values()),"PROGRAM","",e,"program interface"));
                    continue;
                }
                var binary=new EnumMap<ShaderStage,byte[]>(ShaderStage.class);
                var jobs=new EnumMap<ShaderStage,CompletableFuture<byte[]>>(ShaderStage.class);
                transformed.sources().forEach((stage,source)->jobs.put(stage,compiler.compile(stage,files.get(stage),source)));
                for(var job:jobs.entrySet())try{binary.put(job.getKey(),job.getValue().join());}
                catch(Exception e){diagnostics.add(CompilationDiagnostic.of(files.get(job.getKey()),job.getKey().name(),transformed.sources().get(job.getKey()),e,"transformed GLSL"));}
                if(binary.size()==transformed.sources().size())prepared.put(entry.getKey(),new Program(entry.getKey(),files,entry.getValue(),new VulkanPipelineManager.Compiled(transformed,binary)));
            }
            if(!diagnostics.isEmpty())throw new CompilationDiagnostic.Failure(diagnostics);
            var fallback=new LinkedHashMap<String,String>();var names=new LinkedHashSet<>(ProgramFallbacks.PARENTS.keySet());names.addAll(prepared.keySet());
            for(String name:names)ProgramFallbacks.resolve(name,prepared.keySet(),disabled).ifPresent(resolved->fallback.put(name,resolved));
            var textures=new LinkedHashMap<String,TextureData>();
            for(var e:plan.customTextures().entrySet())textures.put(e.getKey(),TextureData.load(pack,e.getValue(),properties,"texture."+e.getKey(),external));
            boolean noise=prepared.values().stream().flatMap(p->p.compiled().program().descriptors().bindings().stream()).anyMatch(b->b.resource().name().equals("noisetex"));
            if(noise&&!textures.containsKey("*.noisetex"))textures.put("*.noisetex",TextureData.noise(plan.noiseResolution()));
            var itemIds=ItemIdMap.parse(PackProperties.parse(pack.exists("item.properties")?new String(pack.readBytes("item.properties"),StandardCharsets.ISO_8859_1):"",merge(environment,options.macros(values))));
            var blockIds=MaterialIdMap.parse(PackProperties.parse(pack.exists("block.properties")?new String(pack.readBytes("block.properties"),StandardCharsets.ISO_8859_1):"",merge(environment,options.macros(values))),"block");
            var entityIds=MaterialIdMap.parse(PackProperties.parse(pack.exists("entity.properties")?new String(pack.readBytes("entity.properties"),StandardCharsets.ISO_8859_1):"",merge(environment,options.macros(values))),"entity");
            return new Loaded(selection.pack(),dimension,directory,options,values,properties,prepared,fallback,plan,textures,itemIds,blockIds,entityIds,new PackBlendOverrides(properties));
        }
    }
    private static Map<String,String> merge(Map<String,String> first,Map<String,String> second){var result=new LinkedHashMap<>(first);result.putAll(second);return result;}
    private static Map<String,String> withStageMacros(Map<String,String> environment) {
        var result=new LinkedHashMap<>(environment);
        var stages=List.of("NONE","SKY","SUNSET","CUSTOM_SKY","SUN","MOON","STARS","VOID","TERRAIN_SOLID","TERRAIN_CUTOUT_MIPPED","TERRAIN_CUTOUT","ENTITIES","BLOCK_ENTITIES","DESTROY","OUTLINE","DEBUG","HAND_SOLID","TERRAIN_TRANSLUCENT","TRIPWIRE","PARTICLES","CLOUDS","RAIN_SNOW","WORLD_BORDER","HAND_TRANSLUCENT");
        for(int i=0;i<stages.size();i++)result.put("MC_RENDER_STAGE_"+stages.get(i),Integer.toString(i));
        return Map.copyOf(result);
    }
    private static String replaceFormatConstants(String source) {
        // These pack metadata constants are consumed by the resource plan before AST compilation.
        var matcher=Pattern.compile("(\\bconst\\s+int\\s+\\w+Format\\s*=\\s*)([A-Z][A-Z0-9_]*)(\\s*;)").matcher(source);
        return matcher.replaceAll(m->Matcher.quoteReplacement(m.group(1)+TextureFormat.of(m.group(2)).shaderToken()+m.group(3)));
    }
}
