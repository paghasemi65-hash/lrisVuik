package net.irisshaders.iris.vulkan;

import org.lwjgl.vulkan.*;

/** RenderPearl enables the KHR features on a Vulkan 1.2 logical device. */
public final class VulkanDispatch {
    private VulkanDispatch() {}
    public static void vkCmdBeginRendering(VkCommandBuffer cmd, VkRenderingInfo info) {
        if (cmd.getDevice().getCapabilities().Vulkan13) VK13.vkCmdBeginRendering(cmd, info);
        else KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, info);
    }
    public static void vkCmdEndRendering(VkCommandBuffer cmd) {
        if (cmd.getDevice().getCapabilities().Vulkan13) VK13.vkCmdEndRendering(cmd);
        else KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
    }
    public static void vkCmdPipelineBarrier2(VkCommandBuffer cmd, VkDependencyInfo info) {
        if (cmd.getDevice().getCapabilities().Vulkan13) VK13.vkCmdPipelineBarrier2(cmd, info);
        else KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, info);
    }
}
