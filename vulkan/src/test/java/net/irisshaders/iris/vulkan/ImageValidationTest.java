package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.vulkan.pack.*;
import net.irisshaders.iris.vulkan.render.VulkanRenderTargets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.VK13.*;

class ImageValidationTest {
    @Test void viewLayerCountsMatchVulkanImageShapes() {
        for(var shape:new PackResourcePlan.Shape[]{PackResourcePlan.Shape.D1,PackResourcePlan.Shape.D2})
            assertThrows(IllegalArgumentException.class,()->new VulkanRenderTargets.Spec(4,1,1,2,1,VK_FORMAT_R8G8B8A8_UNORM,shape,false,false,false));
        assertThrows(IllegalArgumentException.class,()->new VulkanRenderTargets.Spec(4,4,1,12,1,VK_FORMAT_R8G8B8A8_UNORM,PackResourcePlan.Shape.CUBE,false,false,false));
        assertDoesNotThrow(()->new VulkanRenderTargets.Spec(4,4,1,12,1,VK_FORMAT_R8G8B8A8_UNORM,PackResourcePlan.Shape.CUBE_ARRAY,false,false,false));
    }
    @Test void uploadSizesCoverPackedFloatIntegerAndDepthFormats() {
        for(String name:new String[]{"RGBA8","RGBA16F","RGBA32F","R11F_G11F_B10F","RGB10_A2UI","RGB565","R8UI","DEPTH_COMPONENT16","DEPTH_COMPONENT32F"}) {
            var format=TextureFormat.of(name);
            assertEquals(format.bytes(),TextureFormat.bytesPerTexel(format.vk()),name);
        }
        assertThrows(IllegalArgumentException.class,()->TextureFormat.bytesPerTexel(VK_FORMAT_BC1_RGB_UNORM_BLOCK));
    }
}
