package net.irisshaders.iris.vulkan.neoforge;

import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.util.*;

/** Do not apply the stencil retirement repair to versions whose destroy method already owns these handles. */
public final class NeoForgeCompatibilityPlugin implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        if(!mixin.endsWith("StencilPipelineRetirementMixin"))return true;
        try {
            var node=MixinService.getService().getBytecodeProvider().getClassNode(target);
            var destroy=node.methods.stream().filter(m->m.name.equals("destroy")&&m.desc.equals("()V")).findFirst().orElseThrow();
            for(var instruction:destroy.instructions)if(instruction instanceof FieldInsnNode field&&
                (field.name.equals("withStencilPipeline")||field.name.equals("withDepthStencilPipeline")))return false;
            return true;
        }catch(Exception failure){throw new IllegalStateException("Cannot audit NeoForge stencil pipeline retirement",failure);}
    }
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String name,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String name,ClassNode node,String mixin,IMixinInfo info){}
}
