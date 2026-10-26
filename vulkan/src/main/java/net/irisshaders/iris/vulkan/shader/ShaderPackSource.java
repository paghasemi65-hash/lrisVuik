package net.irisshaders.iris.vulkan.shader;

import org.anarres.cpp.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Directory/ZIP shaderpack reader with a pack-root virtual filesystem for conditional includes. */
public final class ShaderPackSource implements AutoCloseable {
    private static final String VERSION="IRIS_VK_INTERNAL_VERSION", EXTENSION="IRIS_VK_INTERNAL_EXTENSION";
    private final FileSystem zip;
    private final Path root;
    public ShaderPackSource(Path pack) throws IOException {
        FileSystem opened=null;
        try {
            Path base;
            if(Files.isDirectory(pack))base=pack.toRealPath();
            else{opened=FileSystems.newFileSystem(pack,Map.of());base=opened.getPath("/");}
            Path shaders=base.resolve("shaders");
            if(!Files.isDirectory(shaders))throw new IOException("Shaderpack must contain a shaders/ directory: "+pack);
            root=shaders.toRealPath();zip=opened;
        }catch(Throwable t){if(opened!=null)opened.close();throw t;}
    }
    public Set<String> programs() throws IOException {
        return programs("");
    }
    public Set<String> programs(String directory) throws IOException {
        var names=new TreeSet<String>();
        try(var files=Files.list(resolve(directory))){
            for(var p:files.toList()) {
                String name=p.getFileName().toString();
                if(name.matches(".+\\.(vsh|fsh|csh)"))names.add(name.substring(0,name.length()-4));
                if(name.matches(".+\\.(gsh|tcs|tes)"))throw new IOException("Geometry/tessellation stage is not implemented: "+name);
            }
        }
        return Collections.unmodifiableSet(names);
    }
    public boolean exists(String virtualPath) {return Files.isRegularFile(resolve(virtualPath));}
    public boolean directory(String path) { return Files.isDirectory(resolve(path)); }
    public List<String> files() throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(root::relativize).map(Path::toString)
                .map(p -> p.replace('\\', '/')).sorted().toList();
        }
    }
    public byte[] readBytes(String path) throws IOException {
        var physical = resolve(path).toRealPath();
        if (!physical.startsWith(root)) throw new IOException("Symlink escapes shaderpack: " + path);
        if (Files.size(physical) > 256L*1024*1024) throw new IOException("Pack resource exceeds 256 MiB: " + path);
        return Files.readAllBytes(physical);
    }
    public String readText(String path) throws IOException { return new String(readBytes(path), StandardCharsets.UTF_8); }
    public String preprocess(String virtualPath,Map<String,String> defines) throws IOException {
        return preprocess(virtualPath, defines, java.util.function.UnaryOperator.identity());
    }
    public String preprocess(String virtualPath, Map<String,String> defines, java.util.function.UnaryOperator<String> sourceOptions) throws IOException {
        var headers=new StringBuilder();var body=new StringBuilder();
        var fs=new PackFileSystem(sourceOptions);
        try(var pp=new Preprocessor()){
            pp.setFileSystem(fs);pp.setQuoteIncludePath(List.of("/"));pp.setSystemIncludePath(List.of("/"));pp.addFeature(Feature.KEEPCOMMENTS);pp.addFeature(Feature.PRAGMA_ONCE);
            pp.setListener(new DefaultPreprocessorListener(){
                @Override public void handleError(Source source,int line,int column,String message)throws LexerException{throw new LexerException(source.getPath()+":"+line+":"+column+": error: "+message);}
                @Override public void handleWarning(Source source,int line,int column,String message)throws LexerException{
                    if(message.startsWith("#warning "+VERSION))headers.append(message.replace("#warning "+VERSION,"#version")).append('\n');
                    else if(message.startsWith("#warning "+EXTENSION))headers.append(message.replace("#warning "+EXTENSION,"#extension")).append('\n');
                    else super.handleWarning(source,line,column,message);
                }
            });
            for(var e:defines.entrySet()){
                if(!e.getKey().matches("[A-Za-z_][A-Za-z_0-9]*")||e.getValue().contains("\n"))throw new IllegalArgumentException("Invalid macro "+e.getKey());
                pp.addMacro(e.getKey(),e.getValue());
            }
            pp.addInput(fs.getFile(virtualPath).getSource());
            for(int tokens=0;;tokens++){
                if(tokens>4_000_000)throw new IOException("Shader preprocessor token budget exceeded");
                var token=pp.token();if(token==null||token.getType()==Token.EOF)break;body.append(token.getText());
            }
        }catch(LexerException e){throw new IOException("Shader preprocessing failed: "+e.getMessage(),e);}
        return headers.append(body).append('\n').toString();
    }
    private Path resolve(String virtualPath){
        String key=normalize(virtualPath);var resolved=root.resolve(key.substring(1)).normalize();
        if(!resolved.startsWith(root))throw new IllegalArgumentException("Include escapes shaderpack");return resolved;
    }
    private static String normalize(String path){
        if(path.indexOf('\\')>=0||path.indexOf(':')>=0)throw new IllegalArgumentException("Shaderpack paths must use '/' and cannot name drives");
        var parts=new ArrayDeque<String>();
        for(String segment:path.split("/")){
            if(segment.isEmpty()||segment.equals("."))continue;
            if(segment.equals("..")){if(parts.isEmpty())throw new IllegalArgumentException("Include escapes shaderpack");parts.removeLast();}
            else parts.addLast(segment);
        }
        return "/"+String.join("/",parts);
    }
    private final class PackFileSystem implements VirtualFileSystem {
        private final java.util.function.UnaryOperator<String> sourceOptions;
        PackFileSystem(java.util.function.UnaryOperator<String> sourceOptions) { this.sourceOptions = sourceOptions; }
        private int openedSources;
        public VirtualFile getFile(String path){return new PackFile(normalize(path));}
        public VirtualFile getFile(String parent,String name){return getFile(name.startsWith("/")?name:parent+"/"+name);}
        private final class PackFile implements VirtualFile {
            private final String path;
            PackFile(String path){this.path=path;}
            public boolean isFile(){return Files.isRegularFile(resolve(path));}
            public String getPath(){return path;}
            public String getName(){return path.substring(path.lastIndexOf('/')+1);}
            public VirtualFile getParentFile(){return getFile(path.substring(0,Math.max(1,path.lastIndexOf('/'))));}
            public VirtualFile getChildFile(String name){return getFile(path,name);}
            public Source getSource() throws IOException {
                if(++openedSources>2048)throw new IOException("Include recursion or source budget exceeded");
                var physical=resolve(path).toRealPath();
                if(!physical.startsWith(root))throw new IOException("Symlink escapes shaderpack: "+path);
                if(Files.size(physical)>8*1024*1024)throw new IOException("Shader source exceeds 8 MiB: "+path);
                String source=Files.readString(physical,StandardCharsets.UTF_8).replace("\u0000", "");
                source = sourceOptions.apply(source);
                if(source.contains(VERSION)||source.contains(EXTENSION))throw new IOException("Reserved preprocessor marker");
                source=source.replaceAll("(?m)^(\\s*)#\\s*version\\b","$1#warning "+VERSION)
                    .replaceAll("(?m)^(\\s*)#\\s*extension\\b","$1#warning "+EXTENSION);
                return new StringLexerSource(source,true){@Override public String getPath(){return path;}@Override public String getName(){return path;}};
            }
        }
    }
    /** Output location is the list position; each value selects the colortex attachment at that location. */
    public static List<Integer> drawBuffers(String source){
        var comments=Pattern.compile("(?s)/\\*.*?\\*/|//[^\\n]*").matcher(source);List<Integer> result=null;
        while(comments.find()){
            var directive=Pattern.compile("\\b(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9,]+)").matcher(comments.group());
            if(!directive.find())continue;
            String digits=directive.group(2);List<Integer> values;
            if(directive.group(1).equals("DRAWBUFFERS"))values=digits.chars().map(c->c-'0').boxed().toList();
            else values=Arrays.stream(digits.split(",")).map(Integer::parseInt).toList();
            if(values.isEmpty()||values.stream().anyMatch(i->i<0||i>15)||new HashSet<>(values).size()!=values.size())throw new IllegalArgumentException("Invalid render target directive");
            // As in Iris's CommentDirectiveParser, the last active declaration overrides earlier ones.
            result=values;
        }
        return result==null?List.of(0):result;
    }
    @Override public void close()throws IOException{if(zip!=null)zip.close();}
}
