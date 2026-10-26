package net.irisshaders.iris.vulkan;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RetirementQueueTest {
    @Test void completionSerialControlsRetirementAcrossFrameSlotReuse() {
        var queue = new DeferredGpuRetirementQueue(); var destroyed = new ArrayList<Integer>();
        queue.retire(2, () -> destroyed.add(2)); queue.retire(4, () -> destroyed.add(4));
        queue.retire(3, () -> destroyed.add(3));
        queue.collect(1); assertTrue(destroyed.isEmpty());
        queue.collect(3); assertEquals(List.of(2, 3), destroyed); assertEquals(1, queue.pendingCount());
        queue.collect(3); assertEquals(2, destroyed.size());
        queue.drainAfterQueueIdle(); queue.drainAfterQueueIdle();
        assertEquals(List.of(2, 3, 4), destroyed);
        assertThrows(IllegalStateException.class, () -> queue.retire(5, () -> {}));
    }
    @Test void destructorFailureDoesNotLeakOtherRetiredObjects() {
        var queue = new DeferredGpuRetirementQueue(); var destroyed = new ArrayList<Integer>();
        queue.retire(8, () -> { throw new IllegalStateException("failure"); });
        queue.retire(8, () -> destroyed.add(8)); queue.retire(9, () -> destroyed.add(9));
        assertThrows(IllegalStateException.class, queue::drainAfterQueueIdle);
        assertEquals(List.of(8, 9), destroyed); assertEquals(0, queue.pendingCount());
    }
}
