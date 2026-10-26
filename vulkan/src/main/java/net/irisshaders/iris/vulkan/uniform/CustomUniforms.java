package net.irisshaders.iris.vulkan.uniform;

import net.irisshaders.iris.vulkan.pack.PackProperties;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;

/** Parsed expression graph with lazy branches, dependency/cycle checks and independent smoothing call sites. */
public final class CustomUniforms {
    private interface Node {UniformValue evaluate(Evaluation context);}
    private record Definition(String type,Node expression,boolean uniform){}
    private final Map<String,Definition> definitions=new LinkedHashMap<>();
    private final Map<String,UniformValue> constants;
    private final Map<String,HalfLifeSmoother> smoothers=new HashMap<>();
    public CustomUniforms(PackProperties properties,Map<String,UniformValue> constants){
        this.constants=Map.copyOf(constants);
        properties.all().forEach((key,expression)->{
            if(!key.startsWith("uniform.")&&!key.startsWith("variable."))return;
            String[] p=key.split("\\.",3);if(p.length!=3)throw new IllegalArgumentException("Invalid custom uniform key "+key);
            net.irisshaders.iris.vulkan.shader.Std140Layout.Type.of(p[1]);
            if(definitions.putIfAbsent(p[2],new Definition(p[1],new Parser(expression,key).parse(),p[0].equals("uniform")))!=null)throw new IllegalArgumentException("Duplicate custom variable "+p[2]);
        });
    }
    public UniformSnapshot evaluate(UniformSnapshot inputs){
        var evaluation=new Evaluation(inputs);var result=new HashMap<>(inputs.values());
        for(var entry:definitions.entrySet())if(entry.getValue().uniform())result.put(entry.getKey(),evaluation.resolve(entry.getKey()));
        return new UniformSnapshot(inputs.frame(),inputs.deltaSeconds(),result);
    }
    private final class Evaluation {
        final UniformSnapshot input;final Map<String,UniformValue> cache=new HashMap<>();final Set<String> visiting=new LinkedHashSet<>();
        Evaluation(UniformSnapshot input){this.input=input;}
        UniformValue resolve(String name){
            if(cache.containsKey(name))return cache.get(name);
            var definition=definitions.get(name);
            if(definition==null){if(constants.containsKey(name))return constants.get(name);return input.require(name);}
            if(!visiting.add(name))throw new IllegalArgumentException("Custom uniform cycle: "+visiting+" -> "+name);
            try {var result=definition.expression().evaluate(this).cast(definition.type());cache.put(name,result);return result;}
            catch(RuntimeException failure){throw new IllegalArgumentException("Custom "+name+": "+failure.getMessage(),failure);}
            finally{visiting.remove(name);}
        }
    }
    private static UniformValue map(UniformValue value,DoubleUnaryOperator operation){double[] data=value.elements();for(int i=0;i<data.length;i++)data[i]=operation.applyAsDouble(data[i]);return data.length==1?UniformValue.scalar(data[0]):UniformValue.vector(data);}
    private static UniformValue zip(UniformValue left,UniformValue right,DoubleBinaryOperator operation){var a=left.elements();var b=right.elements();int size=Math.max(a.length,b.length);if(a.length!=b.length&&a.length!=1&&b.length!=1)throw new IllegalArgumentException("Expression vector shape mismatch");double[] out=new double[size];for(int i=0;i<size;i++)out[i]=operation.applyAsDouble(a[a.length==1?0:i],b[b.length==1?0:i]);return size==1?UniformValue.scalar(out[0]):UniformValue.vector(out);}
    private final class Parser {
        private final List<String> tokens=new ArrayList<>();private final String source,key;private int position,callSite;
        private final Pattern token=Pattern.compile("\\s*(0[xX][0-9a-fA-F]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fF]?|[A-Za-z_]\\w*|&&|\\|\\||==|!=|<=|>=|[.()+*/%,!<>&|^?:-])");
        Parser(String source,String key){this.source=source;this.key=key;var matcher=token.matcher(source);int end=0;while(matcher.find()){if(matcher.start()!=end)throw error("Invalid token");tokens.add(matcher.group(1));end=matcher.end();}if(!source.substring(end).isBlank())throw error("Invalid token");}
        IllegalArgumentException error(String message){return new IllegalArgumentException(key+" at token "+position+": "+message+" in "+source);}
        boolean take(String token){if(position<tokens.size()&&tokens.get(position).equals(token)){position++;return true;}return false;}
        void expect(String token){if(!take(token))throw error("Expected "+token);}
        Node parse(){Node node=expression(0);if(position!=tokens.size())throw error("Unexpected "+tokens.get(position));return node;}
        Node expression(int minimum){
            Node left=atom();while(position<tokens.size()){
                String op=tokens.get(position);int precedence=switch(op){case "||"->1;case "&&"->2;case "==","!="->3;case "<",">","<=",">="->4;case "+","-"->5;case "*","/","%"->6;default->-1;};
                if(precedence<minimum)break;position++;Node a=left,b=expression(precedence+1);
                left=c->switch(op){
                    case "&&"->UniformValue.bool(a.evaluate(c).truth()&&b.evaluate(c).truth());case "||"->UniformValue.bool(a.evaluate(c).truth()||b.evaluate(c).truth());
                    case "+"->zip(a.evaluate(c),b.evaluate(c),(x,y)->x+y);case "-"->zip(a.evaluate(c),b.evaluate(c),(x,y)->x-y);case "*"->zip(a.evaluate(c),b.evaluate(c),(x,y)->x*y);case "/"->zip(a.evaluate(c),b.evaluate(c),(x,y)->x/y);case "%"->zip(a.evaluate(c),b.evaluate(c),(x,y)->x%y);
                    case "=="->UniformValue.bool(a.evaluate(c).scalar()==b.evaluate(c).scalar());case "!="->UniformValue.bool(a.evaluate(c).scalar()!=b.evaluate(c).scalar());case "<"->UniformValue.bool(a.evaluate(c).scalar()<b.evaluate(c).scalar());case ">"->UniformValue.bool(a.evaluate(c).scalar()>b.evaluate(c).scalar());case "<="->UniformValue.bool(a.evaluate(c).scalar()<=b.evaluate(c).scalar());case ">="->UniformValue.bool(a.evaluate(c).scalar()>=b.evaluate(c).scalar());default->throw error(op);
                };
            }
            if(minimum==0&&take("?")){Node condition=left,yes=expression(0);expect(":");Node no=expression(0);left=c->condition.evaluate(c).truth()?yes.evaluate(c):no.evaluate(c);}
            return left;
        }
        Node atom(){
            if(take("!")){var operand=atom();return c->UniformValue.bool(!operand.evaluate(c).truth());}if(take("-")){var operand=atom();return c->map(operand.evaluate(c),x->-x);}if(take("+"))return atom();
            Node node;
            if(take("(")){node=expression(0);expect(")");}
            else {
                if(position==tokens.size())throw error("Missing expression");String name=tokens.get(position++);
                if(name.equals("true")||name.equals("false"))node=c->UniformValue.bool(Boolean.parseBoolean(name));
                else if(Character.isDigit(name.charAt(0))||name.startsWith(".")){
                    boolean hex=name.startsWith("0x")||name.startsWith("0X");
                    double value=hex?Long.parseLong(name.substring(2),16):Double.parseDouble(name.replaceFirst("[fF]$",""));
                    boolean integral=hex||!name.matches(".*[.eEfF].*");
                    node=c->integral?new UniformValue("int",new double[]{value}):UniformValue.scalar(value);
                }
                else if(take("(")){var arguments=new ArrayList<Node>();if(!take(")")){do{arguments.add(expression(0));}while(take(","));expect(")");}node=function(name,List.copyOf(arguments),key+":"+callSite++);}
                else node=c->c.resolve(name);
            }
            while(take(".")){if(position==tokens.size())throw error("Missing component");String component=tokens.get(position++);Node parent=node;node=c->parent.evaluate(c).swizzle(component);}
            return node;
        }
        Node function(String name,List<Node> args,String site){
            if(name.equals("if")){if(args.size()<3||args.size()%2==0)throw error("if needs condition/value pairs and a default");return c->{for(int i=0;i<args.size()-1;i+=2)if(args.get(i).evaluate(c).truth())return args.get(i+1).evaluate(c);return args.getLast().evaluate(c);};}
            if(name.equals("smooth")){
                if(args.isEmpty()||args.size()>4)throw error("smooth arity");
                return c->{
                    var first=args.getFirst().evaluate(c);
                    int offset=args.size()==4||(args.size()>1&&first.type().equals("int"))?1:0;
                    double target=offset==0?first.scalar():args.get(offset).evaluate(c).scalar();
                    double up=args.size()>offset+1?args.get(offset+1).evaluate(c).scalar():1;
                    double down=args.size()>offset+2?args.get(offset+2).evaluate(c).scalar():up;
                    // Iris custom smooth() half-lives are in deciseconds, with state per expression call site.
                    return UniformValue.scalar(smoothers.computeIfAbsent(site,k->new HalfLifeSmoother()).sample(c.input.frame(),c.input.deltaSeconds(),target,up*.1,down*.1));
                };
            }
            return c->{var v=args.stream().map(a->a.evaluate(c)).toList();
                if(name.matches("[biu]?vec[234]")){int size=name.charAt(name.length()-1)-'0';var data=v.stream().flatMapToDouble(a->Arrays.stream(a.elements())).toArray();if(data.length==1){double fill=data[0];data=new double[size];Arrays.fill(data,fill);}return new UniformValue(name,data).cast(name);}
                return switch(name){
                    case "float","int","bool"->v.getFirst().cast(name);
                    case "abs"->map(v.getFirst(),Math::abs);case "sin"->map(v.getFirst(),Math::sin);case "cos"->map(v.getFirst(),Math::cos);case "tan"->map(v.getFirst(),Math::tan);case "asin"->map(v.getFirst(),Math::asin);case "acos"->map(v.getFirst(),Math::acos);case "atan"->map(v.getFirst(),Math::atan);case "atan2"->zip(v.get(0),v.get(1),Math::atan2);
                    case "floor"->map(v.getFirst(),Math::floor);case "ceil"->map(v.getFirst(),Math::ceil);case "round"->map(v.getFirst(),x->Math.round(x));case "frac","fract"->map(v.getFirst(),x->x-Math.floor(x));case "sqrt"->map(v.getFirst(),Math::sqrt);case "exp"->map(v.getFirst(),Math::exp);case "log"->map(v.getFirst(),Math::log);case "signum","sign"->map(v.getFirst(),Math::signum);
                    case "pow"->zip(v.get(0),v.get(1),Math::pow);case "min"->v.stream().reduce((a,b)->zip(a,b,Math::min)).orElseThrow();case "max"->v.stream().reduce((a,b)->zip(a,b,Math::max)).orElseThrow();case "clamp"->zip(zip(v.get(0),v.get(1),Math::max),v.get(2),Math::min);
                    case "between"->UniformValue.bool(v.get(0).scalar()>=v.get(1).scalar()&&v.get(0).scalar()<=v.get(2).scalar());
                    case "equals"->UniformValue.bool(Math.abs(v.get(0).scalar()-v.get(1).scalar())<=v.get(2).scalar());
                    case "in"->UniformValue.bool(v.subList(1,v.size()).stream().anyMatch(a->a.scalar()==v.getFirst().scalar()));
                    case "length"->UniformValue.scalar(Math.sqrt(Arrays.stream(v.getFirst().elements()).map(x->x*x).sum()));
                    case "dot"->UniformValue.scalar(Arrays.stream(zip(v.get(0),v.get(1),(x,y)->x*y).elements()).sum());
                    default->throw error("Unsupported custom function "+name);
                };
            };
        }
    }
}
