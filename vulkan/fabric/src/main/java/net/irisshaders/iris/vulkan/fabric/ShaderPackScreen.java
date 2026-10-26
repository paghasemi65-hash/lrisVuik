package net.irisshaders.iris.vulkan.fabric;

import net.irisshaders.iris.vulkan.pack.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Native Minecraft UI for selection, profiles, every discovered option, and compiler diagnostics. */
public final class ShaderPackScreen extends Screen {
    private final Screen parent;
    private int page,mode;
    private String localError="";
    private List<String> diagnosticLines=List.of();
    private ShaderPackLoader.Loaded displayed;
    private List<CompilationDiagnostic> displayedDiagnostics=List.of();
    private ShaderPackLoader.Selection draft;
    public ShaderPackScreen(Screen parent){super(Component.literal("Iris Vulkan Shaderpacks"));this.parent=parent;}
    protected void init(){
        var packs=LiveShaderPacks.active();
        if(packs!=null){displayed=packs.loaded();displayedDiagnostics=packs.diagnostics();if(draft==null)draft=packs.selection();}
        button("Apply",width/2-104,height-26,100,()->action(()->apply()));
        button("Done",width/2+4,height-26,100,()->action(()->{apply();onClose();}));
        if(packs==null){localError="The Vulkan renderer is not available.";return;}
        button("Packs",width/2-154,32,100,()->{mode=0;page=0;rebuildWidgets();});
        button("Options",width/2-50,32,100,()->{mode=1;page=0;rebuildWidgets();});
        button("Diagnostics",width/2+54,32,100,()->{mode=2;page=0;rebuildWidgets();});
        int count=Math.max(1,(height-150)/24);
        try {
            if(mode==0) {
                var names=new ArrayList<String>();names.add("Off");names.addAll(packs.discover());int start=Math.min(page*count,Math.max(0,names.size()-1));
                for(int i=start;i<Math.min(start+count,names.size());i++){String name=names.get(i);String pack=name.equals("Off")?"":name;button((draft.pack().equals(pack)?"> ":"")+name,width/2-150,62+(i-start)*24,300,()->action(()->draft=packs.savedSelection(pack)));}
                pagination(names.size(),count);
            }else if(mode==1) {
                var loaded=packs.loaded();if(loaded==null||!loaded.name().equals(draft.pack())){localError="Apply this pack and wait for compilation to edit its options.";return;}
                var entries=new ArrayList<>(loaded.options().declarations().values());
                button("Profile: "+(draft.profile().isEmpty()?"Custom":draft.profile()),width/2-150,62,300,()->action(()->{
                    var profiles=new ArrayList<String>();profiles.add("");profiles.addAll(loaded.properties().profileNames());String profile=profiles.get((profiles.indexOf(draft.profile())+1)%profiles.size());draft=new ShaderPackLoader.Selection(loaded.name(),profile,Map.of());
                }));
                var selected=new HashMap<String,String>();if(!draft.profile().isEmpty())selected.putAll(loaded.properties().profile(draft.profile()).options());selected.putAll(draft.overrides());
                var values=loaded.options().values(selected);
                int rows=Math.max(1,count-1),start=Math.min(page*rows,Math.max(0,entries.size()-1));
                for(int i=start;i<Math.min(start+rows,entries.size());i++){var option=entries.get(i);String value=values.get(option.name());
                    button(option.name()+": "+value,width/2-150,86+(i-start)*24,300,()->action(()->{var overrides=new HashMap<>(draft.overrides());overrides.put(option.name(),option.values().get((option.values().indexOf(value)+1)%option.values().size()));draft=new ShaderPackLoader.Selection(loaded.name(),draft.profile(),overrides);}));}
                pagination(entries.size(),rows);
            }else{
                var lines=new ArrayList<String>();for(var diagnostic:packs.diagnostics())for(String line:diagnostic.display().split("\\R"))for(int i=0;i<Math.max(1,line.length());i+=90)lines.add(line.substring(Math.min(i,line.length()),Math.min(line.length(),i+90)));
                if(lines.isEmpty())lines.add("No compilation errors.");diagnosticLines=List.copyOf(lines);pagination(lines.size(),Math.max(1,(height-150)/10));
            }
        }catch(Exception e){localError=e.getMessage();}
    }
    private void pagination(int total,int count){int pages=Math.max(1,(total+count-1)/count);page=Math.min(page,pages-1);button("<",width/2-150,height-82,40,()->{page=Math.floorMod(page-1,pages);rebuildWidgets();});button(">",width/2+110,height-82,40,()->{page=(page+1)%pages;rebuildWidgets();});}
    public void tick(){super.tick();var packs=LiveShaderPacks.active();if(packs!=null&&(displayed!=packs.loaded()||displayedDiagnostics!=packs.diagnostics())){localError="";rebuildWidgets();}}
    private void button(String text,int x,int y,int w,Runnable action){addRenderableWidget(Button.builder(Component.literal(text),b->action.run()).bounds(x,y,w,20).build());}
    @FunctionalInterface private interface Action{void run()throws Exception;}
    private void apply()throws Exception{
        var packs=LiveShaderPacks.active();if(packs==null||draft==null)return;
        if(!draft.equals(packs.selection()))packs.select(draft);
        else if(!packs.diagnostics().isEmpty())packs.reload();
    }
    private void action(Action action){try{action.run();localError="";}catch(Exception e){localError=e.getMessage();}rebuildWidgets();}
    public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){
        super.extractRenderState(g,mouseX,mouseY,delta);g.centeredText(font,title,width/2,12,0xFFFFFFFF);
        var packs=LiveShaderPacks.active();String status=localError.isEmpty()?(packs==null?"Vulkan unavailable":packs.status()):localError;
        g.centeredText(font,font.plainSubstrByWidth(status,width-20),width/2,height-54,0xFFFFFFFF);
        g.centeredText(font,"Pack configuration is available. World shader effects are not active yet.",width/2,height-42,0xFFAAAAAA);
        if(mode==2){int rows=Math.max(1,(height-150)/10),start=page*rows;for(int i=start;i<Math.min(diagnosticLines.size(),start+rows);i++)g.text(font,diagnosticLines.get(i),10,62+(i-start)*10,0xFFFFFFFF);}
    }
    public void onClose(){minecraft.gui.setScreen(parent);}
}
