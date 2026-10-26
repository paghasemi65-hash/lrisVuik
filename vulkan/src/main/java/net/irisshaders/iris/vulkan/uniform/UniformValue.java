package net.irisshaders.iris.vulkan.uniform;

import java.util.*;
import net.irisshaders.iris.vulkan.shader.Std140Layout;

/** Immutable numeric value. Missing game inputs are errors, never implicit zero-filled uniforms. */
public record UniformValue(String type,double[] elements) {
    public UniformValue { Objects.requireNonNull(type);elements=elements.clone();for(double value:elements)if(!Double.isFinite(value))throw new IllegalArgumentException("Non-finite uniform "+type); }
    @Override public double[] elements(){return elements.clone();}
    public static UniformValue scalar(double value){return new UniformValue("float",new double[]{value});}
    public static UniformValue integer(int value){return new UniformValue("int",new double[]{value});}
    public static UniformValue bool(boolean value){return new UniformValue("bool",new double[]{value?1:0});}
    public static UniformValue vector(double... values){return new UniformValue("vec"+values.length,values);}
    public static UniformValue floats(String type,float... values){double[] result=new double[values.length];for(int i=0;i<values.length;i++)result[i]=values[i];return new UniformValue(type,result);}
    public double scalar(){if(elements.length!=1)throw new IllegalArgumentException("Expected scalar, got "+type);return elements[0];}
    public boolean truth(){return scalar()!=0;}
    public UniformValue cast(String target){
        var shape=Std140Layout.Type.of(target);if(elements.length!=shape.rows()*shape.columns())throw new IllegalArgumentException("Cannot convert "+type+" to "+target);
        double[] result=elements.clone();boolean integral=target.equals("int")||target.equals("uint")||target.startsWith("ivec")||target.startsWith("uvec");
        boolean bool=target.equals("bool")||target.startsWith("bvec");for(int i=0;i<result.length;i++)if(integral)result[i]=(long)result[i];else if(bool)result[i]=result[i]==0?0:1;
        return new UniformValue(target,result);
    }
    public UniformValue swizzle(String fields){double[] result=new double[fields.length()];for(int i=0;i<result.length;i++){int component="xyzw".indexOf(fields.charAt(i));if(component<0)component="rgba".indexOf(fields.charAt(i));if(component<0||component>=elements.length)throw new IllegalArgumentException("Invalid swizzle "+fields+" for "+type);result[i]=elements[component];}return result.length==1?scalar(result[0]):vector(result);}
}
