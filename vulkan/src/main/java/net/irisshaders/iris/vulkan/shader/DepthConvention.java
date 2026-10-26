package net.irisshaders.iris.vulkan.shader;

/** Column-major projection conversions; callers must explicitly select the host depth convention. */
public final class DepthConvention {
    private DepthConvention() {}
    /** RenderPearl uses reversed zero-to-one depth. Pack depth textures must also be converted to 1-depth. */
    public static float[] reversedProjectionToOpenGl(float[] projection) {
        requireMatrix(projection);var result=projection.clone();
        for(int column=0;column<4;column++)result[column*4+2]=projection[column*4+3]-2*projection[column*4+2];
        return result;
    }
    public static float[] reversedInverseProjectionToOpenGl(float[] inverse) {
        requireMatrix(inverse);var result=inverse.clone();
        for(int row=0;row<4;row++){
            result[8+row]=-inverse[8+row]*.5f;
            result[12+row]=inverse[12+row]+inverse[8+row]*.5f;
        }
        return result;
    }
    public static float[] projectionToOpenGl(float[] zeroToOneProjection) {
        requireMatrix(zeroToOneProjection);var result=zeroToOneProjection.clone();
        // P_gl = inverse(C) * P_vk; inverse(C) maps z to 2z-w.
        for(int column=0;column<4;column++)result[column*4+2]=2*zeroToOneProjection[column*4+2]-zeroToOneProjection[column*4+3];
        return result;
    }
    public static float[] inverseProjectionToOpenGl(float[] zeroToOneInverse) {
        requireMatrix(zeroToOneInverse);var result=zeroToOneInverse.clone();
        // inverse(P_gl) = inverse(P_vk) * C, where C maps z to (z+w)/2.
        for(int row=0;row<4;row++){
            result[8+row]=zeroToOneInverse[8+row]*0.5f;
            result[12+row]=zeroToOneInverse[12+row]+zeroToOneInverse[8+row]*0.5f;
        }
        return result;
    }
    private static void requireMatrix(float[] matrix){if(matrix.length!=16)throw new IllegalArgumentException("Expected a column-major mat4");}
}
