package net.irisshaders.iris.vulkan.fabric;

import net.irisshaders.iris.vulkan.pack.ShaderPackLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import java.util.*;

/** Immutable per-pack ID snapshot, with exception-safe per-worker emission scopes. */
public final class VertexDataContext implements AutoCloseable {
    public record Value(int id,int renderType,float centerX,float centerY,float centerZ,int emission,boolean block) {}
    private record Ids(ShaderPackLoader.Loaded pack,Map<BlockState,Integer> blocks) {}
    private static volatile Ids ids;
    private static final ThreadLocal<Value> CURRENT=new ThreadLocal<>();
    private final Value previous;
    private VertexDataContext(Value value){previous=CURRENT.get();if(value==null)CURRENT.remove();else CURRENT.set(value);}
    public static boolean active(){return ids!=null;}
    public static Value current(){return CURRENT.get();}
    public static VertexDataContext restore(Value value){return new VertexDataContext(value);}
    public static void select(ShaderPackLoader.Loaded pack) {
        var old=ids;if(old!=null&&old.pack()==pack||old==null&&pack==null)return;
        if(pack==null){ids=null;return;}
        var resolved=new IdentityHashMap<BlockState,Integer>();
        for(var block:BuiltInRegistries.BLOCK)for(var state:block.getStateDefinition().getPossibleStates()){
            var properties=new HashMap<String,String>();state.getValues().forEach(value->properties.put(value.property().getName(),value.valueName()));
            resolved.put(state,pack.blockIds().resolve(BuiltInRegistries.BLOCK.getKey(block).toString(),properties));
        }
        ids=new Ids(pack,Collections.unmodifiableMap(resolved));
    }
    public static VertexDataContext block(BlockState state,float x,float y,float z,boolean fluid) {
        var snapshot=ids;
        return new VertexDataContext(snapshot==null?null:new Value(snapshot.blocks().getOrDefault(state,-1),fluid?1:-1,x+.5f,y+.5f,z+.5f,state.getLightEmission(),true));
    }
    public static VertexDataContext model(Object state) {
        var snapshot=ids;String name=null;
        if(snapshot!=null&&state instanceof EntityRenderState entity&&entity.entityType!=null)name=BuiltInRegistries.ENTITY_TYPE.getKey(entity.entityType).toString();
        if(snapshot!=null&&state instanceof BlockEntityRenderState block&&block.blockEntityType!=null)name=BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(block.blockEntityType).toString();
        return new VertexDataContext(name==null?CURRENT.get():new Value(snapshot.pack().entityIds().resolve(name,Map.of()),-1,0,0,0,0,false));
    }
    @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
