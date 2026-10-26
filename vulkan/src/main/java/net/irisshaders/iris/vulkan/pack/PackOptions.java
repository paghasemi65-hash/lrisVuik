package net.irisshaders.iris.vulkan.pack;

import java.util.*;
import java.util.regex.*;

/** Source-declared options are rewritten before preprocessing, including included files. */
public final class PackOptions {
    private static final Pattern DEFINE = Pattern.compile("^(\\s*)(//\\s*)?#\\s*define\\s+([A-Za-z_]\\w*)\\b(?!\\()(?:[ \\t]+([^/\\r\\n]*))?(.*)$");
    private static final Pattern CONSTANT = Pattern.compile("^(\\s*)const\\s+(bool|int|float)\\s+([A-Za-z_]\\w*)\\s*=\\s*([^;]+);(.*)$");
    private static final Pattern VALUES = Pattern.compile("\\[([^]]+)\\]");
    public record Option(String name, String defaultValue, List<String> values, boolean booleanDefine) {
        public Option {
            // Shaderpacks may intentionally omit their source default from the UI's selectable range.
            var allowed=new ArrayList<>(values);if(!allowed.contains(defaultValue))allowed.addFirst(defaultValue);
            values = List.copyOf(allowed);
        }
        public void validate(String value) {
            if (!values.contains(value)) throw new IllegalArgumentException("Invalid value for " + name + ": " + value + "; allowed=" + values);
        }
    }
    private final SortedMap<String,Option> options;
    private PackOptions(SortedMap<String,Option> options) { this.options = Collections.unmodifiableSortedMap(options); }
    public static PackOptions discover(Collection<String> sources) {
        var result = new TreeMap<String,Option>();
        var ambiguous = new HashSet<String>();
        for (String source : sources) for (String line : source.split("\\R")) {
            var d = DEFINE.matcher(line); var c = CONSTANT.matcher(line);
            Option option = null;
            if (d.matches()) {
                String value = d.group(4) == null ? "" : d.group(4).trim();
                var values = VALUES.matcher(d.group(5));
                if (value.isEmpty()) option = new Option(d.group(3), d.group(2) == null ? "true" : "false", List.of("false","true"), true);
                else if (values.find()) option = new Option(d.group(3), value, parseValues(values.group(1)), false);
            } else if (c.matches()) {
                var values = VALUES.matcher(c.group(5));
                if (values.find()) option = new Option(c.group(3), c.group(4).trim(), parseValues(values.group(1)), false);
                else if (c.group(2).equals("bool") && (c.group(3).startsWith("shadow") || c.group(3).startsWith("generate")))
                    option = new Option(c.group(3), c.group(4).trim(), List.of("false","true"), false);
            }
            if (option != null) {
                option.validate(option.defaultValue());
                var previous = result.putIfAbsent(option.name(), option);
                // Iris leaves conflicting declarations as source constants, rather than rewriting a
                // dimension-specific define into a different dimension's default.
                if (previous != null && !previous.equals(option)) ambiguous.add(option.name());
            }
        }
        ambiguous.forEach(result::remove);
        return new PackOptions(result);
    }
    private static List<String> parseValues(String text) { return Arrays.asList(text.trim().split("\\s+")); }
    public SortedMap<String,Option> declarations() { return options; }
    public Map<String,String> values(Map<String,String> overrides) {
        var result = new TreeMap<String,String>(); options.forEach((name, option) -> result.put(name, option.defaultValue()));
        overrides.forEach((name, value) -> {
            var option = options.get(name);
            if (option == null) throw new IllegalArgumentException("Unknown shader option: " + name);
            option.validate(value); result.put(name, value);
        });
        return Collections.unmodifiableMap(result);
    }
    public Map<String,String> macros(Map<String,String> values) {
        var result = new TreeMap<String,String>();
        options.forEach((name, option) -> {
            String value = values.get(name);
            if (option.booleanDefine()) { if (value.equals("true")) result.put(name,"1"); }
            else result.put(name, value.equals("true") ? "1" : value.equals("false") ? "0" : value);
        }); return result;
    }
    public String apply(String source, Map<String,String> values) {
        var output = new StringBuilder();
        for (String line : source.split("\\n", -1)) {
            var d = DEFINE.matcher(line); var c = CONSTANT.matcher(line);
            if (d.matches() && options.containsKey(d.group(3))) {
                var option = options.get(d.group(3)); String value = values.get(option.name());
                line = d.group(1) + (option.booleanDefine() && value.equals("false") ? "//" : "") + "#define " + option.name()
                    + (option.booleanDefine() ? "" : " " + value) + " " + d.group(5);
            } else if (c.matches() && options.containsKey(c.group(3))) {
                line = c.group(1) + "const " + c.group(2) + " " + c.group(3) + " = " + values.get(c.group(3)) + ";" + c.group(5);
            }
            output.append(line).append('\n');
        }
        return output.toString();
    }
}
