package net.irisshaders.iris.vulkan;

import java.util.*;

/** Render-thread queue keyed by GPU submission serial, never by elapsed CPU frames. */
public final class DeferredGpuRetirementQueue {
    private final NavigableMap<Long, List<Runnable>> pending = new TreeMap<>();
    private long completed = -1;
    private boolean closed;
    public void retire(long lastSubmission, Runnable destructor) {
        Objects.requireNonNull(destructor);
        if (closed) throw new IllegalStateException("Retirement queue closed");
        if (lastSubmission < 0) throw new IllegalArgumentException("Submission serial");
        if (lastSubmission <= completed) destructor.run();
        else pending.computeIfAbsent(lastSubmission, ignored -> new ArrayList<>()).add(destructor);
    }
    public void collect(long completedSubmission) {
        if (completedSubmission < completed) throw new IllegalArgumentException("Completion serial moved backwards");
        completed = completedSubmission;
        RuntimeException failure = null;
        while (!pending.isEmpty() && pending.firstKey() <= completed) {
            for (var destructor : pending.pollFirstEntry().getValue()) {
                try { destructor.run(); }
                catch (RuntimeException e) {
                    if (failure == null) failure = new IllegalStateException("GPU resource destruction failed");
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
    }
    /** Call only after the host graphics queue has actually become idle. */
    public void drainAfterQueueIdle() {
        if (closed) return;
        try { collect(Long.MAX_VALUE); } finally { closed = true; }
    }
    public int pendingCount() { return pending.values().stream().mapToInt(List::size).sum(); }
}
