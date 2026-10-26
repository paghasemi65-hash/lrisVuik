package net.irisshaders.iris.vulkan.fabric.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Names and signatures verified against the official 26.3 client, SHA-1 e877b6a07acd633fb3bb475002175cec036e7b87. */
@Mixin(value=VulkanCommandEncoder.class,remap=false)
public interface VulkanCommandEncoderAccessor {
    @Invoker("currentCommandPool") com.mojang.renderpearl.backend.vulkan.VulkanCommandPool iris$currentCommandPool();
    @Invoker("commandBuffer") VkCommandBuffer iris$commandBuffer();
    @Accessor("currentRenderPass") VulkanRenderPass iris$currentRenderPass();
    @Accessor("currentSubmitIndex") long iris$currentSubmitIndex();
    @Accessor("completedSubmitIndex") long iris$completedSubmitIndex();
}
