package net.irisshaders.iris.vulkan.shader;

import io.github.douira.glsl_transformer.ast.node.*;
import io.github.douira.glsl_transformer.ast.node.declaration.*;
import io.github.douira.glsl_transformer.ast.node.expression.*;
import io.github.douira.glsl_transformer.ast.node.expression.binary.ArrayAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.specifier.ArraySpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.*;
import java.util.*;
import static io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier.StorageType.*;

/** AST-only semantic rewrites. Input is include-expanded and macro-preprocessed GLSL. */
public final class ShaderVulkanTransformer {
    public static final String ABI_VERSION = "iris-vulkan-abi-3";
    public record Program(Map<ShaderStage, String> sources, Std140Layout uniforms, DescriptorSchema descriptors) {
        public Program { sources = Map.copyOf(sources); }
    }
    private record Variable(String name, String type, int arrayLength, List<Integer> dimensions, TypeAndInitDeclaration declaration, DeclarationMember member) {}
    private record Parsed(ShaderStage stage, SingleASTTransformer<?> parser, TranslationUnit tree) {}
    public static final class StageFailure extends IllegalArgumentException {
        private final ShaderStage stage;
        public StageFailure(ShaderStage stage,RuntimeException cause){super(stage+": "+Objects.toString(cause.getMessage(),cause.toString()),cause);this.stage=stage;}
        public ShaderStage stage(){return stage;}
    }

