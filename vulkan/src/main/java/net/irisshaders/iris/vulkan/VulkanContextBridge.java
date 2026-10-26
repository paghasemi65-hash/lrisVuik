package net.irisshaders.iris.vulkan;

import org.lwjgl.vulkan.*;
import java.util.Objects;

/** Borrows Minecraft's device and recording stream. Never creates a window, submits, or destroys the device. */
public final class VulkanContextBridge {
    public interface Host {
        VkCommandBuffer commandBufferOutsideRenderPass();
        long submissionIndex();
        long completedSubmissionIndex();
        int submissionsInFlight();
        void retireAfterGpu(Runnable destructor);
    }
    public record Frame(VkCommandBuffer commands, long submission, long completedSubmission, int slot) {}
    public record Surface(int format, int width, int height, int imageIndex) {}
    private static VulkanContextBridge active;
    private final VkInstance instance;
    private final VkDevice device;
    private final long allocator;
    private final int graphicsQueueFamily;
    private final Host host;
    private final Thread renderThread;
    private Surface surface;
    private final java.util.Set<String> enabledFeatures;
    public VulkanContextBridge(VkInstance instance, VkDevice device, long allocator, int family, Host host) {
        this(instance,device,allocator,family,host,java.util.Set.of());
    }
    public VulkanContextBridge(VkInstance instance, VkDevice device, long allocator, int family, Host host, java.util.Set<String> enabledFeatures) {
        this.enabledFeatures=java.util.Set.copyOf(enabledFeatures);
        this.instance = Objects.requireNonNull(instance); this.device = Objects.requireNonNull(device);
        if (allocator == 0 || family < 0) throw new IllegalArgumentException("Invalid borrowed Vulkan handles");
        this.allocator = allocator; this.graphicsQueueFamily = family; this.host = Objects.requireNonNull(host);
        renderThread = Thread.currentThread();
        var caps = device.getCapabilities();
        if (!caps.Vulkan13 && !(caps.VK_KHR_dynamic_rendering && caps.VK_KHR_synchronization2))
            throw new IllegalStateException("Iris requires dynamic rendering and synchronization2 on the logical device");
    }
    public static void attach(VulkanContextBridge context) {
        if (active != null) throw new IllegalStateException("A Vulkan context is already attached");
        active = Objects.requireNonNull(context);
    }
    public static VulkanContextBridge require() {
        if (active == null) throw new IllegalStateException("Minecraft Vulkan backend is not active");
        active.checkThread(); return active;
    }
    public static void detach(VkDevice device) { if (active != null && active.device.address() == device.address()) { active.checkThread(); active = null; } }
    public void checkThread() { if (Thread.currentThread() != renderThread) throw new IllegalStateException("Vulkan recording must run on the render thread"); }
    public Frame frame() {
        checkThread(); var cmd = host.commandBufferOutsideRenderPass();
        if (cmd == null || cmd.getDevice().address() != device.address()) throw new IllegalStateException("Invalid active command buffer");
        long submission = host.submissionIndex();
        return new Frame(cmd, submission, host.completedSubmissionIndex(), Math.floorMod(submission, host.submissionsInFlight()));
    }
    public void retire(Runnable destructor) { checkThread(); host.retireAfterGpu(destructor); }
    public void updateSurface(Surface value) { checkThread(); surface = value; }
    public Surface surface() { checkThread(); if (surface == null) throw new IllegalStateException("No acquired swapchain image"); return surface; }
    public VkInstance instance() { return instance; }
    public VkDevice device() { return device; }
    public VkPhysicalDevice physicalDevice() { return device.getPhysicalDevice(); }
    public long allocator() { return allocator; }
    public int graphicsQueueFamily() { return graphicsQueueFamily; }
    public int framesInFlight() { return host.submissionsInFlight(); }
    public boolean enabledFeature(String name){return enabledFeatures.contains(name);}
    public static void check(int result, String operation) { if (result != VK10.VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult=" + result); }
}
