package net.irisshaders.iris.vulkan.render;

import java.util.*;

/** Ping-pong selection is independent of descriptor lifetime; already-bound sets stay immutable. */
public final class DescriptorFlipper<T> {
    public record Pair<T>(T main, T alternate) {
        public Pair { Objects.requireNonNull(main); Objects.requireNonNull(alternate); if (main.equals(alternate)) throw new IllegalArgumentException("Aliased ping-pong images"); }
    }
    private final SortedMap<Integer,Pair<T>> pairs;
    private final BitSet flipped = new BitSet();
    public DescriptorFlipper(List<Pair<T>> pairs) {
        this(indexed(pairs));
    }
    private static <T> Map<Integer,Pair<T>> indexed(List<Pair<T>> pairs) {var result=new TreeMap<Integer,Pair<T>>();for(int i=0;i<pairs.size();i++)result.put(i,pairs.get(i));return result;}
    public DescriptorFlipper(Map<Integer,Pair<T>> pairs) {
        if (pairs.isEmpty() || pairs.size() > 16) throw new IllegalArgumentException("Color target count");
        if(pairs.keySet().stream().anyMatch(i->i<0||i>15))throw new IllegalArgumentException("Color target index");
        this.pairs = Collections.unmodifiableSortedMap(new TreeMap<>(pairs));
        var unique = new HashSet<T>();
        for (var p : pairs.values()) if (!unique.add(p.main) || !unique.add(p.alternate)) throw new IllegalArgumentException("Targets alias across pairs");
    }
    public T read(int target) { var p = pairs.get(target); return flipped.get(target) ? p.alternate : p.main; }
    public T write(int target) { var p = pairs.get(target); return flipped.get(target) ? p.main : p.alternate; }
    public List<T> snapshotReads() { var out = new ArrayList<T>(); for (int i:pairs.keySet()) out.add(read(i)); return List.copyOf(out); }
    public void commitWrites(Collection<Integer> outputs) {
        var unique = new HashSet<Integer>();
        for (int i : outputs) if (!pairs.containsKey(i) || !unique.add(i)) throw new IllegalArgumentException("Invalid or duplicate output target");
        for (int i : outputs) flipped.flip(i);
    }
    public void commitWrites(Collection<Integer> outputs,Map<Integer,Boolean> overrides) {
        var selected=new LinkedHashSet<Integer>();
        for(int i:outputs)if(!pairs.containsKey(i)||!selected.add(i))throw new IllegalArgumentException("Invalid output target "+i);
        overrides.forEach((i,flip)->{if(!pairs.containsKey(i))throw new IllegalArgumentException("Flip of unallocated target "+i);if(flip)selected.add(i);else selected.remove(i);});
        commitWrites(selected);
    }
    public int size() { return pairs.size(); }
}
