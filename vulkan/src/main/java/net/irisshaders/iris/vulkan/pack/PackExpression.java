package net.irisshaders.iris.vulkan.pack;

import java.util.*;
import java.util.regex.*;

/** Scalar expression evaluator for program conditions and resource dimensions. */
public final class PackExpression {
    private static final Pattern TOKEN = Pattern.compile("\\s*(0[xX][0-9a-fA-F]+|(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fFuU]?|[A-Za-z_]\\w*|&&|\\|\\||==|!=|<=|>=|<<|>>|[()+*/%,!~<>&|^?:-])");
    private final List<String> tokens = new ArrayList<>();
    private final Map<String,String> variables;
    private final Set<String> resolving;
    private int index;
    private PackExpression(String expression,Map<String,String> variables,Set<String> resolving) {
        this.variables=variables; this.resolving=resolving;
        var matcher=TOKEN.matcher(expression); int end=0;
        while(matcher.find()) { if(matcher.start()!=end) throw new IllegalArgumentException("Invalid expression: "+expression); tokens.add(matcher.group(1)); end=matcher.end(); }
        if(!expression.substring(end).isBlank())throw new IllegalArgumentException("Invalid expression: "+expression);
    }
    public static double evaluate(String expression,Map<String,String> variables) { return new PackExpression(expression,variables,new HashSet<>()).result(); }
    private double result() { double value=expression(0); if(index!=tokens.size() || !Double.isFinite(value))throw new IllegalArgumentException("Invalid scalar expression: "+tokens); return value; }
    private boolean take(String token) { if(index<tokens.size()&&tokens.get(index).equals(token)){index++;return true;}return false; }
    private void expect(String token) { if(!take(token))throw new IllegalArgumentException("Expected "+token+" in "+tokens); }
    private double expression(int minimum) {
        double left=atom();
        while(index<tokens.size()) {
            String operator=tokens.get(index); int p=precedence(operator); if(p<minimum)break; index++;
            double right=expression(p+1);
            left=switch(operator) {
                case "||" -> left!=0||right!=0?1:0; case "&&" -> left!=0&&right!=0?1:0;
                case "|" -> (long)left|(long)right; case "&" -> (long)left&(long)right; case "^" -> (long)left^(long)right;
                case "==" -> left==right?1:0; case "!=" -> left!=right?1:0;
                case "<" -> left<right?1:0; case ">" -> left>right?1:0; case "<=" -> left<=right?1:0; case ">=" -> left>=right?1:0;
                case "<<" -> (long)left<<(long)right; case ">>" -> (long)left>>(long)right;
                case "+" -> left+right; case "-" -> left-right; case "*" -> left*right; case "/" -> left/right; case "%" -> left%right;
                default -> throw new IllegalArgumentException(operator);
            };
        }
        if(minimum==0&&take("?")){double yes=expression(0);expect(":");double no=expression(0);left=left!=0?yes:no;}
        return left;
    }
    private static int precedence(String op) { return switch(op){case "||"->1;case "&&"->2;case "|"->3;case "^"->4;case "&"->5;case "==","!="->6;case "<",">","<=",">="->7;case "<<",">>"->8;case "+","-"->9;case "*","/","%"->10;default->-1;}; }
    private double atom() {
        if(take("!"))return atom()==0?1:0;if(take("~"))return ~(long)atom();if(take("-"))return -atom();if(take("+"))return atom();
        if(take("(")){double v=expression(0);expect(")");return v;}
        if(index==tokens.size())throw new IllegalArgumentException("Incomplete expression");String token=tokens.get(index++);
        if(token.equals("true"))return 1;if(token.equals("false"))return 0;
        if(Character.isDigit(token.charAt(0))||token.startsWith("."))return token.startsWith("0x")||token.startsWith("0X")?Long.parseUnsignedLong(token.substring(2),16):Double.parseDouble(token.replaceFirst("[fFuU]$",""));
        if(take("(")) {
            var args=new ArrayList<Double>();if(!take(")")){do{args.add(expression(0));}while(take(","));expect(")");}
            return switch(token) {
                case "min" -> Math.min(args.get(0),args.get(1));case "max" -> Math.max(args.get(0),args.get(1));
                case "clamp" -> Math.clamp(args.get(0),args.get(1),args.get(2));case "abs" -> Math.abs(args.getFirst());
                case "floor" -> Math.floor(args.getFirst());case "ceil" -> Math.ceil(args.getFirst());case "round" -> Math.round(args.getFirst());
                case "sqrt" -> Math.sqrt(args.getFirst());case "pow" -> Math.pow(args.get(0),args.get(1));
                case "int" -> (int)(double)args.getFirst();case "float" -> args.getFirst();case "bool" -> args.getFirst()!=0?1:0;
                case "if" -> args.get(0)!=0?args.get(1):args.get(2);
                default -> throw new IllegalArgumentException("Unknown function "+token);
            };
        }
        if(!resolving.add(token))throw new IllegalArgumentException("Expression cycle: "+token);
        try { String value=variables.get(token);if(value==null)throw new IllegalArgumentException("Unknown variable "+token);return new PackExpression(value,variables,resolving).result(); }
        finally { resolving.remove(token); }
    }
}
