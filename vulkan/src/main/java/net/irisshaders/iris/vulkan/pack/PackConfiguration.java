package net.irisshaders.iris.vulkan.pack;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Iris selection plus independent per-pack option files. Existing unrelated settings are retained. */
public final class PackConfiguration {
    private final Path game,configuration;
    public PackConfiguration(Path game) { this.game=game;configuration=game.resolve("config/iris.properties"); }
    public record State(ShaderPackLoader.Selection remembered,boolean enabled) {
        public ShaderPackLoader.Selection selection(){return enabled?remembered:new ShaderPackLoader.Selection("","",Map.of());}
    }
    public State read() throws IOException {
        if(!Files.exists(configuration)) {
            var old=readProperties(game.resolve("config/iris-vulkan.properties"));
            String pack=old.getProperty("pack","");var options=new TreeMap<String,String>();
            old.stringPropertyNames().stream().filter(k->k.startsWith("option.")).forEach(k->options.put(k.substring(7),old.getProperty(k)));
            validateName(pack);
            return new State(new ShaderPackLoader.Selection(pack,old.getProperty("profile",""),options),!pack.isEmpty());
        }
        var p=readProperties(configuration);String pack=p.getProperty("shaderPack","");
        return new State(forPack(pack),Boolean.parseBoolean(p.getProperty("enableShaders","true"))&&!pack.isEmpty());
    }
    public ShaderPackLoader.Selection forPack(String pack) throws IOException {
        validateName(pack);if(pack.isEmpty())return new ShaderPackLoader.Selection("","",Map.of());
        var p=readProperties(optionsPath(pack));var overrides=new TreeMap<String,String>();
        p.stringPropertyNames().stream().filter(k->!k.equals("iris.profile")).forEach(k->overrides.put(k,p.getProperty(k)));
        return new ShaderPackLoader.Selection(pack,p.getProperty("iris.profile",""),overrides);
    }
    public void save(State state) throws IOException {
        var selected=state.remembered();validateName(selected.pack());
        if(state.enabled()&&selected.pack().isEmpty())throw new IllegalArgumentException("Enabled selection requires a pack");
        if(!selected.pack().isEmpty()) {
            var options=new Properties();options.putAll(selected.overrides());options.setProperty("iris.profile",selected.profile());
            atomicWrite(optionsPath(selected.pack()),options);
        }
        var p=readProperties(configuration);p.setProperty("shaderPack",selected.pack());p.setProperty("enableShaders",Boolean.toString(state.enabled()));
        atomicWrite(configuration,p);
    }
    private Path optionsPath(String name) throws IOException {
        Path directory=game.resolve("shaderpacks");Files.createDirectories(directory);
        Path path=directory.resolve(name+".txt");
        if(Files.exists(path)&&!path.toRealPath().startsWith(directory.toRealPath()))throw new IOException("Option file escapes shaderpacks/");
        return path;
    }
    private static void validateName(String name) throws IOException {
        if(!name.isEmpty()&&(name.isBlank()||name.contains("/")||name.contains("\\")||name.contains(":")||name.equals(".")||name.equals("..")))
            throw new IOException("Invalid shaderpack name: "+name);
    }
    private static Properties readProperties(Path file) throws IOException {
        var p=new Properties();if(Files.isRegularFile(file))try(var reader=Files.newBufferedReader(file)){p.load(reader);}return p;
    }
    private static void atomicWrite(Path path,Properties p) throws IOException {
        Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"iris-",".tmp");
        try {
            try(var writer=Files.newBufferedWriter(temp)){p.store(writer,"Iris Vulkan shader configuration");}
            try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temp);}
    }
}
