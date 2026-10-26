package net.irisshaders.iris.vulkan.fabric;

import com.mojang.renderpearl.backend.vulkan.*;
import net.irisshaders.iris.vulkan.*;
import net.irisshaders.iris.vulkan.fabric.mixin.*;
import org.lwjgl.vulkan.*;

/** Borrowed handles; submission and command-pool rotation remain host-owned. */
public final class RenderPearlHost implements VulkanContextBridge.Host {
    public final VulkanDevice device;
    public final VulkanCommandEncoder encoder;
    public final DeferredGpuRetirementQueue retirement = new DeferredGpuRetirementQueue();
    private final VulkanCommandEncoderAccessor access;
    public RenderPearlHost(VulkanDevice device) {
        this.device = device; encoder = device.createCommandEncoder();
        access = (VulkanCommandEncoderAccessor) encoder;
    }
    public VkCommandBuffer commandBufferOutsideRenderPass() {
        if (access.iris$currentRenderPass() != null) throw new IllegalStateException("Iris entered an active RenderPearl pass");
        return access.iris$commandBuffer();
    }
    public long commandPool() { return ((VulkanCommandPoolAccessor) access.iris$currentCommandPool()).iris$handle(); }
    public VkQueue graphicsQueue() { return device.graphicsQueue().vkQueue(); }
    public long submissionIndex() { return access.iris$currentSubmitIndex(); }
    public long completedSubmissionIndex() { return access.iris$completedSubmitIndex(); }
    public int submissionsInFlight() { return VulkanCommandEncoder.MAX_SUBMITS_IN_FLIGHT; }
    public void retireAfterGpu(Runnable destructor) { retirement.retire(submissionIndex(), destructor); }
}
