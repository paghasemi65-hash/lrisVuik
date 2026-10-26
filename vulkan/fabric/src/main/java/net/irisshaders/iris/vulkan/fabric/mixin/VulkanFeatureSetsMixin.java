package net.irisshaders.iris.vulkan.fabric.mixin;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;
/** Let RenderPearl query and enable supported pack features during its real device creation. */
@Mixin(value=VulkanFeatureSets.class,remap=false)
public abstract class VulkanFeatureSetsMixin {
    @Inject(method="optionalFeatureSets()Ljava/util/Set;",at=@At("RETURN"),cancellable=true)
    private static void iris$packFeatures(CallbackInfoReturnable<Set<FeatureSet>> ci) {
        var result=new LinkedHashSet<>(ci.getReturnValue());
        for(String name:List.of("independentBlend","fillModeNonSolid","imageCubeArray","shaderStorageImageExtendedFormats","shaderStorageImageReadWithoutFormat","shaderStorageImageWriteWithoutFormat","shaderSampledImageArrayDynamicIndexing","shaderStorageImageArrayDynamicIndexing"))
            result.add(new FeatureSet("Iris "+name,Set.of(),Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT,name))));
        ci.setReturnValue(result);
    }
}
