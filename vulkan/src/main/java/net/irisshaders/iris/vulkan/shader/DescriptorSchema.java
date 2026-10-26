package net.irisshaders.iris.vulkan.shader;

import java.util.*;

/** Set 0: per-pass uniform snapshot. Set 1: images/samplers. Bindings are a program-wide ABI. */
public record DescriptorSchema(List<Binding> bindings) {
    public record Resource(String name, String type, int count, String qualifiers) {
        public Resource { if (count < 1) throw new IllegalArgumentException("Descriptor count"); }
        public boolean storage() { return type.matches("[iu]?image.*"); }
    }
    public record Binding(Resource resource, int binding) {}
    public DescriptorSchema { bindings = List.copyOf(bindings); }
    public static DescriptorSchema create(Collection<Resource> resources) {
        var unique = new TreeMap<String, Resource>();
        for (var r : resources) {
            var old = unique.putIfAbsent(r.name, r);
            if (old != null && (!old.type.equals(r.type) || old.count != r.count || !old.qualifiers.equals(r.qualifiers)))
                throw new IllegalArgumentException("Incompatible resource across stages: " + r.name);
        }
        var out = new ArrayList<Binding>(); int custom = 64;
        for (var r : unique.values()) {
            int b = standardBinding(r.name);
            out.add(new Binding(r, b < 0 ? custom++ : b));
        }
        return new DescriptorSchema(out);
    }
    public Binding get(String name) { return bindings.stream().filter(b -> b.resource.name.equals(name)).findFirst().orElseThrow(); }
    private static int standardBinding(String name) {
        String[] prefixes = {"colortex", "depthtex", "shadowtex", "shadowcolor", "colorimg"};
        int[] bases = {0, 16, 19, 21, 24}, counts = {16, 3, 2, 2, 6};
        if (name.equals("noisetex")) return 23;
        for (int i = 0; i < prefixes.length; i++) if (name.startsWith(prefixes[i])) {
            try { int n = Integer.parseInt(name.substring(prefixes[i].length())); if (n >= 0 && n < counts[i]) return bases[i] + n; }
            catch (NumberFormatException ignored) { }
        }
        return -1;
    }
}
