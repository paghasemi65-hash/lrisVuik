package net.irisshaders.iris.vulkan.fabric.mixin;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.irisshaders.iris.vulkan.fabric.IrisVulkanLifecycle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value = GameRenderer.class, remap = false)
public abstract class GameRendererMixin {
    @Inject(method="render()V",at=@At("HEAD"))
    private void iris$controls(CallbackInfo ci){
        net.irisshaders.iris.vulkan.fabric.ShaderControls.poll();
    }
    @ModifyArg(method = "renderLevel()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0)
    private org.joml.Matrix4f iris$captureProjection(org.joml.Matrix4f projection) {
        IrisVulkanLifecycle.captureWorld((GameRenderer)(Object)this, projection);
        return projection;
    }
    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void iris$beforeGui(CallbackInfo ci) { IrisVulkanLifecycle.beforeGui((GameRenderer) (Object) this); }
    @Inject(method = "resize(II)V", at = @At("HEAD"))
    private void iris$resize(int width, int height, CallbackInfo ci) { IrisVulkanLifecycle.requestReset("window resize"); }
    @Inject(method = "onResourceManagerReload(Lnet/minecraft/server/packs/resources/ResourceManager;)V", at = @At("RETURN"))
    private void iris$reload(CallbackInfo ci) { IrisVulkanLifecycle.requestReset("resource reload"); }
    @Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("HEAD"))
    private void iris$world(ClientLevel level, CallbackInfo ci) { IrisVulkanLifecycle.worldChanged(); IrisVulkanLifecycle.requestReset(level == null ? "world unload" : "world load"); }
    @Inject(method = "close()V", at = @At("HEAD"))
    private void iris$close(CallbackInfo ci) { IrisVulkanLifecycle.releaseRenderer(); }
}
