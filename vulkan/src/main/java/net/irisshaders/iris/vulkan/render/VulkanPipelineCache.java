package net.irisshaders.iris.vulkan.render;

import net.irisshaders.iris.vulkan.VulkanContextBridge;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.nio.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.HexFormat;
import java.util.Arrays;
import java.security.MessageDigest;
import static org.lwjgl.vulkan.VK13.*;
import static net.irisshaders.iris.vulkan.VulkanContextBridge.check;

/** Driver-owned cache bytes, accepted only for this device, driver and pipeline-cache UUID. */
final class VulkanPipelineCache implements AutoCloseable {
    private static final System.Logger LOG=System.getLogger(VulkanPipelineCache.class.getName());
    private static final int MAX_BYTES=128*1024*1024;
    private final VulkanContextBridge context;
    private final Path file;
    final long handle;
    private final int vendor,device;
    private final byte[] uuid=new byte[VK_UUID_SIZE];
    VulkanPipelineCache(VulkanContextBridge context,Path directory) {
        this.context=context;
        try(var s=MemoryStack.stackPush()) {
            var properties=VkPhysicalDeviceProperties.calloc(s);vkGetPhysicalDeviceProperties(context.physicalDevice(),properties);
            vendor=properties.vendorID();device=properties.deviceID();properties.pipelineCacheUUID().get(uuid);
            file=directory==null?null:directory.resolve(Integer.toHexString(vendor)+"-"+Integer.toHexString(device)+"-"+
                Integer.toUnsignedString(properties.driverVersion())+"-"+HexFormat.of().formatHex(uuid)+"-v1.bin");
            byte[] bytes=read();ByteBuffer initial=bytes==null?null:MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
            try {
                var info=VkPipelineCacheCreateInfo.calloc(s).sType$Default().pInitialData(initial);var out=s.mallocLong(1);
                int result=vkCreatePipelineCache(context.device(),info,null,out);
                if(result!=VK_SUCCESS&&initial!=null){LOG.log(System.Logger.Level.WARNING,"Driver rejected cached pipelines: "+result);info.pInitialData(null);result=vkCreatePipelineCache(context.device(),info,null,out);}
                check(result,"pipeline cache");handle=out.get(0);
            } finally {if(initial!=null)MemoryUtil.memFree(initial);}
        }
    }
    private byte[] read() {
        if(file==null)return null;
        try {
            if(!Files.isRegularFile(file)||Files.size(file)>MAX_BYTES)return null;
            byte[] bytes=decode(Files.readAllBytes(file));
            if(bytes==null)return null;
            if(!compatible(bytes,vendor,device,uuid))return null;
            return bytes;
        }catch(IOException failure){LOG.log(System.Logger.Level.WARNING,"Cannot read pipeline cache "+file,failure);return null;}
    }
    static boolean compatible(byte[] bytes,int vendor,int device,byte[] uuid) {
        if(bytes.length<32||uuid.length!=VK_UUID_SIZE)return false;
        var data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(data.getInt(0)!=32||data.getInt(4)!=VK_PIPELINE_CACHE_HEADER_VERSION_ONE||data.getInt(8)!=vendor||data.getInt(12)!=device)return false;
        for(int i=0;i<VK_UUID_SIZE;i++)if(bytes[16+i]!=uuid[i])return false;
        return true;
    }
    private static byte[] digest(byte[] bytes){
        try{return MessageDigest.getInstance("SHA-256").digest(bytes);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    static byte[] encode(byte[] bytes){return ByteBuffer.allocate(32+bytes.length).put(digest(bytes)).put(bytes).array();}
    static byte[] decode(byte[] stored){
        if(stored.length<64)return null;
        byte[] bytes=Arrays.copyOfRange(stored,32,stored.length);
        return MessageDigest.isEqual(Arrays.copyOf(stored,32),digest(bytes))?bytes:null;
    }
    @Override public void close() {
        try {
            if(file!=null)try(var s=MemoryStack.stackPush()) {
                var size=s.mallocPointer(1);check(vkGetPipelineCacheData(context.device(),handle,size,null),"pipeline cache size");
                if(size.get(0)>0&&size.get(0)<=MAX_BYTES){
                    var data=MemoryUtil.memAlloc((int)size.get(0));
                    try {
                        check(vkGetPipelineCacheData(context.device(),handle,size,data),"pipeline cache bytes");
                        byte[] bytes=new byte[(int)size.get(0)];data.get(bytes);
                        Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"pipeline-",".tmp");
                        try {Files.write(temporary,encode(bytes));try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}}
                        finally{Files.deleteIfExists(temporary);}
                    }finally{MemoryUtil.memFree(data);}
                }
            }
        }catch(IOException|RuntimeException failure){LOG.log(System.Logger.Level.WARNING,"Cannot persist pipeline cache "+file,failure);}
        finally{vkDestroyPipelineCache(context.device(),handle,null);}
    }
}
