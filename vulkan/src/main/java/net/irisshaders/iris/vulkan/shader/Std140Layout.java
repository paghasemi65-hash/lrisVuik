package net.irisshaders.iris.vulkan.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Deterministic column-major std140 ABI shared by every stage of a program. */
public record Std140Layout(List<Member> members, int size) {
    public record Type(String glsl, int alignment, int size, int columns, int rows, int matrixStride) {
        public static Type of(String name) {
            return switch (name) {
                case "float", "int", "uint", "bool" -> new Type(name, 4, 4, 1, 1, 0);
                case "vec2", "ivec2", "uvec2", "bvec2" -> new Type(name, 8, 8, 1, 2, 0);
                case "vec3", "ivec3", "uvec3", "bvec3" -> new Type(name, 16, 12, 1, 3, 0);
                case "vec4", "ivec4", "uvec4", "bvec4" -> new Type(name, 16, 16, 1, 4, 0);
                default -> {
                    if (!name.matches("mat[234](x[234])?"))
                        throw new IllegalArgumentException("Unsupported uniform type: " + name + "; structs/doubles require a reflected ABI");
                    int c = name.charAt(3) - '0', r = name.length() == 4 ? c : name.charAt(5) - '0';
                    yield new Type(name, 16, c * 16, c, r, 16);
                }
            };
        }
    }
    // arrayLength=0 is a non-array, including scalar/vector/matrix values.
    public record Field(String name, Type type, int arrayLength, List<Integer> dimensions) {
        public Field(String name,Type type,int arrayLength){this(name,type,arrayLength,arrayLength==0?List.of():List.of(arrayLength));}
        public Field {
            dimensions=List.copyOf(dimensions);
            if (!name.matches("[A-Za-z_][A-Za-z_0-9]*") || arrayLength < 0) throw new IllegalArgumentException(name);
            int elements=dimensions.isEmpty()?0:1;
            for(int dimension:dimensions){if(dimension<1)throw new IllegalArgumentException("Array dimension");elements=Math.multiplyExact(elements,dimension);}
            if(elements!=arrayLength)throw new IllegalArgumentException("Array shape mismatch");
        }
        public String declaration() { return type.glsl + " " + name + dimensions.stream().map(d->"["+d+"]").collect(java.util.stream.Collectors.joining()) + ";"; }
    }
    public record Member(Field field, int offset, int arrayStride) {}
    public Std140Layout { members = List.copyOf(members); }
    public static int align(int value, int alignment) { return Math.addExact(value, alignment - 1) / alignment * alignment; }
    public static Std140Layout pack(Collection<Field> fields) {
        var sorted = new TreeMap<String, Field>();
        for (var f : fields) {
            var old = sorted.putIfAbsent(f.name, f);
            if (old != null && !old.equals(f)) throw new IllegalArgumentException("Conflicting uniform " + f.name);
        }
        var out = new ArrayList<Member>();
        int offset = 0;
        for (var f : sorted.values()) {
            int alignment = f.arrayLength == 0 ? f.type.alignment : align(f.type.alignment, 16);
            int stride = f.arrayLength == 0 ? 0 : align(f.type.size, alignment);
            offset = align(offset, alignment);
            out.add(new Member(f, offset, stride));
            offset = Math.addExact(offset, f.arrayLength == 0 ? f.type.size : Math.multiplyExact(stride, f.arrayLength));
        }
        return new Std140Layout(out, align(offset, 16));
    }
    public String glsl() {
        if (members.isEmpty()) return "";
        var s = new StringBuilder("layout(set=0,binding=0,std140) uniform IrisPerFrameUniforms {\n");
        for (var m : members) s.append("layout(offset=").append(m.offset).append(") ").append(m.field.declaration()).append('\n');
        return s.append("};\n").toString();
    }
    public Member member(String name) {
        return members.stream().filter(m -> m.field.name.equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown uniform " + name));
    }
    public void putFloats(ByteBuffer target, String name, float... values) {
        var m = member(name); var t = m.field.type;
        if (!(t.glsl.startsWith("mat") || t.glsl.startsWith("vec") || t.glsl.equals("float")))
            throw new IllegalArgumentException("Not a floating-point uniform: " + name);
        write(target, m, values.length, (b, offset, i) -> b.putFloat(offset, values[i]));
    }
    public void putInts(ByteBuffer target, String name, int... values) {
        var m = member(name);
        if (m.field.type.glsl.startsWith("mat") || m.field.type.glsl.startsWith("vec") || m.field.type.glsl.equals("float"))
            throw new IllegalArgumentException("Not an integer/boolean uniform: " + name);
        write(target, m, values.length, (b, offset, i) -> b.putInt(offset, values[i]));
    }
    private interface Writer { void put(ByteBuffer b, int offset, int index); }
    private void write(ByteBuffer target, Member m, int count, Writer writer) {
        var t = m.field.type; int arrays = Math.max(1, m.field.arrayLength);
        if (count != arrays * t.columns * t.rows || target.remaining() < size) throw new IllegalArgumentException("Uniform shape or buffer size mismatch");
        var b = target.slice().order(ByteOrder.nativeOrder());
        int i = 0;
        for (int a = 0; a < arrays; a++) for (int c = 0; c < t.columns; c++) for (int r = 0; r < t.rows; r++)
            writer.put(b, m.offset + a * m.arrayStride + c * t.matrixStride + r * 4, i++);
    }
}
