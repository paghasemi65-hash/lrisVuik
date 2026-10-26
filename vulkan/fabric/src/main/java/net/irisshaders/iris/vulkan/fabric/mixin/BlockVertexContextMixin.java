package net.irisshaders.iris.vulkan.fabric.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.vulkan.fabric.VertexDataContext;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=ModelBlockRenderer.class,remap=false)
public abstract class BlockVertexContextMixin {
    @WrapMethod(method="tesselateBlock")
    private void iris$block(BlockQuadOutput output,float x,float y,float z,BlockAndTintGetter level,BlockPos pos,BlockState state,BlockStateModel model,long seed,Operation<Void> original){
        try(var scope=VertexDataContext.block(state,x,y,z,false)){original.call(output,x,y,z,level,pos,state,model,seed);}
    }
}
