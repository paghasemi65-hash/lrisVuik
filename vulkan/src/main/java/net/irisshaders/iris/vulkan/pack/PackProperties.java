package net.irisshaders.iris.vulkan.pack;

import org.anarres.cpp.*;
import java.io.*;
import java.util.*;

/** Java property escapes/continuations plus conditional preprocessing; profile option names stay literal. */
public final class PackProperties {
    private static final String MARKER = "IRIS_VK_PROPERTY_";
    public record Profile(Map<String,String> options, Set<String> disabledPrograms) {
        public Profile { options = Map.copyOf(options); disabledPrograms = Set.copyOf(disabledPrograms); }
    }
    private final Map<String,String> values;
    public PackProperties(Map<String,String> values) { this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    public static PackProperties parse(String text, Map<String,String> macros) throws IOException {
        if (text.contains(MARKER)) throw new IOException("Reserved property marker");
        var logical = new ArrayList<String>(); var line = new StringBuilder();
        for (String physical : text.split("\\R", -1)) {
            String trimmed = physical.stripTrailing();
            int slashes = 0; for (int i=trimmed.length()-1;i>=0 && trimmed.charAt(i)=='\\';i--) slashes++;
            line.append(line.isEmpty() ? trimmed : trimmed.stripLeading());
            if ((slashes & 1) == 1) { line.setLength(line.length()-1); continue; }
            logical.add(line.toString()); line.setLength(0);
        }
        if (!line.isEmpty()) throw new IOException("Unterminated property continuation");
        var ppText = new StringBuilder();
        for (int i=0;i<logical.size();i++) {
            String raw = logical.get(i).stripLeading();
            if (raw.matches("#\\s*(if|ifdef|ifndef|elif|else|endif|define|undef|error)\\b.*")) ppText.append(raw);
            else if (!raw.isBlank() && !raw.startsWith("#") && !raw.startsWith("!")) ppText.append("#warning ").append(MARKER).append(i);
            ppText.append('\n');
        }
        var active = new ArrayList<Integer>();
        var environments = new HashMap<Integer,Map<String,Macro>>();
        try (var pp = new Preprocessor()) {
            pp.setListener(new DefaultPreprocessorListener() {
                public void handleWarning(Source source,int line,int column,String message) {
                    String prefix = "#warning " + MARKER;
                    if (message.startsWith(prefix)) {
                        int property = Integer.parseInt(message.substring(prefix.length()).trim());
                        active.add(property); environments.put(property, new HashMap<>(pp.getMacros()));
                    }
                }
                public void handleError(Source source,int line,int column,String message) throws LexerException {
                    throw new LexerException("shaders.properties:"+line+":"+column+": "+message);
                }
            });
            for (var e : macros.entrySet()) pp.addMacro(e.getKey(),e.getValue());
            pp.addInput(new StringLexerSource(ppText.toString(), true));
            while (pp.token().getType() != Token.EOF) { }
        } catch (LexerException e) { throw new IOException(e.getMessage(),e); }
        var ordered = new LinkedHashMap<String,String>();
        for (int property : active) {
            var parsed = new Properties(); parsed.load(new StringReader(logical.get(property)));
            for (String key : parsed.stringPropertyNames()) {
                String value = parsed.getProperty(key);
                // These directives refer to option identifiers, rather than their current values.
                if (!key.startsWith("profile.") && !key.startsWith("screen") && !key.equals("sliders")) {
                    try (var expander = new Preprocessor()) {
                        for (var macro : environments.get(property).values()) expander.addMacro(macro);
                        expander.addInput(new StringLexerSource(value));
                        var expanded = new StringBuilder(); Token token;
                        while ((token = expander.token()).getType() != Token.EOF) expanded.append(token.getText());
                        value = expanded.toString().trim();
                    } catch (LexerException e) { throw new IOException("shaders.properties:"+(property+1)+": "+e.getMessage(),e); }
                }
                ordered.put(key,value);
            }
        }
        return new PackProperties(ordered);
    }
    public Map<String,String> all() { return values; }
    public String get(String key,String fallback) { return values.getOrDefault(key,fallback); }
    public Set<String> profileNames() {
        var names = new LinkedHashSet<String>(); values.keySet().stream().filter(k->k.startsWith("profile.")).forEach(k->names.add(k.substring(8))); return names;
    }
    public Profile profile(String name) { return profile(name,new LinkedHashSet<>()); }
    private Profile profile(String name,Set<String> visiting) {
        if (!visiting.add(name)) throw new IllegalArgumentException("Profile cycle: " + visiting + " -> " + name);
        String definition = values.get("profile."+name);
        if (definition == null) throw new IllegalArgumentException("Unknown profile: " + name);
        var options = new LinkedHashMap<String,String>(); var disabled = new LinkedHashSet<String>();
        for (String token : definition.trim().split("\\s+")) {
            if (token.startsWith("profile.")) { var parent=profile(token.substring(8),visiting); options.putAll(parent.options()); disabled.addAll(parent.disabledPrograms()); }
            else if (token.startsWith("!program.")) disabled.add(token.substring(9));
            else if (token.startsWith("program.")) disabled.remove(token.substring(8));
            else if (token.startsWith("!")) options.put(token.substring(1),"false");
            else if (!token.isEmpty()) { var pair=token.split("[:=]",2); options.put(pair[0], pair.length==2 ? pair[1] : "true"); }
        }
        visiting.remove(name); return new Profile(options,disabled);
    }
    public boolean enabled(String program,Map<String,String> options,Set<String> disabled) {
        return !disabled.contains(program) && PackExpression.evaluate(get("program."+program+".enabled","true"),options) != 0;
    }
}
