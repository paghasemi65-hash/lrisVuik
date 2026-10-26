package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.vulkan.shader.*;
import net.irisshaders.iris.vulkan.render.DescriptorFlipper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShaderTests {
    @TempDir Path temp;
    @Test void unsupportedBufferBlocksCannotEscapeWithUnboundNativeDescriptors(){
        for(String declaration:List.of("layout(std430,binding=0) buffer Values { float value; };","layout(std140,binding=0) uniform Values { float value; };")){
            var failure=assertThrows(ShaderVulkanTransformer.StageFailure.class,()->new ShaderVulkanTransformer().transformProgram(Map.of(ShaderStage.COMPUTE,
                "#version 460\nlayout(local_size_x=1) in;"+declaration+"void main(){}")));
            assertTrue(failure.getMessage().contains("Values"));assertTrue(failure.getMessage().contains("host allocation"));
        }
    }
    @Test void reconcilesSourceBindingsWithoutLosingImageFormatsAndAccess() throws Exception {
        var program=new ShaderVulkanTransformer().transformProgram(Map.of(ShaderStage.COMPUTE,"""
            #version 460
            layout(local_size_x=1) in;
            layout(set=3,binding=0,rgba16f) writeonly uniform image2D colorimg0;
            layout(binding=0) uniform sampler2D colortex0;
            layout(location=4) uniform float strength;
            void main(){imageStore(colorimg0,ivec2(0),texelFetch(colortex0,ivec2(0),0)*strength);}
            """));
        assertEquals(24,program.descriptors().get("colorimg0").binding());
        assertEquals(0,program.descriptors().get("colortex0").binding());
        assertTrue(program.descriptors().get("colorimg0").resource().qualifiers().contains("rgba16f"));
        assertTrue(program.descriptors().get("colorimg0").resource().qualifiers().contains("writeonly"));
        try(var compiler=new SpirvCompiler(temp,1)){assertTrue(compiler.compile(ShaderStage.COMPUTE,"explicit-bindings",program.sources().get(ShaderStage.COMPUTE)).join().length>20);}
    }
    @Test void inverseLegacyMatricesAndAllTextureMatrixIndicesCompile() throws Exception {
        var program=new ShaderVulkanTransformer().transformProgram(Map.of(ShaderStage.VERTEX,"""
            #version 120
            void main(){mat4 combined=gl_ModelViewMatrixInverse*gl_ProjectionMatrixTranspose*gl_ModelViewProjectionMatrixInverseTranspose;
                for(int i=0;i<8;i++)combined*=gl_TextureMatrix[i];gl_Position=combined*gl_Vertex;}
            """));
        assertEquals(8,program.uniforms().member("iris_TextureMatrix").field().arrayLength());
        try(var compiler=new SpirvCompiler(temp,1)){assertTrue(compiler.compile(ShaderStage.VERTEX,"legacy-matrices",program.sources().get(ShaderStage.VERTEX)).join().length>20);}
    }
    @Test void concurrentPackAndCompositorTransformsKeepIndependentRoots() throws Exception {
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs=new ArrayList<java.util.concurrent.Future<?>>();
            for(int job=0;job<60;job++){
                final String name="unique"+job;
                jobs.add(workers.submit(()->{
                    var program=new ShaderVulkanTransformer().transformProgram(Map.of(ShaderStage.VERTEX,
                        "#version 460\nuniform float "+name+";void main(){gl_Position=vec4("+name+");}"));
                    assertEquals(List.of(name),program.uniforms().members().stream().map(m->m.field().name()).toList());
                    assertTrue(program.sources().get(ShaderStage.VERTEX).contains(name));
                }));
            }
            for(var job:jobs)job.get();
        }
    }
    @Test void interfaceLocationsAndInterpolationAreLinked() throws Exception {
        String vertex="#version 460\nlayout(location=5) flat out ivec2 value;void main(){value=ivec2(1);gl_Position=vec4(0);}";
        String fragment="#version 460\nlayout(location=5) flat in ivec2 renamed;void main(){gl_FragColor=vec4(renamed,0,1);}";
        var transformer=new ShaderVulkanTransformer();var program=transformer.transformProgram(Map.of(ShaderStage.VERTEX,vertex,ShaderStage.FRAGMENT,fragment));
        try(var compiler=new SpirvCompiler(temp,1)){for(var stage:program.sources().entrySet())assertTrue(compiler.compile(stage.getKey(),"linked",stage.getValue()).join().length>20);}
        assertThrows(IllegalArgumentException.class,()->transformer.transformProgram(Map.of(ShaderStage.VERTEX,vertex,ShaderStage.FRAGMENT,fragment.replace("flat in","smooth in"))));
        assertThrows(IllegalArgumentException.class,()->transformer.transformProgram(Map.of(ShaderStage.VERTEX,vertex,ShaderStage.FRAGMENT,fragment.replace("location=5","location=6"))));
    }
    @Test void legacyFogDrawMatricesAndLargeSourcesCompile() throws Exception {
        var source=new ShaderVulkanTransformer().transformProgram(Map.of(
            ShaderStage.VERTEX,"#version 120\nvoid main(){gl_Position=ftransform();}",
            ShaderStage.FRAGMENT,"#version 120\nuniform float rainStrength=0.0;\n/*\n#define COMMENT_ONLY\n*/\nvoid main(){gl_FragColor=vec4(gl_Fog.start*gl_Fog.scale+rainStrength);}"));
        assertNotNull(source.uniforms().member("iris_ModelViewMatrix"));assertNotNull(source.uniforms().member("fogStart"));
        try(var compiler=new SpirvCompiler(temp,1)){
            for(var stage:source.sources().entrySet())assertTrue(compiler.compile(stage.getKey(),"legacy",stage.getValue()).join().length>20);
            String large="#version 460\n/*"+"large source ".repeat(20000)+"*/\nvoid main(){gl_Position=vec4(0);}";
            assertTrue(compiler.compile(ShaderStage.VERTEX,"large",large).join().length>20);
        }
    }
    @Test void std140Vec3TailAndMatrixArray() {
        var layout=Std140Layout.pack(List.of(
            new Std140Layout.Field("a",Std140Layout.Type.of("vec3"),0),
            new Std140Layout.Field("b",Std140Layout.Type.of("float"),0),
            new Std140Layout.Field("c",Std140Layout.Type.of("mat3"),2),
            new Std140Layout.Field("d",Std140Layout.Type.of("bool"),0)));
        assertEquals(12,layout.member("b").offset());
        assertEquals(16,layout.member("c").offset());
        assertEquals(48,layout.member("c").arrayStride());
        assertEquals(128,layout.size());
        var bytes=ByteBuffer.allocate(layout.size()).order(ByteOrder.nativeOrder());
        layout.putFloats(bytes,"c",1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18);
        assertEquals(4,bytes.getFloat(32));assertEquals(10,bytes.getFloat(64));
        layout.putInts(bytes,"d",1);assertEquals(1,bytes.getInt(112));
    }
    @Test void graphicsTransformsAndCompiles() throws Exception {
        var program=new ShaderVulkanTransformer().transformProgram(Map.of(
            ShaderStage.VERTEX,"""
                #version 120
                uniform mat4 gbufferProjection;
                uniform mat4 gbufferModelView;
                varying vec2 uv;
                void main() { uv=gl_MultiTexCoord0.xy; gl_Position=ftransform(); if(gl_VertexID==4) return; }
                """,
            ShaderStage.FRAGMENT,"""
                #version 120
                uniform sampler2D colortex0;
                uniform sampler2DShadow shadowtex0;
                uniform float frameTimeCounter;
                varying vec2 uv;
                void main() { gl_FragData[0]=texture2D(colortex0,uv)*shadow2D(shadowtex0,vec3(uv,0.5)); gl_FragData[1]=vec4(frameTimeCounter); }
                """));
        assertEquals(0,program.descriptors().get("colortex0").binding());
        assertEquals(19,program.descriptors().get("shadowtex0").binding());
        assertTrue(program.sources().get(ShaderStage.VERTEX).contains("iris_pack_main"));
        try(var compiler=new SpirvCompiler(temp,2)) {
            for(var e:program.sources().entrySet()) {
                byte[] result=compiler.compile(e.getKey(),"legacy",e.getValue()).join();
                assertEquals(0x07230203,ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt());
            }
        }
    }
    @Test void computeStorageArraysCompile() throws Exception {
        var p=new ShaderVulkanTransformer().transformProgram(Map.of(ShaderStage.COMPUTE,"""
            #version 430
            layout(local_size_x=8,local_size_y=8) in;
            layout(rgba16f) uniform image2D colorimg0;
            uniform float weights[3];
            void main(){imageStore(colorimg0,ivec2(gl_GlobalInvocationID.xy),vec4(weights[1]));}
            """));
        assertEquals(24,p.descriptors().get("colorimg0").binding());
        assertEquals(16,p.uniforms().member("weights").arrayStride());
        try(var c=new SpirvCompiler(temp,1)){assertTrue(c.compile(ShaderStage.COMPUTE,"compute",p.sources().get(ShaderStage.COMPUTE)).join().length>20);}
    }
    @Test void cacheRecoversFromCorruptionAndDoesNotShareMutableArrays() throws Exception {
        String source="#version 460\nvoid main(){gl_Position=vec4(0);}";
        byte[] original;
        try(var c=new SpirvCompiler(temp,1)){
            original=c.compile(ShaderStage.VERTEX,"test",source).join();
            var second=c.compile(ShaderStage.VERTEX,"test",source).join();second[0]=0;assertNotEquals(second[0],original[0]);
        }
        try(var files=Files.list(temp.resolve("cache/iris_vulkan_spirv"))){Files.write(files.findFirst().orElseThrow(),new byte[]{0});}
        try(var c=new SpirvCompiler(temp,1)){assertArrayEquals(original,c.compile(ShaderStage.VERTEX,"test",source).join());}
    }
    @Test void uniformConflictsAndDynamicOutputsFailEarly() {
        assertThrows(IllegalArgumentException.class,()->new ShaderVulkanTransformer().transformProgram(Map.of(
            ShaderStage.VERTEX,"#version 330\nuniform float value; void main(){gl_Position=vec4(value);}",
            ShaderStage.FRAGMENT,"#version 330\nuniform vec3 value; out vec4 frag; void main(){frag=vec4(value,1);}")));
        assertThrows(IllegalArgumentException.class,()->new ShaderVulkanTransformer().transformProgram(Map.of(
            ShaderStage.FRAGMENT,"#version 120\nvoid main(){int n=1;gl_FragData[n]=vec4(1);}")));
    }
    @Test void flipOnlyWrittenTargetsAndKeepEarlierSnapshots() {
        var f=new DescriptorFlipper<>(List.of(new DescriptorFlipper.Pair<>("0a","0b"),new DescriptorFlipper.Pair<>("1a","1b")));
        var before=f.snapshotReads();f.commitWrites(List.of(1));assertEquals(List.of("0a","1b"),f.snapshotReads());
        assertEquals(List.of("0a","1a"),before);assertEquals("1a",f.write(1));
        assertThrows(IllegalArgumentException.class,()->f.commitWrites(List.of(0,0)));
        assertEquals("0a",f.read(0));
    }
    @Test void modernOutputsAndLegacyTextureSamplerCompile() throws Exception {
        var p=new ShaderVulkanTransformer().transformProgram(Map.of(ShaderStage.FRAGMENT,"""
            #version 330
            uniform sampler2D texture;
            layout(location=1) out vec4 b;
            out vec4 a;
            void main(){a=texture2D(texture,vec2(0.5));b=a;}
            """));
        assertNotNull(p.descriptors().get("iris_TextureSampler"));
        try(var c=new SpirvCompiler(temp,1)){assertTrue(c.compile(ShaderStage.FRAGMENT,"modern",p.sources().get(ShaderStage.FRAGMENT)).join().length>20);}
    }
    @Test void projectionAndInverseRemainConsistent() {
        float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        var p=DepthConvention.projectionToOpenGl(identity);var inv=DepthConvention.inverseProjectionToOpenGl(identity);
        for(int row=0;row<4;row++)for(int col=0;col<4;col++){
            float value=0;for(int k=0;k<4;k++)value+=p[k*4+row]*inv[col*4+k];assertEquals(row==col?1f:0f,value,0.000001f);
        }
        assertEquals(-1,p[14]);assertEquals(1,p[10]+p[14]);
    }
    @Test void preprocessResolvesConditionalIncludesAndMacros() throws Exception {
        var shaders=Files.createDirectories(temp.resolve("pack/shaders"));Files.createDirectories(shaders.resolve("lib"));
        Files.writeString(shaders.resolve("lib/color.glsl"),"#define COLOR vec4(1.0)\n");
        Files.writeString(shaders.resolve("final.fsh"),"""
            #version 120
            #if MC_VERSION >= 260300
            #include "/lib/color.glsl"
            #else
            #include "/missing-in-inactive-branch.glsl"
            #endif
            /* RENDERTARGETS: 0,12 */
            void main(){gl_FragColor=COLOR;}
            """);
        try(var source=new ShaderPackSource(temp.resolve("pack"))){
            var expanded=source.preprocess("final.fsh",Map.of("MC_VERSION","260300"));
            assertTrue(expanded.contains("#version 120"));assertFalse(expanded.contains("#include"));
            assertEquals(List.of(0,12),ShaderPackSource.drawBuffers(expanded));
            var p=new ShaderVulkanTransformer().transformProgram(Map.of(ShaderStage.FRAGMENT,expanded));
            try(var c=new SpirvCompiler(temp,1)){assertTrue(c.compile(ShaderStage.FRAGMENT,"preprocessed",p.sources().get(ShaderStage.FRAGMENT)).join().length>20);}
            assertThrows(IllegalArgumentException.class,()->source.preprocess("../../outside.glsl",Map.of()));
        }
    }
    @Test void zipPackCanHaveComputeAndGraphicsWithTheSameBasename() throws Exception {
        var zip=temp.resolve("test-pack.zip");
        try(var stream=new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))){
            var files=Map.of(
                "shaders/composite.vsh","#version 120\nvoid main(){gl_Position=vec4(0,0,0,1);}",
                "shaders/composite.fsh","#version 120\nvoid main(){gl_FragColor=vec4(1);}",
                "shaders/composite.csh","#version 430\nlayout(local_size_x=1) in;void main(){}");
            for(var e:files.entrySet()){stream.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));stream.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));stream.closeEntry();}
        }
        assertDoesNotThrow(()->ShaderPackCompilerMain.main(new String[]{zip.toString(),temp.resolve("game").toString()}));
    }
}
