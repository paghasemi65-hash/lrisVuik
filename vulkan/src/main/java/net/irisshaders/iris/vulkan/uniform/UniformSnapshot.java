package net.irisshaders.iris.vulkan.uniform;

import java.nio.*;
import java.util.*;
import net.irisshaders.iris.vulkan.shader.Std140Layout;

/** One captured frame or draw; its bytes remain independent of subsequent camera/object changes. */
public record UniformSnapshot(long frame,double deltaSeconds,Map<String,UniformValue> values) {
    public UniformSnapshot {if(!Double.isFinite(deltaSeconds)||deltaSeconds<0)throw new IllegalArgumentException("Frame delta");values=Map.copyOf(values);}
    public UniformValue require(String name){var value=values.get(name);if(value==null)throw new IllegalArgumentException("No live provider for uniform "+name);return value;}
    public UniformSnapshot overlay(Map<String,UniformValue> perDraw){var combined=new HashMap<>(values);combined.putAll(perDraw);return new UniformSnapshot(frame,deltaSeconds,combined);}
    public ByteBuffer pack(Std140Layout layout){
        var data=ByteBuffer.allocate(layout.size()).order(ByteOrder.nativeOrder());
        for(var member:layout.members()){
            var field=member.field();var value=require(field.name());var values=value.elements();
            String type=field.type().glsl();int expected=field.type().columns()*field.type().rows()*Math.max(1,field.arrayLength());
            if(values.length!=expected)throw new IllegalArgumentException("Uniform "+field.name()+" requires "+expected+" components, supplied "+values.length);
            if(type.equals("float")||type.startsWith("vec")||type.startsWith("mat")){float[] floats=new float[values.length];for(int i=0;i<values.length;i++)floats[i]=(float)values[i];layout.putFloats(data,field.name(),floats);}
            else {int[] ints=new int[values.length];for(int i=0;i<values.length;i++)ints[i]=type.equals("bool")||type.startsWith("bvec")?(values[i]==0?0:1):(int)(long)values[i];layout.putInts(data,field.name(),ints);}
        }
        return data;
    }
}
