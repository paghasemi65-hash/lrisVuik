package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.vulkan.render.ShadowCamera;
import org.joml.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ShadowCameraTest {
    private static final ShadowCamera.Settings SETTINGS=new ShadowCamera.Settings(64,2,1024,-100,156);
    @Test void lightDirectionAndDepthEndpoints() {
        var camera=new ShadowCamera(SETTINGS,new Vector3d(),new Vector3f(.3f,.9f,.2f),256);
        var direction=camera.view().transformDirection(new Vector3f(.3f,.9f,.2f).normalize());
        assertEquals(0,direction.x,1e-6);assertEquals(0,direction.y,1e-6);assertEquals(1,direction.z,1e-6);
        assertEquals(-1,camera.projection().transformPosition(new Vector3f(0,0,100)).z,1e-6);
        assertEquals(1,camera.projection().transformPosition(new Vector3f(0,0,-156)).z,1e-6);
        assertTrue(camera.view().mul(camera.view().invert()).equals(new Matrix4f(),1e-5f));
    }
    @Test void stableWorldTexelsAcrossCameraMotionAndNegativeCoordinates() {
        for(double origin:new double[]{100,-100,29_000_000,-29_000_000}) {
            var a=new Vector3d(origin+.1,70.1,origin+.1);var b=new Vector3d(a).add(.01,.01,.01);
            var first=new ShadowCamera(SETTINGS,a,new Vector3f(.2f,1,.1f),128);
            var second=new ShadowCamera(SETTINGS,b,new Vector3f(.2f,1,.1f),128);
            var worldPoint=new Vector3d(a).add(5,2,3);
            var p=first.view().transformPosition(new Vector3f(new Vector3d(worldPoint).sub(a)));
            var q=second.view().transformPosition(new Vector3f(new Vector3d(worldPoint).sub(b)));
            assertEquals(p.x,q.x,1e-5);assertEquals(p.y,q.y,1e-5);assertEquals(p.z,q.z,1e-5);
        }
    }
    @Test void casterCullingUsesLightFrustumAndClampedWorldDistance() {
        var camera=new ShadowCamera(SETTINGS,new Vector3d(),new Vector3f(0,1,0),32);
        assertEquals(32,camera.cullDistance());
        assertTrue(camera.intersects(-1,10,-1,1,12,1));
        assertFalse(camera.intersects(40,0,0,42,2,2));
        assertFalse(camera.intersects(-1,1000,-1,1,1002,1));
        assertEquals(1f/64,camera.projection().m00());
        assertTrue(new ShadowCamera(SETTINGS,new Vector3d(),new Vector3f(0,0,1),32).view().isFinite());
    }
    @Test void settingsResolvePackExpressionsAndRejectInvalidProjection() {
        var s=ShadowCamera.Settings.from(Map.of("shadowDistance","DIST * 2.0f","DIST","48","shadowMapResolution","2048"));
        assertEquals(96,s.distance());assertEquals(2048,s.resolution());
        assertThrows(IllegalArgumentException.class,()->ShadowCamera.Settings.from(Map.of("shadowMapResolution","1.5")));
        assertThrows(IllegalArgumentException.class,()->ShadowCamera.Settings.from(Map.of("shadowIntervalSize","-1")));
        assertThrows(IllegalArgumentException.class,()->ShadowCamera.Settings.from(Map.of("shadowMapFov","90")));
    }
}
