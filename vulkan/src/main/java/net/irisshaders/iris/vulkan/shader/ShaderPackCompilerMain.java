package net.irisshaders.iris.vulkan.shader;

import java.nio.file.*;
import java.util.*;

/** Offline compatibility diagnostic; this command does not activate a shaderpack in Minecraft. */
public final class ShaderPackCompilerMain {
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Usage: <shaderpack-directory-or-zip> <minecraft-directory>");
        int passed=0,failed=0;
        try(var pack=new ShaderPackSource(Path.of(args[0]));var compiler=new SpirvCompiler(Path.of(args[1]),Math.max(1,Math.min(4,Runtime.getRuntime().availableProcessors()/2)))){
            for(String name:pack.programs()){
                try{
                    var sources=new EnumMap<ShaderStage,String>(ShaderStage.class);
                    if(pack.exists(name+".csh"))compile(compiler,name+".compute",Map.of(ShaderStage.COMPUTE,pack.preprocess(name+".csh",Map.of("MC_VERSION","260300"))));
                    if(pack.exists(name+".vsh"))sources.put(ShaderStage.VERTEX,pack.preprocess(name+".vsh",Map.of("MC_VERSION","260300")));
                    if(pack.exists(name+".fsh"))sources.put(ShaderStage.FRAGMENT,pack.preprocess(name+".fsh",Map.of("MC_VERSION","260300")));
                    if(!sources.isEmpty()){
                        if(sources.size()!=2)throw new IllegalArgumentException("Missing stage; fallback program resolution is not implemented");
                        compile(compiler,name,sources);
                    }
                    passed++;
                }catch(Exception e){System.err.println("FAIL "+name+": "+e.getMessage());failed++;}
            }
        }
        System.out.println("Programs compiled: "+passed+"; failed: "+failed);
        if(failed>0||passed==0)throw new IllegalStateException("Shaderpack is not yet compatible with this engine");
    }
    private static void compile(SpirvCompiler compiler,String name,Map<ShaderStage,String> sources){
        var result=new ShaderVulkanTransformer().transformProgram(sources);
        for(var e:result.sources().entrySet())compiler.compile(e.getKey(),name,e.getValue()).join();
        System.out.println("PASS "+name+"; uniform bytes="+result.uniforms().size()+"; descriptors="+result.descriptors().bindings().size());
    }
}
