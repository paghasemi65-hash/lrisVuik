package net.irisshaders.iris.vulkan;
import net.irisshaders.iris.vulkan.render.QuadVertexData;
import net.irisshaders.iris.vulkan.render.QuadVertexData.Vertex;
import net.irisshaders.iris.vulkan.pack.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class VertexDataTest {
    @Test void blendOverridesUseTargetIdsAndRetainWriteMasks() {
        var nativeBlend=new net.irisshaders.iris.vulkan.render.VulkanPipelineManager.Blend(true,1,0,1,0,0,0,5);
        var overrides=new PackBlendOverrides(new PackProperties(Map.of(
            "blend.gbuffers_terrain","SRC_ALPHA ONE_MINUS_SRC_ALPHA",
            "blend.gbuffers_terrain.colortex7","off",
            "blend.gbuffers_terrain.colortex3","ONE ZERO ONE ONE_MINUS_SRC_ALPHA")));
        var main=overrides.apply("gbuffers_terrain",0,nativeBlend);
        assertEquals(org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA,main.sourceRgb());assertEquals(5,main.writeMask());
        assertFalse(overrides.apply("gbuffers_terrain",7,nativeBlend).enabled());
        assertEquals(org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE,overrides.apply("gbuffers_terrain",3,nativeBlend).sourceRgb());
        assertSame(nativeBlend,overrides.apply("gbuffers_water",0,nativeBlend));
        assertThrows(IllegalArgumentException.class,()->new PackBlendOverrides(new PackProperties(Map.of("blend.gbuffers_basic.colortex16","off"))));
        assertThrows(IllegalArgumentException.class,()->new PackBlendOverrides(new PackProperties(Map.of("blend.gbuffers_basic","ONE UNKNOWN"))));
    }
    @Test void quadCentersTangentsAndMirroredUvHandedness() {
        var basis=QuadVertexData.compute(new Vertex(0,0,0,0,0),new Vertex(1,0,0,1,0),new Vertex(1,1,0,1,1),new Vertex(0,1,0,0,1));
        assertEquals(.5f,basis.u());assertEquals(.5f,basis.v());assertEquals(1,basis.nz());assertEquals(1,basis.tx());assertEquals(0,basis.ty());assertEquals(1,basis.handedness());
        var mirrored=QuadVertexData.compute(new Vertex(0,0,0,1,0),new Vertex(1,0,0,0,0),new Vertex(1,1,0,0,1),new Vertex(0,1,0,1,1));
        assertEquals(-1,mirrored.tx());assertEquals(-1,mirrored.handedness());
    }
    @Test void degenerateUvsStayFiniteAndMidBlockUsesSignedSixtyFourths() {
        var a=new Vertex(0,0,0,0,0);var b=new Vertex(1,0,0,0,0);var c=new Vertex(1,1,0,0,0);
        var basis=QuadVertexData.compute(a,b,c,new Vertex(0,1,0,0,0));
        assertEquals(1,basis.tx()*basis.tx()+basis.ty()*basis.ty()+basis.tz()*basis.tz(),1e-6);
        assertEquals(0,basis.tx()*basis.nx()+basis.ty()*basis.ny()+basis.tz()*basis.nz(),1e-6);
        assertEquals(32,QuadVertexData.midBlock(.5f,0));assertEquals(-32,QuadVertexData.midBlock(.5f,1));
        assertEquals(0,QuadVertexData.midBlock(12.5f,12.5f));
        assertTrue(Float.isFinite(QuadVertexData.compute(a,a,a,a).tx()));
    }
    @Test void materialSelectorsResolveNamespacesAndStateSpecificity() {
        var map=MaterialIdMap.parse(new PackProperties(Map.of("block.10","stone minecraft:oak_log example:lamp","block.20","minecraft:oak_log:axis=x,z","block.30","example:lamp:lit=true")),"block");
        assertEquals(10,map.resolve("minecraft:stone",Map.of()));assertEquals(10,map.resolve("minecraft:oak_log",Map.of("axis","y")));
        assertEquals(20,map.resolve("minecraft:oak_log",Map.of("axis","x")));assertEquals(20,map.resolve("minecraft:oak_log",Map.of("axis","z")));
        assertEquals(30,map.resolve("example:lamp",Map.of("lit","true")));assertEquals(10,map.resolve("example:lamp",Map.of()));
        assertEquals(-1,map.resolve("minecraft:missing",Map.of()));
        var conflict=MaterialIdMap.parse(new PackProperties(Map.of("entity.1","minecraft:pig","entity.2","minecraft:pig")),"entity");
        assertThrows(IllegalArgumentException.class,()->conflict.resolve("minecraft:pig",Map.of()));
    }
}
