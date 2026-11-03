package dev.workhub.work;

import dev.workhub.common.ApiException;

/** One monotonic budget covers authorization, lock waiting, every CTE and diagnostics. */
public final class DependencyBudget {
    private final long started = System.nanoTime();
    private final long deadline;

    public DependencyBudget(int seconds) {
        deadline = started + seconds * 1_000_000_000L;
    }

    public int remainingMillis() {
        long millis = (deadline - System.nanoTime()) / 1_000_000;
        if (millis <= 0) throw ApiException.limit();
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    public long elapsedMicros() {
        return (System.nanoTime() - started) / 1000;
    }

    public void check() {
        remainingMillis();
    }
}
