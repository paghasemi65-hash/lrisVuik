package net.irisshaders.iris.vulkan.fabric.mixin;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value = VulkanCommandPool.class, remap = false)
public interface VulkanCommandPoolAccessor {
    @Accessor("commandPool") long iris$handle();
}
