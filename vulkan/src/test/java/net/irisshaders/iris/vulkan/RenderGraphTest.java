package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.vulkan.render.VulkanRenderGraph;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static net.irisshaders.iris.vulkan.render.VulkanRenderGraph.Phase.*;

class RenderGraphTest {
    @Test void fullFramePlacesDeferredBetweenOpaqueDepthAndTranslucency() {
        var actual=new ArrayList<String>();var passes=new ArrayList<VulkanRenderGraph.Pass>();
        var phases=List.of(FRAME_START,PREPARE,SHADOW,SHADOWCOMP,GBUFFERS_OPAQUE,DEPTH_BEFORE_TRANSLUCENT,DEFERRED,GBUFFERS_TRANSLUCENT,DEPTH_BEFORE_HAND,HAND,COMPOSITE,FINAL);
        for(var phase:phases)passes.add(new VulkanRenderGraph.Pass(phase.name(),phase,Set.of(),Set.of(),Set.of(),()->actual.add(phase.name())));
        var graph=new VulkanRenderGraph(passes,Set.of());graph.execute();assertEquals(phases.stream().map(Enum::name).toList(),actual);
        // An explicit dependency cannot move deferred past water, even when the resources do not overlap.
        assertThrows(IllegalArgumentException.class,()->new VulkanRenderGraph(List.of(passes.get(7),passes.get(6)),Set.of()));
    }
    @Test void keepsOpaqueDepthTranslucentCompositeOrder(){
        var recorded=new ArrayList<String>();
        var graph=new VulkanRenderGraph(List.of(
            new VulkanRenderGraph.Pass("opaque",GBUFFERS_OPAQUE,Set.of(),Set.of("depth0","color"),Set.of(),()->recorded.add("opaque")),
            new VulkanRenderGraph.Pass("depth1",DEPTH_BEFORE_TRANSLUCENT,Set.of("depth0"),Set.of("depth1"),Set.of(),()->recorded.add("depth1")),
            new VulkanRenderGraph.Pass("water",GBUFFERS_TRANSLUCENT,Set.of("depth1"),Set.of("color"),Set.of(),()->recorded.add("water")),
            new VulkanRenderGraph.Pass("composite",COMPOSITE,Set.of("color"),Set.of("final"),Set.of(),()->recorded.add("composite"))),Set.of());
        assertTrue(recorded.isEmpty());graph.execute();assertEquals(graph.order(),recorded);
    }
    @Test void rejectsReadBeforeWriteAndDependencyCycles(){
        assertThrows(IllegalArgumentException.class,()->new VulkanRenderGraph(List.of(new VulkanRenderGraph.Pass("bad",COMPOSITE,Set.of("missing"),Set.of(),Set.of(),()->{})),Set.of()));
        assertThrows(IllegalArgumentException.class,()->new VulkanRenderGraph(List.of(
            new VulkanRenderGraph.Pass("a",COMPOSITE,Set.of(),Set.of(),Set.of("b"),()->{}),
            new VulkanRenderGraph.Pass("b",COMPOSITE,Set.of(),Set.of(),Set.of("a"),()->{})),Set.of()));
    }
}
