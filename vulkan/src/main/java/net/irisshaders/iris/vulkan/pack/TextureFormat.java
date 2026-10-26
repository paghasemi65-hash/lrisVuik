package net.irisshaders.iris.vulkan.pack;

import java.util.*;
import java.util.regex.Pattern;
import static org.lwjgl.vulkan.VK13.*;

/** Exact Vulkan formats. A missing or unsupported native format is an error, never an RGBA8 substitution. */
public record TextureFormat(String name,int vk,int channels,int bits,String encoding,int bytes) {
    // Shader language metadata tokens, verified against LWJGL's registry constants. No GL calls or dependency.
    private static final Map<String,Integer> PACK_TOKENS;
    private static final Map<Integer,Integer> TEXEL_BYTES;
    static {
        var tokens=new HashMap<String,Integer>();
        String[] groups={
            "33321:R8,R16,RG8,RG16,R16F,R32F,RG16F,RG32F,R8I,R8UI,R16I,R16UI,R32I,R32UI,RG8I,RG8UI,RG16I,RG16UI,RG32I,RG32UI",
            "36756:R8_SNORM,RG8_SNORM,RGB8_SNORM,RGBA8_SNORM,R16_SNORM,RG16_SNORM,RGB16_SNORM,RGBA16_SNORM",
            "34836:RGBA32F,RGB32F","34842:RGBA16F,RGB16F","36208:RGBA32UI,RGB32UI","36214:RGBA16UI,RGB16UI",
            "36220:RGBA8UI,RGB8UI","36226:RGBA32I,RGB32I","36232:RGBA16I,RGB16I","36238:RGBA8I,RGB8I",
            "32849:RGB8","32852:RGB16","32854:RGBA4,RGB5_A1,RGBA8,RGB10_A2","32859:RGBA16",
            "35898:R11F_G11F_B10F","35901:RGB9_E5","36194:RGB565","36975:RGB10_A2UI","35905:RGB8_SRGB","35907:RGBA8_SRGB"
        };
        for(String group:groups){var parts=group.split(":");int value=Integer.parseInt(parts[0]);for(String name:parts[1].split(","))tokens.put(name,value++);}
        PACK_TOKENS=Map.copyOf(tokens);
        var sizes=new HashMap<Integer,Integer>();
        for(String name:PACK_TOKENS.keySet()){var format=of(name);sizes.put(format.vk(),format.bytes());}
        sizes.put(VK_FORMAT_D16_UNORM,2);sizes.put(VK_FORMAT_D32_SFLOAT,4);
        sizes.put(VK_FORMAT_B8G8R8A8_UNORM,4);sizes.put(VK_FORMAT_B8G8R8A8_SRGB,4);
        TEXEL_BYTES=Map.copyOf(sizes);
    }
    public static int bytesPerTexel(int format){
        var bytes=TEXEL_BYTES.get(format);
        if(bytes==null)throw new IllegalArgumentException("Unsupported upload format: "+format);
        return bytes;
    }
    public int shaderToken(){var value=PACK_TOKENS.get(name);if(value==null)throw new IllegalArgumentException("No shader metadata token for "+name);return value;}
    public boolean integer(){return encoding.equals("SINT")||encoding.equals("UINT");}
    public boolean depth(){return name.startsWith("DEPTH_");}
    public static boolean integerVk(int format){return switch(format){
        case VK_FORMAT_R8_UINT,VK_FORMAT_R8_SINT,VK_FORMAT_R8G8_UINT,VK_FORMAT_R8G8_SINT,VK_FORMAT_R8G8B8_UINT,VK_FORMAT_R8G8B8_SINT,VK_FORMAT_R8G8B8A8_UINT,VK_FORMAT_R8G8B8A8_SINT,
             VK_FORMAT_R16_UINT,VK_FORMAT_R16_SINT,VK_FORMAT_R16G16_UINT,VK_FORMAT_R16G16_SINT,VK_FORMAT_R16G16B16_UINT,VK_FORMAT_R16G16B16_SINT,VK_FORMAT_R16G16B16A16_UINT,VK_FORMAT_R16G16B16A16_SINT,
             VK_FORMAT_R32_UINT,VK_FORMAT_R32_SINT,VK_FORMAT_R32G32_UINT,VK_FORMAT_R32G32_SINT,VK_FORMAT_R32G32B32_UINT,VK_FORMAT_R32G32B32_SINT,VK_FORMAT_R32G32B32A32_UINT,VK_FORMAT_R32G32B32A32_SINT,
             VK_FORMAT_A2B10G10R10_UINT_PACK32 -> true;default -> false;};}
    public static TextureFormat of(String original) {
        String name=original.toUpperCase(Locale.ROOT);if(name.equals("RGBA"))name="RGBA8";
        if(name.matches("[0-9]+|0X[0-9A-F]+")){int token=Integer.decode(name);name=PACK_TOKENS.entrySet().stream().filter(e->e.getValue()==token).map(Map.Entry::getKey).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown format metadata token "+original));}
        if(name.equals("DEPTH_COMPONENT32F"))return new TextureFormat(name,VK_FORMAT_D32_SFLOAT,1,32,"SFLOAT",4);
        if(name.equals("DEPTH_COMPONENT16"))return new TextureFormat(name,VK_FORMAT_D16_UNORM,1,16,"UNORM",2);
        var special=switch(name){
            case "R11F_G11F_B10F"->new TextureFormat(name,VK_FORMAT_B10G11R11_UFLOAT_PACK32,3,0,"PACKED_FLOAT",4);
            case "RGB10_A2"->new TextureFormat(name,VK_FORMAT_A2B10G10R10_UNORM_PACK32,4,0,"PACKED",4);
            case "RGB10_A2UI"->new TextureFormat(name,VK_FORMAT_A2B10G10R10_UINT_PACK32,4,0,"UINT",4);
            case "RGBA4"->new TextureFormat(name,VK_FORMAT_R4G4B4A4_UNORM_PACK16,4,0,"PACKED",2);
            case "RGB5_A1"->new TextureFormat(name,VK_FORMAT_R5G5B5A1_UNORM_PACK16,4,0,"PACKED",2);
            case "RGB565"->new TextureFormat(name,VK_FORMAT_R5G6B5_UNORM_PACK16,3,0,"PACKED",2);
            case "RGB9_E5"->new TextureFormat(name,VK_FORMAT_E5B9G9R9_UFLOAT_PACK32,3,0,"PACKED_FLOAT",4);
            default->null;
        };if(special!=null)return special;
        var m=Pattern.compile("(R|RG|RGB|RGBA)(8|16|32)(F|UI|I|_SNORM|_SRGB)?").matcher(name);
        if(!m.matches())throw new IllegalArgumentException("No exact Vulkan format for "+original);
        int bits=Integer.parseInt(m.group(2)),channels=m.group(1).length();String suffix=Objects.requireNonNullElse(m.group(3),"");
        String encoding=switch(suffix){case "F"->"SFLOAT";case "UI"->"UINT";case "I"->"SINT";case "_SNORM"->"SNORM";case "_SRGB"->"SRGB";default->"UNORM";};
        var vkName=new StringBuilder("VK_FORMAT_");for(char c:m.group(1).toCharArray())vkName.append(c).append(bits);vkName.append('_').append(encoding);
        try{return new TextureFormat(name,org.lwjgl.vulkan.VK10.class.getField(vkName.toString()).getInt(null),channels,bits,encoding,channels*bits/8);}
        catch(ReflectiveOperationException e){throw new IllegalArgumentException("No exact Vulkan format for "+original,e);}
    }
}
