package net.irisshaders.iris.vulkan.pack;
import java.util.*;
import java.util.regex.Pattern;
public record CompilationDiagnostic(String file, String stage, String coordinateSpace, int line, String snippet, String compilerOutput) {
    public static CompilationDiagnostic of(String file,String stage,String source,Throwable failure,String space) {
        while(failure instanceof java.util.concurrent.CompletionException&&failure.getCause()!=null)failure=failure.getCause();
        String output=failure.getMessage()==null?failure.toString():failure.getMessage();
        var matcher=Pattern.compile(":(\\d+)(?::\\d+)?:\\s*(?:error|warning)",Pattern.CASE_INSENSITIVE).matcher(output);
        int line=matcher.find()?Integer.parseInt(matcher.group(1)):0;
        if(line==0){var parserLine=Pattern.compile("\\bline (\\d+):\\d+").matcher(output);if(parserLine.find())line=Integer.parseInt(parserLine.group(1));}
        if(line==0)for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof org.antlr.v4.runtime.RecognitionException syntax&&syntax.getOffendingToken()!=null){line=syntax.getOffendingToken().getLine();break;}
        var lines=source.split("\\R",-1);var excerpt=new StringBuilder();
        if(line>0)for(int i=Math.max(0,line-3);i<Math.min(lines.length,line+2);i++)excerpt.append(i+1).append(": ").append(lines[i]).append('\n');
        return new CompilationDiagnostic(file,stage,space,line,excerpt.toString(),output);
    }
    public String display(){return file+":"+(line==0?"?":line)+" ["+stage+", "+coordinateSpace+"]\n"+snippet+compilerOutput;}
    public static final class Failure extends RuntimeException {
        private final List<CompilationDiagnostic> diagnostics;
        public Failure(List<CompilationDiagnostic> diagnostics){super(diagnostics.stream().map(CompilationDiagnostic::display).collect(java.util.stream.Collectors.joining("\n")));this.diagnostics=List.copyOf(diagnostics);}
        public List<CompilationDiagnostic> diagnostics(){return diagnostics;}
    }
}
