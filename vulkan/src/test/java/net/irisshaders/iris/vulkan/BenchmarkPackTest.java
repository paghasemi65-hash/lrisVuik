package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.vulkan.pack.*;
import net.irisshaders.iris.vulkan.shader.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** External licensed pack is supplied by the developer, never bundled with the mod. */
@Tag("benchmark")
class BenchmarkPackTest {
    @TempDir Path game;
    @Test void compileAllDimensions() throws Exception {
        Path pack=Path.of(System.getProperty("iris.benchmark.pack"));
        var loader=new ShaderPackLoader(game);
        Files.copy(pack,game.resolve("shaderpacks").resolve(pack.getFileName()));
        var macros=Map.of("MC_VERSION","260300","MC_GLSL_VERSION","460","IS_IRIS","1","IRIS_VULKAN","1",
            "RENDERPEARL_IS_ZERO_TO_ONE","1","MAX_COLOR_BUFFERS","16","MC_OS_WINDOWS","1","MC_GL_VENDOR_NVIDIA","1","MC_GL_RENDERER_GEFORCE","1");
        try(var compiler=new SpirvCompiler(game,2,SpirvCompiler.Target.VULKAN_1_2)) {
            for(String dimension:List.of("minecraft:overworld","minecraft:the_nether","minecraft:the_end")) {
                try {
                    var loaded=loader.load(new ShaderPackLoader.Selection(pack.getFileName().toString(),"",Map.of()),dimension,macros,compiler,
                        id->{throw new IllegalArgumentException("Native resource requires client: "+id);});
                    assertFalse(loaded.programs().isEmpty());
                    System.out.println(dimension+": "+loaded.programs().size()+" programs compiled");
                } catch(CompilationDiagnostic.Failure failure) {
                    failure.diagnostics().forEach(d->System.err.println(d.display()));throw failure;
                }
            }
        }
    }
}
