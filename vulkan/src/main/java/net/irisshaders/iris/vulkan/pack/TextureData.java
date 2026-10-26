package net.irisshaders.iris.vulkan.pack;

import com.google.gson.*;
import net.irisshaders.iris.vulkan.shader.ShaderPackSource;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.*;
import java.util.*;
import static net.irisshaders.iris.vulkan.pack.PackResourcePlan.*;

/** Decoded, tightly packed pixels; missing assets fail loading instead of substituting a texture. */
public record TextureData(Shape shape,TextureFormat format,int width,int height,int depth,int layers,boolean mipmaps,Sampler sampler,byte[] pixels) {
    @FunctionalInterface public interface ExternalResources { byte[] read(String identifier) throws IOException; }
    public TextureData {
        Objects.requireNonNull(pixels);Objects.requireNonNull(shape);Objects.requireNonNull(format);Objects.requireNonNull(sampler);
        long size=(long)width*height*depth*layers*format.bytes();
        if(width<1||height<1||depth<1||layers<1||size>256L*1024*1024||size!=pixels.length)throw new IllegalArgumentException("Texture extent/pixel byte count mismatch");
        if((shape==Shape.CUBE||shape==Shape.CUBE_ARRAY)&&(width!=height||layers%6!=0))throw new IllegalArgumentException("Cubemaps need square faces and six layers per cube");
        pixels=pixels.clone();
    }
    @Override public byte[] pixels(){return pixels.clone();}
    public static TextureData load(ShaderPackSource pack,String definition,PackProperties properties,String prefix,ExternalResources external) throws IOException {
        var tokens=definition.trim().split("\\s+");String path=tokens[0];byte[] bytes=read(pack,path,external);
        var metadata=new JsonObject();
        try {
            byte[] meta=read(pack,path+".mcmeta",external);
            var parsed=JsonParser.parseString(new String(meta,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if(parsed.has("texture"))metadata=parsed.getAsJsonObject("texture");
        }catch(java.nio.file.NoSuchFileException|FileNotFoundException ignored){}
        boolean blur=metadata.has("blur")&&metadata.get("blur").getAsBoolean(),clamp=metadata.has("clamp")&&metadata.get("clamp").getAsBoolean();
        boolean mips=metadata.has("mipmaps")&&metadata.get("mipmaps").getAsBoolean()||properties.get(prefix+".minFilter","").contains("MIPMAP");
        Wrap wrap=clamp?Wrap.CLAMP_TO_EDGE:Wrap.REPEAT;
        var sampler=PackResourcePlan.sampler(properties,prefix,new Sampler(wrap,wrap,wrap,!blur,false,false));
        if(tokens.length==1) {
            BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
            if(image==null)throw new IOException("Unsupported image file: "+path);
            long size=(long)image.getWidth()*image.getHeight()*4;if(size>256L*1024*1024)throw new IOException("Decoded texture too large: "+path);
            var output=new byte[(int)size];int p=0;
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){
                int argb=image.getRGB(x,y);output[p++]=(byte)(argb>>>16);output[p++]=(byte)(argb>>>8);output[p++]=(byte)argb;output[p++]=(byte)(argb>>>24);
            }
            return new TextureData(Shape.D2,TextureFormat.of("RGBA8"),image.getWidth(),image.getHeight(),1,1,mips,sampler,output);
        }
        Shape shape=switch(tokens[1].toUpperCase(Locale.ROOT)) {
            case "TEXTURE_1D"->Shape.D1;case "TEXTURE_2D"->Shape.D2;case "TEXTURE_3D"->Shape.D3;
            case "TEXTURE_CUBE_MAP","TEXTURE_CUBE"->Shape.CUBE;case "TEXTURE_1D_ARRAY"->Shape.D1_ARRAY;
            case "TEXTURE_2D_ARRAY"->Shape.D2_ARRAY;case "TEXTURE_CUBE_MAP_ARRAY"->Shape.CUBE_ARRAY;
            default->throw new IOException("Unsupported raw texture shape: "+tokens[1]);
        };
        int dimensions=switch(shape){case D1->1;case D2,D1_ARRAY,CUBE->2;default->3;};
        if(tokens.length!=5+dimensions)throw new IOException("Raw texture expects path type internalFormat dimensions pixelFormat pixelType: "+definition);
        int width=Integer.parseInt(tokens[3]),height=dimensions>1?Integer.parseInt(tokens[4]):1,depth=dimensions>2?Integer.parseInt(tokens[5]):1,layers=1;
        if(shape==Shape.D1_ARRAY){layers=height;height=1;}if(shape==Shape.D2_ARRAY||shape==Shape.CUBE_ARRAY){layers=depth;depth=1;}if(shape==Shape.CUBE)layers=6;
        var format=TextureFormat.of(tokens[2]);
        int count=Math.toIntExact((long)width*height*depth*layers);
        var converted=convert(bytes,count,tokens[tokens.length-2],tokens[tokens.length-1],format);
        if(format.integer())sampler=new Sampler(sampler.s(),sampler.t(),sampler.r(),true,true,true,sampler.compare());
        return new TextureData(shape,format,width,height,depth,layers,mips,sampler,converted);
    }
    private static byte[] read(ShaderPackSource pack,String path,ExternalResources external)throws IOException {
        if(path.contains(":")){if(external==null)throw new IOException("Minecraft resource resolver required for "+path);return external.read(path);}
        return pack.readBytes(path);
    }
    public static TextureData noise(int resolution) {
        if(resolution<1||resolution>8192)throw new IllegalArgumentException("Noise resolution "+resolution);
        byte[] rgb=new byte[Math.multiplyExact(Math.multiplyExact(resolution,resolution),3)];new Random(0).nextBytes(rgb);
        byte[] rgba=new byte[resolution*resolution*4];for(int i=0,j=0;i<rgb.length;i+=3,j+=4){rgba[j]=rgb[i];rgba[j+1]=rgb[i+1];rgba[j+2]=rgb[i+2];rgba[j+3]=(byte)255;}
        return new TextureData(Shape.D2,TextureFormat.of("RGBA8"),resolution,resolution,1,1,false,new Sampler(Wrap.REPEAT,Wrap.REPEAT,Wrap.REPEAT,false,false,false),rgba);
    }
    static byte[] convert(byte[] data,int pixels,String pixelFormat,String pixelType,TextureFormat target) {
        pixelFormat=pixelFormat.toUpperCase(Locale.ROOT);pixelType=pixelType.toUpperCase(Locale.ROOT);
        String packed=switch(pixelType){case "UNSIGNED_INT_10F_11F_11F_REV"->"R11F_G11F_B10F";case "UNSIGNED_INT_5_9_9_9_REV"->"RGB9_E5";case "UNSIGNED_INT_2_10_10_10_REV"->"RGB10_A2";case "UNSIGNED_SHORT_5_6_5"->"RGB565";case "UNSIGNED_SHORT_4_4_4_4"->"RGBA4";case "UNSIGNED_SHORT_5_5_5_1"->"RGB5_A1";default->null;};
        if(packed!=null){if(!target.name().equals(packed)||data.length!=(long)pixels*target.bytes())throw new IllegalArgumentException("Packed raw texture format/size mismatch");return data;}
        String channelOrder=pixelFormat.replace("_INTEGER","");if(channelOrder.equals("RED"))channelOrder="R";
        if(!Set.of("R","RG","RGB","RGBA","BGR","BGRA").contains(channelOrder))throw new IllegalArgumentException("Pixel format "+pixelFormat);
        boolean integer=pixelFormat.endsWith("_INTEGER");
        int bytesPerComponent=switch(pixelType){case "BYTE","UNSIGNED_BYTE"->1;case "SHORT","UNSIGNED_SHORT","HALF_FLOAT"->2;case "INT","UNSIGNED_INT","FLOAT"->4;default->throw new IllegalArgumentException("Pixel type "+pixelType);};
        if((long)pixels*channelOrder.length()*bytesPerComponent!=data.length)throw new IllegalArgumentException("Raw texture byte count mismatch");
        var input=ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());var out=ByteBuffer.allocate(Math.multiplyExact(pixels,target.bytes())).order(ByteOrder.nativeOrder());
        for(int p=0;p<pixels;p++) {
            double[] components={0,0,0,1};
            for(char channel:channelOrder.toCharArray()) {
                double value=switch(pixelType){case "BYTE"->input.get();case "UNSIGNED_BYTE"->Byte.toUnsignedInt(input.get());case "SHORT"->input.getShort();case "UNSIGNED_SHORT"->Short.toUnsignedInt(input.getShort());case "INT"->input.getInt();case "UNSIGNED_INT"->Integer.toUnsignedLong(input.getInt());case "HALF_FLOAT"->Float.float16ToFloat(input.getShort());case "FLOAT"->input.getFloat();default->throw new AssertionError();};
                if(!integer&&!pixelType.endsWith("FLOAT")){double max=switch(pixelType){case "BYTE"->127;case "UNSIGNED_BYTE"->255;case "SHORT"->32767;case "UNSIGNED_SHORT"->65535;case "INT"->2147483647;default->4294967295.0;};value=Math.max(-1,value/max);}
                components["RGBA".indexOf(channel)]=value;
            }
            if(target.bits()==0){writePacked(out,target,components);continue;}
            for(int c=0;c<target.channels();c++) {
                double value=components[c];if(target.encoding().equals("SFLOAT")){if(target.bits()==16)out.putShort(Float.floatToFloat16((float)value));else out.putFloat((float)value);continue;}
                if(target.encoding().equals("UNORM")||target.encoding().equals("SRGB"))value=Math.round(Math.clamp(value,0,1)*((1L<<target.bits())-1));
                else if(target.encoding().equals("SNORM"))value=Math.round(Math.clamp(value,-1,1)*((1L<<(target.bits()-1))-1));
                long v=(long)value;switch(target.bits()){case 8->out.put((byte)v);case 16->out.putShort((short)v);case 32->out.putInt((int)v);default->throw new AssertionError();}
            }
        }
        return out.array();
    }
    private static void writePacked(ByteBuffer out,TextureFormat format,double[] c) {
        switch(format.name()) {
            case "RGB10_A2","RGB10_A2UI" -> {boolean integer=format.integer();int value=0;for(int i=0;i<4;i++){int bits=i==3?2:10,shift=i*10;int v=integer?(int)c[i]:(int)Math.round(Math.clamp(c[i],0,1)*((1<<bits)-1));value|=(v&((1<<bits)-1))<<shift;}out.putInt(value);}
            case "RGBA4" -> out.putShort((short)(unorm(c[0],4)<<12|unorm(c[1],4)<<8|unorm(c[2],4)<<4|unorm(c[3],4)));
            case "RGB5_A1" -> out.putShort((short)(unorm(c[0],5)<<11|unorm(c[1],5)<<6|unorm(c[2],5)<<1|unorm(c[3],1)));
            case "RGB565" -> out.putShort((short)(unorm(c[0],5)<<11|unorm(c[1],6)<<5|unorm(c[2],5)));
            case "R11F_G11F_B10F" -> out.putInt(ufloat(c[0],6)|ufloat(c[1],6)<<11|ufloat(c[2],5)<<22);
            case "RGB9_E5" -> {double max=Math.clamp(Math.max(c[0],Math.max(c[1],c[2])),0,65408);int exponent=Math.max(-16,Math.getExponent(max))+1+15;double scale=Math.scalb(1.0,exponent-24);if(Math.round(max/scale)==512){exponent++;scale*=2;}int v=exponent<<27;for(int i=0;i<3;i++)v|=((int)Math.round(Math.clamp(c[i],0,65408)/scale)&511)<<(9*i);out.putInt(v);}
            default -> throw new IllegalArgumentException("Packed pixel conversion: "+format.name());
        }
    }
    private static int unorm(double value,int bits){return (int)Math.round(Math.clamp(value,0,1)*((1<<bits)-1));}
    private static int ufloat(double value,int mantissa){int half=Short.toUnsignedInt(Float.floatToFloat16((float)Math.max(0,value)));int shift=10-mantissa;return Math.min((half+(1<<(shift-1)))>>shift,(31<<mantissa));}
}
