package net.irisshaders.iris.vulkan.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import java.util.Arrays;

/** Native SDL-backed, rebindable keys. Polling queues selection changes without recording GPU work. */
public final class ShaderControls {
    private static final KeyMapping.Category CATEGORY=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("iris_vulkan_engine","shaders"));
    public static final KeyMapping TOGGLE=new KeyMapping("key.iris_vulkan_engine.toggle",InputConstants.KEY_K,CATEGORY);
    public static final KeyMapping RELOAD=new KeyMapping("key.iris_vulkan_engine.reload",InputConstants.KEY_R,CATEGORY);
    public static KeyMapping[] register(KeyMapping[] original) {
        var mappings=Arrays.copyOf(original,original.length+2);
        mappings[original.length]=TOGGLE;mappings[original.length+1]=RELOAD;return mappings;
    }
    public static void poll() {
        var mc=Minecraft.getInstance();var packs=LiveShaderPacks.active();
        boolean available=packs!=null&&mc.level!=null&&mc.gui.screen()==null&&mc.gui.overlay()==null;
        while(TOGGLE.consumeClick())if(available)try{packs.toggle();}catch(Exception failure){notifyFailure(mc,failure);}
        boolean reload=false;while(RELOAD.consumeClick())reload=true;
        if(available&&reload&&!packs.selection().pack().isEmpty())packs.reload();
    }
    private static void notifyFailure(Minecraft mc,Exception failure) {
        SystemToast.add(mc.gui.toastManager(),new SystemToast.SystemToastId(5000),Component.literal("Iris Vulkan"),Component.literal(failure.getMessage()));
    }
}