    public Program transformProgram(Map<ShaderStage, String> sources) {
        // glsl-transformer 3.0.0-pre3 keeps Root.activeBuildRoots in a static, non-thread-local deque.
        // Render-thread compositor recreation can overlap background pack transformation.
        synchronized(ShaderVulkanTransformer.class){return transformExclusive(sources);}
    }
    private Program transformExclusive(Map<ShaderStage,String> sources) {
        if (sources.isEmpty() || sources.containsKey(ShaderStage.COMPUTE) && sources.size() != 1)
            throw new IllegalArgumentException("A program is graphics stages or a single compute stage");
        var parsed = new ArrayList<Parsed>();
        var uniforms = new ArrayList<Std140Layout.Field>();
        var resources = new ArrayList<DescriptorSchema.Resource>();
        var varyingTypes = new TreeMap<String, String>();
        for (var stage : ShaderStage.values()) {
            if (!sources.containsKey(stage)) continue;
            try {
            String input = sources.get(stage);
            if (input.replaceAll("(?s)/\\*.*?\\*/|//[^\\n]*"," ").lines().anyMatch(l -> l.stripLeading().matches("#\\s*(include|define|if|ifdef|ifndef|else|elif|endif|undef)\\b.*")))
                throw new IllegalArgumentException("Expand includes and preprocessor conditionals before AST transformation");
            var parser = new SingleASTTransformer<>();
            final TranslationUnit[] result = {null};
            parser.setTransformation((tree, root) -> {
                tree.setVersionStatement(new VersionStatement(Version.GLSL46, Profile.CORE));
                for(var block:root.nodeIndex.getStream(InterfaceBlockDeclaration.class).toList()) {
                    if(block.getTypeQualifier().getParts().stream().anyMatch(part->part instanceof StorageQualifier q&&(q.storageType==BUFFER||q.storageType==UNIFORM)))
                        throw new IllegalArgumentException("Pack-declared buffer block has no host allocation/binding implementation: "+block.getBlockName().getName());
                }
                normalizeBuiltins(parser, tree, root, stage);
                reconcileUniformBindings(tree);
                result[0] = tree;
            });
            parser.transform(input);
            var tree = result[0];
            parsed.add(new Parsed(stage, parser, tree));
            for (var v : variables(tree)) {
                if (has(v, UNIFORM)) {
                    // Loose declarations (including GL initializers) are replaced by the host-populated UBO.
                    // UniformSnapshot requires a value for every member; initializers never become fake game inputs.
                    if (opaque(v.type)) resources.add(resource(v));
                    else uniforms.add(new Std140Layout.Field(v.name, Std140Layout.Type.of(v.type), v.arrayLength,v.dimensions));
                } else if (has(v, VARYING) || stage == ShaderStage.VERTEX && has(v, OUT) || stage == ShaderStage.FRAGMENT && has(v, IN)) {
                    var type = v.type + ":" + v.arrayLength;
                    var old = varyingTypes.putIfAbsent(v.name, type);
                    if (old != null && !old.equals(type)) throw new IllegalArgumentException("Stage interface mismatch: " + v.name);
                }
            }
            } catch(RuntimeException failure){throw new StageFailure(stage,failure);}
        }
        var layout = Std140Layout.pack(uniforms);
        var schema = DescriptorSchema.create(resources);
        var locations = interfaceLocations(parsed,varyingTypes);
        var output = new EnumMap<ShaderStage, String>(ShaderStage.class);
        for (var p : parsed) {
            try {
            var tree = p.tree; var parser = p.parser;
            var outputLocations = fragmentOutputLocations(tree);
            // Split multiple declarators structurally, preserving interpolation and image format qualifiers.
            for (var v : variables(tree)) {
                String decl = null;
                if (has(v, UNIFORM)) {
                    if (opaque(v.type)) {
                        var b = schema.get(v.name);
                        decl = "layout(set=1,binding=" + b.binding() + ") " + resource(v).qualifiers() + " uniform " + v.type + " " + v.name + suffix(v) + ";";
                    }
                } else {
                    boolean varying = has(v, VARYING) || p.stage == ShaderStage.VERTEX && has(v, OUT) || p.stage == ShaderStage.FRAGMENT && has(v, IN);
                    boolean attribute = p.stage == ShaderStage.VERTEX && (has(v, ATTRIBUTE) || has(v, IN));
                    boolean fragmentOutput = p.stage == ShaderStage.FRAGMENT && has(v, OUT);
                    if (!varying && !attribute && !fragmentOutput) continue;
                    int loc = fragmentOutput ? outputLocations.get(v.name) : varying ? locations.get(v.name) : attributeLocation(v.name);
                    String interpolation = qualifierText(v).replaceAll("\\blayout\\s*\\([^)]*\\)", "")
                        .replaceAll("\\b(varying|attribute|in|out)\\b", "").strip();
                    String direction = fragmentOutput ? "out" : attribute || p.stage == ShaderStage.FRAGMENT ? "in" : "out";
                    decl = "layout(location=" + loc + ") " + interpolation + " " + direction + " " + v.type + " " + v.name + suffix(v) + ";";
                }
                v.member.detachAndDelete();
                if (v.declaration.getMembers().isEmpty()) v.declaration.getAncestor(DeclarationExternalDeclaration.class).detachAndDelete();
                if (decl != null) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, decl);
            }
            if (layout.size() > 0) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, layout.glsl());
            if (p.stage == ShaderStage.VERTEX) {
                // Wrapping main handles every early return. Do not also modify the host projection.
                tree.getRoot().rename("main", "iris_pack_main");
                tree.parseAndInjectNode(parser, ASTInjectionPoint.END,
                    "void main() { iris_pack_main(); gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5; }");
            }
            output.put(p.stage, ASTPrinter.printSimple(tree));
            } catch(RuntimeException failure){throw new StageFailure(p.stage,failure);}
        }
        return new Program(output, layout, schema);
    }

    private static List<Variable> variables(TranslationUnit tree) {
        var out = new ArrayList<Variable>();
        for (var d : tree.getRoot().nodeIndex.getStream(TypeAndInitDeclaration.class).toList()) {
            if (!(d.getParent() instanceof DeclarationExternalDeclaration)) continue;
            String type = ASTPrinter.printSimple(d.getType().getTypeSpecifier()).strip();
            for (var m : d.getMembers()) {
                var dimensions=arrayDimensions(m.getArraySpecifier());int length=dimensions.isEmpty()?0:1;
                for(int dimension:dimensions)length=Math.multiplyExact(length,dimension);
                var variable=new Variable(m.getName().getName(), type, length, dimensions,d,m);
                if(dimensions.size()>1&&(!has(variable,UNIFORM)||opaque(type)))throw new IllegalArgumentException("Multidimensional arrays currently require non-opaque uniforms: "+variable.name);
                out.add(variable);
            }
        }
        return out;
    }
    private static Map<String,Integer> interfaceLocations(List<Parsed> parsed,SortedMap<String,String> types) {
        var producers=new TreeMap<String,Variable>();var consumers=new TreeMap<String,Variable>();
        for(var p:parsed)for(var v:variables(p.tree)) {
            if(p.stage==ShaderStage.VERTEX&&(has(v,OUT)||has(v,VARYING)))producers.put(v.name,v);
            if(p.stage==ShaderStage.FRAGMENT&&(has(v,IN)||has(v,VARYING)))consumers.put(v.name,v);
        }
        var locations=new HashMap<String,Integer>();var used=new BitSet();
        for(var v:producers.values()) {
            Integer location=explicitLocation(v);var consumer=consumers.get(v.name);
            Integer inputLocation=consumer==null?null:explicitLocation(consumer);
            if(location!=null&&inputLocation!=null&&!location.equals(inputLocation))throw new IllegalArgumentException("Stage location mismatch: "+v.name);
            if(location==null)location=inputLocation;
            if(location!=null){reserve(used,location,locationCount(v.type)*Math.max(1,v.arrayLength));locations.put(v.name,location);}
        }
        for(var v:producers.values())if(!locations.containsKey(v.name)) {
            int count=locationCount(v.type)*Math.max(1,v.arrayLength),location=0;
            while(used.nextSetBit(location)>=0&&used.nextSetBit(location)<location+count)location=used.nextSetBit(location)+1;
            reserve(used,location,count);locations.put(v.name,location);
        }
        boolean linked=parsed.stream().anyMatch(p->p.stage==ShaderStage.VERTEX)&&parsed.stream().anyMatch(p->p.stage==ShaderStage.FRAGMENT);
        for(var input:consumers.values()) {
            Integer location=explicitLocation(input);Variable output=producers.get(input.name);
            if(location!=null) {
                final int requested=location;
                output=producers.values().stream().filter(v->Objects.equals(locations.get(v.name),requested)).findFirst().orElse(null);
            }
            if(linked&&output==null)throw new IllegalArgumentException("Fragment input has no vertex producer: "+input.name);
            if(output!=null) {
                if(!input.type.equals(output.type)||input.arrayLength!=output.arrayLength||!interpolation(input).equals(interpolation(output)))
                    throw new IllegalArgumentException("Stage interface type/interpolation mismatch: "+output.name+" -> "+input.name);
                locations.put(input.name,locations.get(output.name));
            }else if(location!=null)locations.put(input.name,location);
        }
        // Isolated stage compilation still receives deterministic locations.
        int next=used.length();for(String name:types.keySet())if(!locations.containsKey(name)){
            locations.put(name,next);String[] shape=types.get(name).split(":");next+=locationCount(shape[0])*Math.max(1,Integer.parseInt(shape[1]));
        }
        return locations;
    }
    private static String interpolation(Variable v){String q=qualifierText(v);return q.matches("(?s).*\\bflat\\b.*")?"flat":q.matches("(?s).*\\bnoperspective\\b.*")?"noperspective":"smooth";}
    private static Integer explicitLocation(Variable v){var matcher=java.util.regex.Pattern.compile("\\blocation\\s*=\\s*(\\d+)").matcher(qualifierText(v));return matcher.find()?Integer.parseInt(matcher.group(1)):null;}
    private static void reserve(BitSet used,int location,int count){
        if(location<0||location>65535||location+count>65536)throw new IllegalArgumentException("Interface location range");
        for(int i=location;i<location+count;i++)if(used.get(i))throw new IllegalArgumentException("Overlapping stage interface locations at "+i);
        used.set(location,location+count);
    }
    private static List<Integer> arrayDimensions(ArraySpecifier s) {
        if (s == null) return List.of();
        var dimensions=new ArrayList<Integer>();
        for(var expression:s.getDimensions()){
            if(!(expression instanceof LiteralExpression l)||!l.isInteger()||l.getInteger()<1||l.getInteger()>65536)throw new IllegalArgumentException("Array dimensions must be positive literals after preprocessing");
            dimensions.add((int)l.getInteger());
        }
        return List.copyOf(dimensions);
    }
    private static boolean has(Variable v, StorageQualifier.StorageType kind) {
        var q = v.declaration.getType().getTypeQualifier();
        return q != null && q.getParts().stream().anyMatch(p -> p instanceof StorageQualifier s && s.storageType == kind);
    }
    private static String qualifierText(Variable v) {
        var q = v.declaration.getType().getTypeQualifier(); return q == null ? "" : ASTPrinter.printSimple(q);
    }
    private static DescriptorSchema.Resource resource(Variable v) {
        // Pack-specified image format and access qualifiers survive. Descriptor indices are owned here.
        String qualifiers = qualifierText(v).replaceAll("\\buniform\\b", "").strip();
        if (qualifiers.matches("(?s).*\\b(set|binding|location)\\s*=.*"))
            throw new IllegalArgumentException("Explicit source descriptor bindings require reconciliation: " + v.name);
        return new DescriptorSchema.Resource(v.name, v.type, Math.max(1, v.arrayLength), qualifiers);
    }
    private static void reconcileUniformBindings(TranslationUnit tree) {
        // GL texture units and loose-uniform locations are not Vulkan descriptor indices.
        // Remove only those AST qualifier parts, retaining image formats and memory qualifiers.
        var declarations=new HashSet<TypeAndInitDeclaration>();
        for(var v:variables(tree))if(has(v,UNIFORM))declarations.add(v.declaration);
        for(var declaration:declarations)for(var part:List.copyOf(declaration.getType().getTypeQualifier().getParts()))if(part instanceof LayoutQualifier layout){
            for(var item:List.copyOf(layout.getParts()))if(item instanceof NamedLayoutQualifierPart named&&Set.of("set","binding","location").contains(named.getName().getName()))item.detachAndDelete();
            if(layout.getParts().isEmpty())layout.detachAndDelete();
        }
    }
    private static boolean opaque(String type) { return type.matches("[iu]?(sampler|image).*"); }
    private static Map<String,Integer> fragmentOutputLocations(TranslationUnit tree) {
        var result = new HashMap<String,Integer>(); var used = new BitSet();
        var outputs = variables(tree).stream().filter(v -> has(v,OUT)).sorted(Comparator.comparing(Variable::name)).toList();
        for(var v:outputs) {
            var matcher=java.util.regex.Pattern.compile("\\blocation\\s*=\\s*(\\d+)").matcher(qualifierText(v));
            if(matcher.find()) {
                int location=Integer.parseInt(matcher.group(1)), count=locationCount(v.type)*Math.max(1,v.arrayLength);
                for(int i=location;i<location+count;i++){if(used.get(i))throw new IllegalArgumentException("Overlapping output locations");used.set(i);}
                result.put(v.name,location);
            }
        }
        for(var v:outputs)if(!result.containsKey(v.name)) {
            int count=locationCount(v.type)*Math.max(1,v.arrayLength), location=0;
            while(used.nextSetBit(location)>=0&&used.nextSetBit(location)<location+count)location=used.nextSetBit(location)+1;
            result.put(v.name,location);used.set(location,location+count);
        }
        return result;
    }
    private static String suffix(Variable v) { return v.arrayLength == 0 ? "" : "[" + v.arrayLength + "]"; }
    private static int locationCount(String type) { return type.startsWith("mat") ? type.charAt(3) - '0' : 1; }
    private static int attributeLocation(String name) {
        return switch (name) {
            case "iris_Position", "Position", "vaPosition" -> 0;
            case "iris_Color", "Color" -> 1;
            case "iris_UV0", "UV0" -> 2;
            case "iris_UV1", "UV1" -> 3;
            case "iris_Normal", "Normal", "vaNormal" -> 4;
            case "mc_Entity" -> 5;
            case "mc_midTexCoord" -> 6;
            case "at_tangent" -> 7;
            case "at_midBlock" -> 8;
            default -> throw new IllegalArgumentException("Vertex attribute has no host layout: " + name);
        };
    }
    private static void normalizeBuiltins(SingleASTTransformer<?> parser, TranslationUnit tree, Root root, ShaderStage stage) {
        for(var id:root.identifierIndex.getStream("gl_Fog").toList()) {
            var member=id.getAncestor(MemberAccessExpression.class);
            if(member==null)throw new IllegalArgumentException("Legacy gl_Fog must be accessed by field");
            String expression=switch(member.getMember().getName()){
                case "color"->"iris_FogColor";case "density"->"fogDensity";case "start"->"fogStart";
                case "end"->"fogEnd";case "scale"->"(1.0 / (fogEnd - fogStart))";
                default->throw new IllegalArgumentException("Unknown gl_Fog field "+member.getMember().getName());};
            member.replaceByAndDelete(parser.parseExpression(root,expression));
        }
        for(String name:List.of("iris_FogColor","fogDensity","fogStart","fogEnd"))if(root.identifierIndex.has(name)&&variables(tree).stream().noneMatch(v->v.name.equals(name)))
            tree.parseAndInjectNode(parser,ASTInjectionPoint.BEFORE_DECLARATIONS,"uniform "+(name.equals("iris_FogColor")?"vec4":"float")+" "+name+";");
        // Iris's modern line programs use these implicit per-draw names without declaring them.
        for(String name:List.of("modelViewMatrix","projectionMatrix"))if(root.identifierIndex.has(name)&&variables(tree).stream().noneMatch(v->v.name.equals(name))) {
            String mapped=name.equals("modelViewMatrix")?"iris_ModelViewMatrix":"iris_ProjectionMatrix";
            root.rename(name,mapped);tree.parseAndInjectNode(parser,ASTInjectionPoint.BEFORE_DECLARATIONS,"uniform mat4 "+mapped+";");
        }
        if(stage==ShaderStage.VERTEX)for(String name:List.of("vaPosition","vaNormal"))if(root.identifierIndex.has(name)&&variables(tree).stream().noneMatch(v->v.name.equals(name)))
            root.replaceReferenceExpressions(parser,name,name.equals("vaPosition")?"gl_Vertex.xyz":"gl_Normal");
        for(var v:variables(tree))if(has(v,UNIFORM)&&v.name.equals("texture")) {
            root.replaceReferenceExpressions(parser,"texture","iris_TextureSampler");
            v.member.getName().setName("iris_TextureSampler");
        }
        root.rename("gl_VertexID", "gl_VertexIndex");
        root.rename("gl_InstanceID", "gl_InstanceIndex"); // Draw API enforces firstInstance == 0.
        var renames = Map.of("texture2D", "texture", "texture3D", "texture", "texture2DLod", "textureLod", "texture2DProj", "textureProj", "textureCube", "texture", "texture2DGradARB", "textureGrad");
        for (var e : renames.entrySet()) root.process(root.identifierIndex.getStream(e.getKey())
            .filter(id -> id.getParent() instanceof FunctionCallExpression), id -> id.setName(e.getValue()));
        for (String old : List.of("shadow2D", "shadow2DProj")) root.process(root.identifierIndex.getStream(old)
            .filter(id -> id.getParent() instanceof FunctionCallExpression), id -> {
                var call = (FunctionCallExpression)id.getParent();
                id.setName(old.endsWith("Proj") ? "textureProj" : "texture");
                var wrap = (FunctionCallExpression)parser.parseExpression(root, "vec4()");
                call.replaceBy(wrap); wrap.getParameters().add(call);
            });
        if (stage == ShaderStage.FRAGMENT) {
            root.replaceReferenceExpressions(parser, "gl_FragColor", "gl_FragData[0]");
            var used = new TreeSet<Integer>();
            for (var id : root.identifierIndex.getStream("gl_FragData").toList()) {
                var a = id.getAncestor(ArrayAccessExpression.class);
                if (a == null || !(a.getRight() instanceof LiteralExpression l) || !l.isInteger() || l.getInteger() < 0 || l.getInteger() > 15)
                    throw new IllegalArgumentException("gl_FragData requires a constant output index in 0..15");
                int n = (int)l.getInteger(); used.add(n);
                a.replaceByAndDelete(parser.parseExpression(root, "iris_FragData" + n));
            }
            for (int n : used) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "layout(location=" + n + ") out vec4 iris_FragData" + n + ";");
        }
        if (stage == ShaderStage.VERTEX) {
            String[][] attrs = {{"gl_Vertex","iris_Position","vec4"},{"gl_Color","iris_Color","vec4"},
                {"gl_Normal","iris_Normal","vec3"},{"gl_MultiTexCoord0","iris_UV0","vec4"},{"gl_MultiTexCoord1","iris_UV1","vec4"}};
            for (var a : attrs) if (root.identifierIndex.has(a[0])) {
                root.rename(a[0], a[1]);
                tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "in " + a[2] + " " + a[1] + ";");
            }
        }
        if (root.identifierIndex.has("gl_TexCoord")) {
            root.rename("gl_TexCoord", "iris_TexCoord");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "varying vec4 iris_TexCoord[8];");
        }
        if(root.identifierIndex.has("gl_TextureMatrix")) {
            root.rename("gl_TextureMatrix","iris_TextureMatrix");
            tree.parseAndInjectNode(parser,ASTInjectionPoint.BEFORE_DECLARATIONS,"uniform mat4 iris_TextureMatrix[8];");
        }
        if (root.identifierIndex.has("gl_FrontColor") || stage == ShaderStage.FRAGMENT && root.identifierIndex.has("gl_Color")) {
            root.rename(stage == ShaderStage.VERTEX ? "gl_FrontColor" : "gl_Color", "iris_FrontColor");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "varying vec4 iris_FrontColor;");
        }
        // Preserve compatibility-profile matrix semantics. Host must supply OpenGL-convention matrices.
        String[][] matrices = {{"gl_ModelViewMatrix","iris_ModelViewMatrix"},{"gl_ProjectionMatrix","iris_ProjectionMatrix"},
            {"gl_ModelViewProjectionMatrix","(iris_ProjectionMatrix * iris_ModelViewMatrix)"},{"gl_NormalMatrix","transpose(inverse(mat3(iris_ModelViewMatrix)))"},
            {"gl_ModelViewMatrixInverse","inverse(iris_ModelViewMatrix)"},{"gl_ProjectionMatrixInverse","inverse(iris_ProjectionMatrix)"},
            {"gl_ModelViewMatrixTranspose","transpose(iris_ModelViewMatrix)"},{"gl_ProjectionMatrixTranspose","transpose(iris_ProjectionMatrix)"},
            {"gl_ModelViewMatrixInverseTranspose","transpose(inverse(iris_ModelViewMatrix))"},{"gl_ProjectionMatrixInverseTranspose","transpose(inverse(iris_ProjectionMatrix))"},
            {"gl_ModelViewProjectionMatrixInverse","inverse(iris_ProjectionMatrix * iris_ModelViewMatrix)"},
            {"gl_ModelViewProjectionMatrixTranspose","transpose(iris_ProjectionMatrix * iris_ModelViewMatrix)"},
            {"gl_ModelViewProjectionMatrixInverseTranspose","transpose(inverse(iris_ProjectionMatrix * iris_ModelViewMatrix))"}};
        boolean needsMatrices = Arrays.stream(matrices).anyMatch(m -> root.identifierIndex.has(m[0])) || root.identifierIndex.has("ftransform");
        if (needsMatrices) {
            for (var m : matrices) root.replaceReferenceExpressions(parser, m[0], m[1]);
            for (String name : List.of("iris_ModelViewMatrix", "iris_ProjectionMatrix")) {
                boolean declared = variables(tree).stream().anyMatch(v -> v.name.equals(name));
                if (!declared) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "uniform mat4 " + name + ";");
            }
        }
        if (root.identifierIndex.has("ftransform")) {
            if (stage != ShaderStage.VERTEX) throw new IllegalArgumentException("ftransform outside vertex stage");
            if (!root.identifierIndex.has("iris_Position")) tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_DECLARATIONS, "in vec4 iris_Position;");
            root.rename("ftransform", "iris_ftransform");
            tree.parseAndInjectNode(parser, ASTInjectionPoint.BEFORE_FUNCTIONS,
                "vec4 iris_ftransform() { return iris_ProjectionMatrix * iris_ModelViewMatrix * iris_Position; }");
        }
    }
}
