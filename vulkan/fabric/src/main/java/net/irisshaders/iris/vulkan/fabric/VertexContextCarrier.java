package net.irisshaders.iris.vulkan.fabric;

/** Carries the originating entity through deferred feature preparation, without retaining the entity. */
public interface VertexContextCarrier {
    VertexDataContext.Value iris$vertexContext();
}
