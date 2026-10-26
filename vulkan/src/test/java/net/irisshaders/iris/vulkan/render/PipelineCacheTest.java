package net.irisshaders.iris.vulkan.render;

import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class PipelineCacheTest {
    @Test void detectsPayloadCorruptionBeforePassingOpaqueBytesToTheDriver(){
        byte[] data=new byte[80];data[70]=42;byte[] stored=VulkanPipelineCache.encode(data);
        assertArrayEquals(data,VulkanPipelineCache.decode(stored));
        stored[100]^=1;assertNull(VulkanPipelineCache.decode(stored));
        assertNull(VulkanPipelineCache.decode(new byte[31]));
    }
    @Test void rejectsTruncationForeignDevicesAndUnknownHeadersBeforeDriverReads() {
        byte[] uuid=new byte[16];uuid[0]=42;
        byte[] header=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).putInt(32).putInt(1).putInt(10).putInt(20).put(uuid).array();
        assertTrue(VulkanPipelineCache.compatible(header,10,20,uuid));
        assertFalse(VulkanPipelineCache.compatible(new byte[31],10,20,uuid));
        assertFalse(VulkanPipelineCache.compatible(header,11,20,uuid));
        assertFalse(VulkanPipelineCache.compatible(header,10,21,uuid));
        assertFalse(VulkanPipelineCache.compatible(header,10,20,new byte[16]));
        header[4]=2;assertFalse(VulkanPipelineCache.compatible(header,10,20,uuid));
    }
}
