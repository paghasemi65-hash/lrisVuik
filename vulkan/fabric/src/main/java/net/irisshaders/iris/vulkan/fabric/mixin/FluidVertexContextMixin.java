package net.irisshaders.iris.vulkan.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.vulkan.fabric.VertexDataContext;
import net.minecraft.client.renderer.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=FluidRenderer.class,remap=false)
public abstract class FluidVertexContextMixin {
    @WrapMethod(method="tesselate")
    private void iris$fluid(BlockAndTintGetter level,BlockPos pos,FluidRenderer.Output output,BlockState state,FluidState fluid,Operation<Void> original){
        try(var scope=VertexDataContext.block(fluid.createLegacyBlock(),pos.getX()&15,pos.getY()&15,pos.getZ()&15,true)){original.call(level,pos,output,state,fluid);}
    }
}
