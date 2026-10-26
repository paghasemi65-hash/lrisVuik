package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.vulkan.pack.*;
import net.irisshaders.iris.vulkan.shader.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PackLoaderTest {
    @Test void sourceDefaultsAndLastRenderDirectiveFollowIris() {
        var options=PackOptions.discover(List.of("#define LIGHTING 0 // [128 256]\n"));
        assertEquals("0",options.values(Map.of()).get("LIGHTING"));
        assertEquals(List.of(0,6,4),ShaderPackSource.drawBuffers("/* DRAWBUFFERS:06 */\n/* DRAWBUFFERS:064 */"));
    }
    @Test void heldItemMappingsUsePackIdsAndNamespaces() throws Exception {
        var properties=PackProperties.parse("item.44002=torch example:lamp\n#if MC_VERSION >= 260300\nitem.44084=copper_torch\n#endif\n",Map.of("MC_VERSION","260300"));
        var ids=ItemIdMap.parse(properties);
        assertEquals(44002,ids.get("minecraft:torch"));assertEquals(44002,ids.get("example:lamp"));assertEquals(44084,ids.get("minecraft:copper_torch"));
        assertFalse(ids.containsKey("minecraft:stone"));
    }
    @TempDir Path game;
    private static final String VERTEX="#version 460\nvoid main(){gl_Position=vec4(0,0,0,1);}\n";
    @Test void conditionalPropertiesContinuationsEscapesAndProfiles() throws Exception {
        var properties=PackProperties.parse("""
            # Comment
            #if MC_VERSION >= 260300 && defined(MC_GL_VENDOR_NVIDIA)
            profile.base=FOG QUALITY:1
            profile.high=profile.base QUALITY=2 \\
              !program.composite2
            screen=QUALITY [Advanced]
            name=Hello\\u0020world
            #else
            invalid=true
            #endif
            program.composite.enabled=FOG && QUALITY >= 2
            """,Map.of("MC_VERSION","260300","MC_GL_VENDOR_NVIDIA","1"));
        assertFalse(properties.all().containsKey("invalid"));assertEquals("Hello world",properties.get("name",""));
        var high=properties.profile("high");assertEquals(Map.of("FOG","true","QUALITY","2"),high.options());
        assertFalse(properties.enabled("composite2",high.options(),high.disabledPrograms()));assertTrue(properties.enabled("composite",high.options(),high.disabledPrograms()));
        assertThrows(IllegalArgumentException.class,()->new PackProperties(Map.of("profile.a","profile.b","profile.b","profile.a")).profile("a"));
    }
    @Test void optionsRewriteIncludesAndValidateChoices() {
        String source="// #define FOG\n#define QUALITY 1 // [1 2 3]\nconst float shadowDistance = 32.0; // [32.0 64.0]\n#define SCALE(x) ((x)*2.0)\n";
        var options=PackOptions.discover(List.of(source));var values=options.values(Map.of("FOG","true","QUALITY","3","shadowDistance","64.0"));
        String rewritten=options.apply(source,values);assertTrue(rewritten.contains("#define QUALITY 3"));assertTrue(rewritten.contains("shadowDistance = 64.0"));
        assertEquals("1",options.macros(values).get("FOG"));assertThrows(IllegalArgumentException.class,()->options.values(Map.of("QUALITY","999")));
        assertFalse(options.declarations().containsKey("SCALE"));assertTrue(rewritten.contains("#define SCALE(x) ((x)*2.0)"));
    }
    @Test void propertyMacroValuesRespectDefinitionOrderAndLiteralOptionNames() throws Exception {
        var p=PackProperties.parse("#define SCALE 0.5\nsize.buffer.colortex0=SCALE SCALE\n#undef SCALE\n#define SCALE 0.25\nsize.buffer.colortex1=SCALE 128\nprofile.high=QUALITY:2\nscreen=QUALITY\nprogram.composite.enabled=QUALITY > 1\n",Map.of("QUALITY","2"));
        assertEquals("0.5 0.5",p.get("size.buffer.colortex0",""));
        assertEquals("0.25 128",p.get("size.buffer.colortex1",""));
        assertEquals("QUALITY:2",p.get("profile.high",""));assertEquals("QUALITY",p.get("screen",""));
        assertTrue(p.enabled("composite",Map.of(),Set.of()));
    }
    @Test void directoryAndZipLoadWithDimensionReplacementAndFallbacks() throws Exception {
        Path shaders=game.resolve("shaderpacks/Folder/shaders");Files.createDirectories(shaders.resolve("world-1"));
        Files.writeString(shaders.resolve("shaders.properties"),"profile.high=QUALITY:2\n");
        Files.writeString(shaders.resolve("settings.glsl"),"#define QUALITY 1 // [1 2]\n");
        String fragment="#version 460\n#include \"/settings.glsl\"\nuniform sampler2D colortex0;\nconst int colortex0Format=RGBA16F;\nvoid main(){gl_FragColor=vec4(float(QUALITY));}\n";
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"),VERTEX);Files.writeString(shaders.resolve("gbuffers_terrain.fsh"),fragment);
        Files.writeString(shaders.resolve("world-1/gbuffers_basic.vsh"),VERTEX);Files.writeString(shaders.resolve("world-1/gbuffers_basic.fsh"),fragment);
        Path zip=game.resolve("shaderpacks/Archive.zip");
        try(var output=new ZipOutputStream(Files.newOutputStream(zip));var paths=Files.walk(shaders)){
            for(var file:paths.filter(Files::isRegularFile).toList()){output.putNextEntry(new ZipEntry("shaders/"+shaders.relativize(file).toString().replace('\\','/')));output.write(Files.readAllBytes(file));output.closeEntry();}
        }
        var loader=new ShaderPackLoader(game);assertEquals(List.of("Archive.zip","Folder"),loader.discover());
        try(var compiler=new SpirvCompiler(game,2,SpirvCompiler.Target.VULKAN_1_2)){
            for(String name:loader.discover()) {
                var loaded=loader.load(new ShaderPackLoader.Selection(name,"high",Map.of()),"minecraft:the_nether",Map.of("MC_VERSION","260300"),compiler,null);
                assertEquals("world-1",loaded.directory());assertFalse(loaded.programs().containsKey("gbuffers_terrain"));
                assertEquals("gbuffers_basic",loaded.fallbacks().get("gbuffers_terrain"));assertEquals("2",loaded.values().get("QUALITY"));
                assertEquals("RGBA16F",loaded.resources().targets().get("colortex0").format().name());
            }
        }
    }
    @Test void customDimensionMappingAndFallbackRoots() throws Exception {
        Path shaders=game.resolve("pack/shaders");Files.createDirectories(shaders.resolve("moon"));
        try(var pack=new ShaderPackSource(shaders.getParent())){
            var properties=new PackProperties(Map.of("dimension.moon","example:moon *:*"));
            assertEquals("moon",ProgramFallbacks.dimensionDirectory(pack,properties,"example:moon"));
            assertEquals("moon",ProgramFallbacks.dimensionDirectory(pack,properties,"unknown:dimension"));
        }
        assertEquals("gbuffers_textured",ProgramFallbacks.resolve("gbuffers_water",Set.of("gbuffers_textured"),Set.of()).orElseThrow());
        assertTrue(ProgramFallbacks.resolve("final",Set.of("gbuffers_basic"),Set.of()).isEmpty());
        assertEquals("shadow",ProgramFallbacks.resolve("shadow_lightning",Set.of("shadow"),Set.of()).orElseThrow());
    }
    @Test void compileFailureIncludesExactTransformedLineSnippetAndOutput() throws Exception {
        Path shaders=game.resolve("shaderpacks/Broken/shaders");Files.createDirectories(shaders);
        Files.writeString(shaders.resolve("final.vsh"),VERTEX);
        Files.writeString(shaders.resolve("final.fsh"),"#version 460\nvoid main(){gl_FragColor=vec4(unknownSymbol);}\n");
        try(var compiler=new SpirvCompiler(game,1)){
            var failure=assertThrows(CompilationDiagnostic.Failure.class,()->new ShaderPackLoader(game).load(new ShaderPackLoader.Selection("Broken","",Map.of()),"minecraft:overworld",Map.of(),compiler,null));
            var diagnostic=failure.diagnostics().getFirst();assertEquals("final.fsh",diagnostic.file());assertTrue(diagnostic.line()>0);
            assertTrue(diagnostic.snippet().contains("unknownSymbol"));assertTrue(diagnostic.compilerOutput().contains("error"));assertEquals("transformed GLSL",diagnostic.coordinateSpace());
        }
    }
    @Test void resourceDirectivesPreserveFormatsTemporalClearsFlipsAndStorageAliases() {
        String fragment="""
            #version 460
            uniform sampler2D colortex3;
            uniform sampler2D shadowtex1;
            uniform image2D colorimg3;
            const int colortex3Format=R11F_G11F_B10F;
            const bool colortex3Clear=false;
            const vec4 colortex3ClearColor=vec4(0.1,0.2,0.3,1);
            const bool colortex3MipmapEnabled=true;
            const bool shadowHardwareFiltering1=true;
            const int shadowMapResolution=512;
            /* RENDERTARGETS:3 */ /*Flip:colortex3:false*/
            void main(){}
            """;
        var plan=PackResourcePlan.parse(Map.of("composite",Map.of(ShaderStage.FRAGMENT,fragment)),new PackProperties(Map.of("size.buffer.colortex3","0.5 128","image.volume","volumeSampler RGBA RGBA32F FLOAT false false 4 8 16")),Map.of());
        var color=plan.targets().get("colortex3");assertFalse(color.clear());assertTrue(color.storage());assertTrue(color.mipmaps());
        assertEquals("colortex3",plan.aliases().get("colorimg3"));assertArrayEquals(new int[]{400,128,1},color.size().resolve(800,600,Map.of()));
        assertFalse(plan.passes().get("composite").flips().get(3));assertTrue(plan.targets().get("shadowtex1").sampler().compare());
        assertEquals(PackResourcePlan.Shape.D3,plan.targets().get("volume").size().shape());assertEquals("volume",plan.aliases().get("volumeSampler"));
    }
    @Test void preprocessorDiagnosticsIdentifyIncludedFile() throws Exception {
        Path root=game.resolve("shaderpacks/Included/shaders");Files.createDirectories(root);
        Files.writeString(root.resolve("final.vsh"),VERTEX);Files.writeString(root.resolve("final.fsh"),"#version 460\n#include \"bad.glsl\"\nvoid main(){gl_FragColor=vec4(1);}\n");
        Files.writeString(root.resolve("bad.glsl"),"// include\n#error Invalid option combination\n");
        try(var compiler=new SpirvCompiler(game,1)){
            var failure=assertThrows(CompilationDiagnostic.Failure.class,()->new ShaderPackLoader(game).load(new ShaderPackLoader.Selection("Included","",Map.of()),"minecraft:overworld",Map.of(),compiler,null));
            var error=failure.diagnostics().getFirst();assertEquals("bad.glsl",error.file());assertEquals(2,error.line());assertTrue(error.snippet().contains("#error"));
        }
    }
    @Test void noiseIsDeterministicAndNotAPlaceholder() {
        var a=TextureData.noise(16);var b=TextureData.noise(16);assertArrayEquals(a.pixels(),b.pixels());
        var unique=new HashSet<Byte>();for(byte pixel:a.pixels())unique.add(pixel);assertTrue(unique.size()>200);
    }
    @Test void pngMetadataRawVolumeAndMissingAssets() throws Exception {
        Path root=game.resolve("pack/shaders");Files.createDirectories(root);
        var png=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_ARGB);png.setRGB(0,0,0x80402010);
        javax.imageio.ImageIO.write(png,"png",root.resolve("custom.png").toFile());
        Files.writeString(root.resolve("custom.png.mcmeta"),"{\"texture\":{\"blur\":true,\"clamp\":true,\"mipmaps\":true}}");
        Files.write(root.resolve("volume.bin"),new byte[]{0,(byte)255,64,(byte)128});
        try(var pack=new ShaderPackSource(root.getParent())){
            var image=TextureData.load(pack,"custom.png",new PackProperties(Map.of("texture.composite.custom.wrapS","MIRRORED_REPEAT","texture.composite.custom.magFilter","NEAREST")),"texture.composite.custom",null);
            assertArrayEquals(new byte[]{64,32,16,(byte)128},Arrays.copyOf(image.pixels(),4));assertTrue(image.mipmaps());
            assertEquals(PackResourcePlan.Wrap.MIRRORED_REPEAT,image.sampler().s());assertEquals(PackResourcePlan.Wrap.CLAMP_TO_EDGE,image.sampler().t());
            assertFalse(image.sampler().nearest());assertTrue(image.sampler().magNearest());
            var volume=TextureData.load(pack,"volume.bin TEXTURE_3D R16F 1 2 2 RED UNSIGNED_BYTE",new PackProperties(Map.of()),"texture.composite.volume",null);
            assertEquals(PackResourcePlan.Shape.D3,volume.shape());assertEquals(8,volume.pixels().length);
            assertThrows(java.io.IOException.class,()->TextureData.load(pack,"missing.png",new PackProperties(Map.of()),"texture.composite.missing",null));
        }
        var options=PackOptions.discover(List.of("#define QUALITY 1 // [1 2]","#define QUALITY 2 // [1 2]"));
        assertTrue(options.declarations().isEmpty());
    }
}
