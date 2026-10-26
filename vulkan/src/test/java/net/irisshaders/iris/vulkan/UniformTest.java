package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.vulkan.pack.PackProperties;
import net.irisshaders.iris.vulkan.shader.*;
import net.irisshaders.iris.vulkan.uniform.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UniformTest {
    @Test void smoothingRejectsNonFiniteTimingWithoutPoisoningHistory() {
        var smoother=new HalfLifeSmoother();
        assertEquals(0,smoother.sample(0,0,0,1,1));
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->smoother.sample(1,bad,1,1,1));
            assertThrows(IllegalArgumentException.class,()->smoother.sample(1,1,1,bad,1));
            assertThrows(IllegalArgumentException.class,()->smoother.sample(1,1,1,1,bad));
        }
        assertEquals(.5,smoother.sample(1,1,1,1,1),1e-9);
    }
    private static UniformSnapshot frame(long index,double seconds,double rain) {
        return new UniformSnapshot(index,seconds,Map.of("rainStrength",UniformValue.scalar(rain),
            "frameCounter",UniformValue.integer((int)index),"eyeBrightness",UniformValue.vector(32,240)));
    }
    @Test void customDependenciesVectorsAndLazyBranches() {
        var custom=new CustomUniforms(new PackProperties(Map.of(
            "variable.float.sky","eyeBrightness.y / 240",
            "uniform.vec3.result","vec3(sky, frameCounter % 8, if(rainStrength > 0, absent, 0))",
            "uniform.bool.check","false && absent || in(frameCounter, 1, 2, 3)")),Map.of());
        var result=custom.evaluate(frame(3,.1,0));
        assertArrayEquals(new double[]{1,3,0},result.require("result").elements());
        assertTrue(result.require("check").truth());
        assertFalse(result.values().containsKey("sky"));
    }
    @Test void smoothingUsesDecisecondsAndIndependentCallSites() {
        var custom=new CustomUniforms(new PackProperties(Map.of(
            "uniform.float.rain","smooth(1, rainStrength, 3, 6)",
            "uniform.float.fast","smooth(1, rainStrength, 1)",
            "uniform.float.noId","smooth(rainStrength, 3.0)")),Map.of());
        custom.evaluate(frame(0,0,0));
        var next=custom.evaluate(frame(1,.3,1));
        assertEquals(.5,next.require("rain").scalar(),1e-9);
        assertEquals(.875,next.require("fast").scalar(),1e-9);
        assertEquals(.5,next.require("noId").scalar(),1e-9);
        assertEquals(.5,custom.evaluate(frame(1,.3,0)).require("rain").scalar(),1e-9);
        assertEquals(.25,custom.evaluate(frame(2,.6,0)).require("rain").scalar(),1e-9);
    }
    @Test void missingProvidersAndCyclesFailWithNames() {
        var absent=new CustomUniforms(new PackProperties(Map.of("uniform.float.result","missing + 1")),Map.of());
        assertTrue(assertThrows(IllegalArgumentException.class,()->absent.evaluate(frame(0,0,0))).getMessage().contains("missing"));
        var cycle=new CustomUniforms(new PackProperties(Map.of("uniform.float.a","b","variable.float.b","a")),Map.of());
        assertTrue(assertThrows(IllegalArgumentException.class,()->cycle.evaluate(frame(0,0,0))).getMessage().contains("cycle"));
    }
    @Test void snapshotPacksRealValuesWithStd140MatrixPadding() {
        var layout=Std140Layout.pack(List.of(new Std140Layout.Field("matrix",Std140Layout.Type.of("mat3"),0),
            new Std140Layout.Field("enabled",Std140Layout.Type.of("bool"),0)));
        double[] matrix={1,2,3,4,5,6,7,8,9};
        var snapshot=new UniformSnapshot(0,0,Map.of("matrix",new UniformValue("mat3",matrix),"enabled",UniformValue.bool(true)));
        matrix[0]=999;
        var bytes=snapshot.pack(layout);
        assertEquals(1,bytes.getInt(layout.member("enabled").offset()));
        int offset=layout.member("matrix").offset();
        assertEquals(1,bytes.getFloat(offset));assertEquals(4,bytes.getFloat(offset+16));assertEquals(9,bytes.getFloat(offset+40));
        assertThrows(IllegalArgumentException.class,()->frame(0,0,0).pack(layout));
    }
    @Test void reversedDepthProjectionReconstructsNearMiddleAndFar() {
        // Perspective with near=.1 and far=100, matching RenderPearl's reversed-Z matrix.
        float n=.1f,f=100;
        float a=n/(f-n),b=f*n/(f-n);
        float[] nativeP={1,0,0,0,0,1,0,0,0,0,a,-1,0,0,b,0};
        float[] nativeInverse={1,0,0,0,0,1,0,0,0,0,0,1/b,0,0,-1,a/b};
        var packP=DepthConvention.reversedProjectionToOpenGl(nativeP);
        var packInverse=DepthConvention.reversedInverseProjectionToOpenGl(nativeInverse);
        for(float distance:new float[]{n,1,10,f}) {
            float z=-distance;
            float nativeDepth=(a*z+b)/-z;
            float packDepth=1-nativeDepth;
            assertEquals(packDepth*2-1,(packP[10]*z+packP[14])/-z,2e-6);
            float ndc=packDepth*2-1;
            float reconstructed=(packInverse[10]*ndc+packInverse[14])/(packInverse[11]*ndc+packInverse[15]);
            assertEquals(z,reconstructed,Math.max(.00001,distance*.0001));
        }
        assertEquals(a,nativeP[10]);
    }
}
