package net.irisshaders.iris.vulkan.render;

/** Geometry-derived tangent frames. UV degeneracy uses an orthogonal basis of the actual face normal. */
public final class QuadVertexData {
    public record Vertex(float x,float y,float z,float u,float v) {}
    public record Basis(float u,float v,float nx,float ny,float nz,float tx,float ty,float tz,float handedness) {}
    private QuadVertexData() {}
    public static Basis compute(Vertex a,Vertex b,Vertex c,Vertex d) {
        double[] e1={b.x-a.x,b.y-a.y,b.z-a.z},e2={c.x-a.x,c.y-a.y,c.z-a.z};
        double[] normal=cross(e1,e2);
        if(length(normal)<1e-12)normal=cross(e2,new double[]{d.x-a.x,d.y-a.y,d.z-a.z});
        if(length(normal)<1e-12)return new Basis((a.u+b.u+c.u+d.u)*.25f,(a.v+b.v+c.v+d.v)*.25f,0,0,0,0,0,0,1);
        normalize(normal);
        double du1=b.u-a.u,dv1=b.v-a.v,du2=c.u-a.u,dv2=c.v-a.v,det=du1*dv2-du2*dv1;
        double[] tangent=new double[3],bitangent=new double[3];
        if(Math.abs(det)>1e-12)for(int i=0;i<3;i++){tangent[i]=(e1[i]*dv2-e2[i]*dv1)/det;bitangent[i]=(e2[i]*du1-e1[i]*du2)/det;}
        double projection=dot(normal,tangent);for(int i=0;i<3;i++)tangent[i]-=normal[i]*projection;
        if(length(tangent)<1e-12)tangent=cross(Math.abs(normal[0])<.8?new double[]{1,0,0}:new double[]{0,1,0},normal);
        normalize(tangent);
        float sign=dot(cross(normal,tangent),bitangent)<0?-1:1;
        return new Basis((a.u+b.u+c.u+d.u)*.25f,(a.v+b.v+c.v+d.v)*.25f,(float)normal[0],(float)normal[1],(float)normal[2],(float)tangent[0],(float)tangent[1],(float)tangent[2],sign);
    }
    /** Matches Iris's signed-byte mid-block representation, including truncation and two's-complement wrapping. */
    public static byte midBlock(float center,float position){return (byte)(int)((center-position)*64);}
    private static double dot(double[] a,double[] b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];}
    private static double length(double[] a){return Math.sqrt(dot(a,a));}
    private static void normalize(double[] a){double n=length(a);for(int i=0;i<3;i++)a[i]/=n;}
    private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
}
