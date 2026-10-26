package net.irisshaders.iris.vulkan.pack;

import net.irisshaders.iris.vulkan.shader.*;
import java.util.*;
import java.util.regex.*;

public record PackResourcePlan(Map<String,Target> targets, Map<String,Pass> passes, Map<String,String> customTextures,
                               Map<String,String> aliases, int noiseResolution, Map<String,String> constants, PackProperties properties) {
    public enum Shape { D1, D2, D3, CUBE, D1_ARRAY, D2_ARRAY, CUBE_ARRAY }
    public enum Wrap { REPEAT, CLAMP_TO_EDGE, MIRRORED_REPEAT, CLAMP_TO_BORDER }
    public record Sampler(Wrap s,Wrap t,Wrap r,boolean nearest,boolean magNearest,boolean mipNearest,boolean compare) {
        public Sampler(Wrap s,Wrap t,Wrap r,boolean nearest,boolean mipNearest,boolean compare){this(s,t,r,nearest,nearest,mipNearest,compare);}
        public static Sampler regular(){return new Sampler(Wrap.CLAMP_TO_EDGE,Wrap.CLAMP_TO_EDGE,Wrap.CLAMP_TO_EDGE,false,false,false);}
    }
    public record Size(String x,String y,String z,boolean relativeX,boolean relativeY,Shape shape) {
        public int[] resolve(int width,int height,Map<String,String> values) {
            var vars=new HashMap<>(values);vars.put("viewWidth",Integer.toString(width));vars.put("viewHeight",Integer.toString(height));
            return new int[]{positive(PackExpression.evaluate(x,vars)*(relativeX?width:1)),positive(PackExpression.evaluate(y,vars)*(relativeY?height:1)),positive(PackExpression.evaluate(z,vars))};
        }
        private static int positive(double value){if(!Double.isFinite(value)||value<1||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid image extent "+value);return (int)value;}
        public static Size screen(){return new Size("1","1","1",true,true,Shape.D2);}
    }
    public record Color(float r,float g,float b,float a) { public float[] array(){return new float[]{r,g,b,a};} }
    public record Target(String name,TextureFormat format,Size size,boolean storage,boolean pingPong,boolean clear,
                         Color clearColor,boolean fogClear,boolean mipmaps,Sampler sampler) {}
    public record Pass(List<Integer> outputs,Map<Integer,Boolean> flips,Set<String> mipmaps) {
        public Pass { outputs=List.copyOf(outputs);flips=Map.copyOf(flips);mipmaps=Set.copyOf(mipmaps); }
    }
    public PackResourcePlan {targets=Map.copyOf(targets);passes=Map.copyOf(passes);customTextures=Map.copyOf(customTextures);aliases=Map.copyOf(aliases);constants=Map.copyOf(constants);}
    public static final List<String> LEGACY=List.of("gcolor","gdepth","gnormal","composite","gaux1","gaux2","gaux3","gaux4");
    public static String canonical(String name) {
        int legacy=LEGACY.indexOf(name);if(legacy>=0)return "colortex"+legacy;
        if(name.matches("colorimg[0-5]"))return "colortex"+name.substring(8);
        return switch(name){case "shadow","watershadow"->"shadowtex0";case "shadowcolor"->"shadowcolor0";default->name;};
    }
    public static boolean standard(String name){return name.matches("colortex(?:[0-9]|1[0-5])|depthtex[0-2]|shadowtex[01]|shadowcolor[01]");}
    public static PackResourcePlan parse(Map<String,Map<ShaderStage,String>> programs,PackProperties properties,Map<String,String> options) {
        var constants=new LinkedHashMap<String,String>(options);var requested=new TreeSet<String>();var storage=new HashSet<String>();
        var passes=new LinkedHashMap<String,Pass>();var mipped=new HashSet<String>();var aliases=new HashMap<String,String>();
        var constPattern=Pattern.compile("\\bconst\\s+(?:int|float|bool|vec4|ivec3|vec3)\\s+(\\w+)\\s*=\\s*([^;]+);");
        var custom=new LinkedHashMap<String,String>();
        properties.all().forEach((key,value)->{
            if(key.equals("texture.noise"))custom.put("*.noisetex",value);
            else if(key.startsWith("customTexture."))custom.put("*."+key.substring(14),value);
            else if(key.startsWith("texture.")&&!key.matches(".*\\.(?:wrapS|wrapT|wrapR|minFilter|magFilter|mipmapFilter|compare)$"))custom.put(key.substring(8),value);
        });
        for(var program:programs.entrySet()) {
            var flips=new LinkedHashMap<Integer,Boolean>();var mipmaps=new HashSet<String>();
            for(String source:program.getValue().values()) {
                String code=source.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*", " ");
                var c=constPattern.matcher(code);while(c.find()){
                    String key=c.group(1);for(int i=0;i<LEGACY.size();i++)if(key.startsWith(LEGACY.get(i))){key="colortex"+i+key.substring(LEGACY.get(i).length());break;}
                    key=key.replaceFirst("^shadowColor","shadowcolor").replaceFirst("^(shadowcolor[01])MinMagNearest$","$1Nearest")
                        .replaceFirst("^shadow([01])MinMagNearest$","shadowtex$1Nearest").replaceFirst("^shadowtex(Mipmap|Nearest)$","shadowtex0$1");
                    constants.put(key,c.group(2).trim());
                    var resource=Pattern.compile("^(colortex(?:[0-9]|1[0-5])|shadowcolor[01]|shadowtex[01]|depthtex[0-2])(?:Format|Clear|clear|ClearColor|Mipmap|MipmapEnabled|Nearest)$").matcher(key);
                    if(resource.matches())requested.add(resource.group(1));
                    if(key.matches("colortex(?:[0-9]|1[0-5])MipmapEnabled")&&truth(c.group(2),constants)){String buffer=key.replace("MipmapEnabled","");mipmaps.add(buffer);mipped.add(buffer);}
                }
                var uniforms=Pattern.compile("\\buniform\\s+(?:(?:readonly|writeonly|coherent|volatile|restrict|highp|mediump|lowp)\\s+)*([iu]?(?:sampler|image)\\w+)\\s+([^;]+);").matcher(code);
                while(uniforms.find())for(String declaration:uniforms.group(2).split(",")) {
                    var identifier=Pattern.compile("^\\s*(\\w+)").matcher(declaration);if(!identifier.find())continue;
                    String original=identifier.group(1),name=canonical(original);
                    if(standard(name)){requested.add(name);aliases.put(original,name);if(uniforms.group(1).contains("image"))storage.add(name);}
                }
                var legacyFormat=Pattern.compile("/\\*\\s*GAUX4FORMAT\\s*:\\s*(\\w+)\\s*\\*/").matcher(source);
                if(legacyFormat.find()){constants.put("GAUX4FORMAT",legacyFormat.group(1));requested.add("colortex7");}
                var flip=Pattern.compile("/\\*\\s*Flip\\s*:\\s*(\\w+)\\s*:\\s*(true|false)\\s*\\*/",Pattern.CASE_INSENSITIVE).matcher(source);
                while(flip.find())flips.put(colorIndex(flip.group(1)),Boolean.parseBoolean(flip.group(2)));
            }
            String name=program.getKey();String directiveName=name.replaceFirst("\\.compute$","");String fragment=program.getValue().get(ShaderStage.FRAGMENT);
            List<Integer> outputs=fragment==null||name.equals("final")?List.of():ShaderPackSource.drawBuffers(fragment);
            for(int output:outputs)requested.add((name.startsWith("shadow")&&!name.startsWith("shadowcomp")?"shadowcolor":"colortex")+output);
            properties.all().forEach((key,value)->{String prefix="flip."+directiveName+".";if(key.startsWith(prefix))flips.put(colorIndex(key.substring(prefix.length())),truth(value,constants));});
            flips.keySet().forEach(index->requested.add("colortex"+index));
            String generate=properties.get("generateMipmap."+directiveName,"");for(String buffer:generate.split("\\s+"))if(!buffer.isEmpty()){String canonical=canonical(buffer);mipmaps.add(canonical);mipped.add(canonical);requested.add(canonical);}
            passes.put(name,new Pass(outputs,flips,mipmaps));
        }
        var targets=new LinkedHashMap<String,Target>();int shadowResolution=integer(constants.getOrDefault("shadowMapResolution","1024"),constants);
        for(String name:requested) {
            boolean depth=name.startsWith("depthtex")||name.startsWith("shadowtex"),shadow=name.startsWith("shadow");
            String format=constants.getOrDefault(name+"Format",depth?"DEPTH_COMPONENT32F":"RGBA8");
            if(name.equals("colortex1")&&aliases.containsKey("gdepth")&&!constants.containsKey(name+"Format"))format="RGBA32F";
            if(name.equals("colortex7")&&constants.containsKey("GAUX4FORMAT"))format=constants.get("GAUX4FORMAT");
            var textureFormat=TextureFormat.of(format);
            var size=shadow?new Size(""+shadowResolution,""+shadowResolution,"1",false,false,Shape.D2):Size.screen();
            String sizeText=properties.get("size.buffer."+name,null);if(sizeText!=null)size=parseSize(sizeText);
            boolean clear=truth(properties.get(name+"clear",constants.getOrDefault(name+"Clear",constants.getOrDefault(name+"clear","true"))),constants);
            Color color=constants.containsKey(name+"ClearColor")?color(constants.get(name+"ClearColor"),constants):name.equals("colortex1")?new Color(1,1,1,1):shadow?new Color(1,1,1,1):new Color(0,0,0,0);
            boolean mip=mipped.contains(name)||truth(constants.getOrDefault(name+"Mipmap",shadow?constants.getOrDefault(depth?"generateShadowMipmap":"generateShadowColorMipmap","false"):"false"),constants);
            boolean nearest=textureFormat.integer()||truth(constants.getOrDefault(name+"Nearest","false"),constants);
            boolean compare=shadow&&depth&&truth(constants.getOrDefault("shadowHardwareFiltering"+name.substring(9),constants.getOrDefault("shadowHardwareFiltering","false")),constants);
            targets.put(name,new Target(name,textureFormat,size,storage.contains(name),name.startsWith("colortex"),clear,color,name.equals("colortex0")&&!constants.containsKey(name+"ClearColor"),mip,
                sampler(properties,"sampler."+name,new Sampler(Wrap.CLAMP_TO_EDGE,Wrap.CLAMP_TO_EDGE,Wrap.CLAMP_TO_EDGE,nearest,textureFormat.integer(),compare))));
        }
        properties.all().forEach((key,value)->{
            if(!key.startsWith("image."))return;String name=key.substring(6);var p=value.trim().split("\\s+");
            if(p.length<7||p.length>9)throw new IllegalArgumentException("Invalid "+key+": "+value);
            boolean relative=Boolean.parseBoolean(p[5]);
            if(relative&&p.length!=8)throw new IllegalArgumentException("Relative custom image expects width and height: "+key);
            Size size=relative?new Size(p[6],p[7],"1",true,true,Shape.D2):new Size(p[6],p.length>7?p[7]:"1",p.length>8?p[8]:"1",false,false,p.length==7?Shape.D1:p.length==8?Shape.D2:Shape.D3);
            if(targets.containsKey(name)||standard(canonical(name)))throw new IllegalArgumentException("Custom image conflicts with standard target: "+name);
            targets.put(name,new Target(name,TextureFormat.of(p[2]),size,true,false,Boolean.parseBoolean(p[4]),new Color(0,0,0,0),false,false,Sampler.regular()));
            if(!p[0].equals("none"))aliases.put(p[0],name);
        });
        int noise=integer(constants.getOrDefault("noiseTextureResolution","256"),constants);
        return new PackResourcePlan(targets,passes,custom,aliases,noise,constants,properties);
    }
    public static Size parseSize(String text){var v=text.trim().split("\\s+");if(v.length!=2)throw new IllegalArgumentException("Expected width height: "+text);return new Size(v[0],v[1],"1",relative(v[0]),relative(v[1]),Shape.D2);}
    private static boolean relative(String text){return text.matches("[0-9]*\\.[0-9]+(?:[eE][+-]?[0-9]+)?|[0-9]+[eE]-[0-9]+");}
    public static boolean truth(String value,Map<String,String> constants){return PackExpression.evaluate(value,constants)!=0;}
    public static int integer(String value,Map<String,String> constants){double n=PackExpression.evaluate(value,constants);if(n<1||n!=Math.rint(n)||n>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid positive integer "+value);return (int)n;}
    private static int colorIndex(String name){String c=canonical(name);int n=Integer.parseInt(c.replaceFirst("^colortex",""));if(n<0||n>15)throw new IllegalArgumentException("Color target "+name);return n;}
    private static Color color(String value,Map<String,String> constants){String v=value.trim().replaceFirst("^(?:[iu]?vec4)\\s*\\(","").replaceFirst("\\)$","");var parts=v.split(",");if(parts.length!=1&&parts.length!=4)throw new IllegalArgumentException("Invalid clear color "+value);float[] c=new float[4];for(int i=0;i<4;i++)c[i]=(float)PackExpression.evaluate(parts[parts.length==1?0:i].trim(),constants);return new Color(c[0],c[1],c[2],c[3]);}
    public static Sampler sampler(PackProperties p,String prefix,Sampler fallback){
        String min=p.get(prefix+".minFilter",fallback.nearest()?"NEAREST":"LINEAR");String mag=p.get(prefix+".magFilter",fallback.magNearest()?"NEAREST":"LINEAR");
        if(!min.matches("(?:NEAREST|LINEAR)(?:_MIPMAP_(?:NEAREST|LINEAR))?")||!mag.matches("NEAREST|LINEAR"))throw new IllegalArgumentException("Invalid sampler filters for "+prefix);
        return new Sampler(Wrap.valueOf(p.get(prefix+".wrapS",fallback.s().name())),Wrap.valueOf(p.get(prefix+".wrapT",fallback.t().name())),Wrap.valueOf(p.get(prefix+".wrapR",fallback.r().name())),
            min.startsWith("NEAREST"),mag.equals("NEAREST"),p.get(prefix+".mipmapFilter",min.contains("MIPMAP_")?min.substring(min.lastIndexOf('_')+1):fallback.mipNearest()?"NEAREST":"LINEAR").equals("NEAREST"),Boolean.parseBoolean(p.get(prefix+".compare",Boolean.toString(fallback.compare()))));
    }
}
