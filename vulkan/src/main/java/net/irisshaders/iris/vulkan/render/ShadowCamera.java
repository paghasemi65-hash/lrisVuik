package net.irisshaders.iris.vulkan.render;

import net.irisshaders.iris.vulkan.pack.PackExpression;
import org.joml.*;
import java.lang.Math;
import java.util.Map;

/** Camera-relative OpenGL-space shadow uniforms; the shader vertex epilogue converts clip Z. */
public final class ShadowCamera {
    public record Settings(float distance, float interval, int resolution, float near, float far) {
        public Settings {
            if (!Float.isFinite(distance) || distance <= 0 || !Float.isFinite(interval) || interval < 0
                || resolution < 1 || !Float.isFinite(near) || !Float.isFinite(far) || near >= far)
                throw new IllegalArgumentException("Invalid orthographic shadow settings");
        }
        public static Settings from(Map<String,String> constants) {
            double resolution = number(constants,"shadowMapResolution",1024);
            if (resolution != Math.rint(resolution) || resolution > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid shadow map resolution: " + resolution);
            if (constants.containsKey("shadowMapFov"))
                throw new IllegalArgumentException("Perspective shadowMapFov is not supported by the orthographic shadow camera");
            return new Settings((float)number(constants,"shadowDistance",160),
                (float)number(constants,"shadowIntervalSize",2),(int)resolution,
                (float)number(constants,"shadowNearPlane",-100.05),(float)number(constants,"shadowFarPlane",156));
        }
        private static double number(Map<String,String> constants,String key,double fallback) {
            return constants.containsKey(key) ? PackExpression.evaluate(constants.get(key),constants) : fallback;
        }
    }
    private final Matrix4f view,projection;
    private final FrustumIntersection frustum;
    private final Vector3d camera;
    private final double cullDistance;
    private final float texelSize;

    /** lightDirection points from the world toward the live sun/moon, including sunPathRotation. */
    public ShadowCamera(Settings settings, Vector3dc cameraPosition, Vector3fc lightDirection, double renderDistance) {
        if (!cameraPosition.isFinite() || !lightDirection.isFinite() || lightDirection.lengthSquared() < 1e-12f
            || !Double.isFinite(renderDistance) || renderDistance <= 0)
            throw new IllegalArgumentException("Invalid live shadow camera input");
        camera = new Vector3d(cameraPosition);
        cullDistance = Math.min(settings.distance,renderDistance);
        // Keep pack projection extent fixed when changing view distance; clamp only caster selection.
        texelSize = 2 * settings.distance / settings.resolution;
        var light = new Vector3f(lightDirection).normalize();
        var up = Math.abs(light.z) > .999f ? new Vector3f(0,1,0) : new Vector3f(0,0,1);
        view = new Matrix4f().lookAlong(new Vector3f(light).negate(),up);
        // Work in double precision before subtracting the camera to avoid far-world float jitter.
        var anchor = new Vector3d(camera);
        if (settings.interval > 0) {
            anchor.set(snap(anchor.x,settings.interval),snap(anchor.y,settings.interval),snap(anchor.z,settings.interval));
        }
        var rotation = new Matrix3d(view);
        var lightAnchor = rotation.transform(new Vector3d(anchor));
        lightAnchor.x = snap(lightAnchor.x,texelSize);
        lightAnchor.y = snap(lightAnchor.y,texelSize);
        var lightCamera = rotation.transform(new Vector3d(camera));
        view.setTranslation((float)(lightCamera.x-lightAnchor.x),(float)(lightCamera.y-lightAnchor.y),
            (float)(lightCamera.z-lightAnchor.z));
        projection = new Matrix4f().setOrtho(-settings.distance,settings.distance,-settings.distance,settings.distance,
            settings.near,settings.far,false);
        frustum = new FrustumIntersection(new Matrix4f(projection).mul(view));
    }
    private static double snap(double value,double grid) { return Math.floor(value/grid+.5)*grid; }
    public Matrix4f view() { return new Matrix4f(view); }
    public Matrix4f projection() { return new Matrix4f(projection); }
    public double cullDistance() { return cullDistance; }
    public float texelSize() { return texelSize; }

    /** World-space AABB selection independent of the player's camera frustum. */
    public boolean intersects(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
            || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
            || minX>maxX || minY>maxY || minZ>maxZ) throw new IllegalArgumentException("Invalid caster bounds");
        double dx=Math.max(Math.max(minX-camera.x,camera.x-maxX),0);
        double dz=Math.max(Math.max(minZ-camera.z,camera.z-maxZ),0);
        if (dx*dx+dz*dz > cullDistance*cullDistance) return false;
        return frustum.testAab((float)(minX-camera.x),(float)(minY-camera.y),(float)(minZ-camera.z),
            (float)(maxX-camera.x),(float)(maxY-camera.y),(float)(maxZ-camera.z));
    }
}
