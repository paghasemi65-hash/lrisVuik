package net.irisshaders.iris.vulkan.uniform;

/** Stateful, asymmetric exponential smoothing, advanced once per captured frame. */
public final class HalfLifeSmoother {
    private long frame=Long.MIN_VALUE;
    private double value;
    public double sample(long nextFrame,double seconds,double target,double upSeconds,double downSeconds){
        if(!Double.isFinite(target)||!Double.isFinite(seconds)||!Double.isFinite(upSeconds)||!Double.isFinite(downSeconds)
            ||seconds<0||upSeconds<0||downSeconds<0)throw new IllegalArgumentException("Invalid smoothing input");
        if(nextFrame==frame)return value;
        if(frame==Long.MIN_VALUE||nextFrame<frame)value=target;
        else {double half=target>value?upSeconds:downSeconds;value=half==0?target:target+(value-target)*Math.exp(-Math.log(2)*seconds/half);}
        frame=nextFrame;return value;
    }
}
