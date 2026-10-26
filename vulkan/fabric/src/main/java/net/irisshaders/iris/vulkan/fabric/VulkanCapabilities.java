package net.irisshaders.iris.vulkan.fabric;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import net.irisshaders.iris.vulkan.VulkanContextBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.*;
import static org.lwjgl.vulkan.VK13.*;

public record VulkanCapabilities(String device, int api, int driver, List<String> missing) {
    public static VulkanCapabilities query(VulkanContextBridge context, FeatureSet enabled) {
        var missing = new ArrayList<String>();
        try (var stack = MemoryStack.stackPush()) {
            var synchronization = VkPhysicalDeviceSynchronization2Features.calloc(stack).sType$Default();
            var rendering = VkPhysicalDeviceDynamicRenderingFeatures.calloc(stack).sType$Default().pNext(synchronization.address());
            var features = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(rendering.address());
            vkGetPhysicalDeviceFeatures2(context.physicalDevice(), features);
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(context.physicalDevice(), properties);
            var names = enabled.features().stream().map(f -> f.name()).collect(java.util.stream.Collectors.toSet());
            if (!rendering.dynamicRendering() || !names.contains("dynamicRendering")) missing.add("dynamicRendering supported AND enabled");
            if (!synchronization.synchronization2() || !names.contains("synchronization2")) missing.add("synchronization2 supported AND enabled");
            if (!context.device().getCapabilities().Vulkan12) missing.add("Vulkan 1.2 logical device");
            if (properties.limits().maxColorAttachments() < 1 || properties.limits().maxPerStageDescriptorSamplers() < 2)
                missing.add("one color attachment and two fragment samplers");
            return new VulkanCapabilities(properties.deviceNameString(), properties.apiVersion(), properties.driverVersion(), List.copyOf(missing));
        }
    }
}
