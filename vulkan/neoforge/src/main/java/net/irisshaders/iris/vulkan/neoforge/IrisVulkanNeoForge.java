package net.irisshaders.iris.vulkan.neoforge;

import net.irisshaders.iris.vulkan.fabric.ShaderControls;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Loader-owned input registration; renderer/world/resource lifetimes use the shared native hooks. */
@Mod(value="iris_vulkan_engine", dist=Dist.CLIENT)
public final class IrisVulkanNeoForge {
    public IrisVulkanNeoForge(IEventBus events) {
        events.addListener(IrisVulkanNeoForge::registerKeys);
    }
    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(ShaderControls.TOGGLE);
        event.register(ShaderControls.RELOAD);
    }
}
