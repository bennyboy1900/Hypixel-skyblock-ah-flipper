package dev.skytrade.util;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns every background thread used by the mod. Nothing heavy ever runs on the render thread:
 * callers schedule work here and publish immutable results.
 */
public final class TaskManager implements AutoCloseable {
    private final ScheduledExecutorService scheduler;
    private final ExecutorService compute;
    private volatile boolean shutdown;

    public TaskManager() {
        this.scheduler = Executors.newScheduledThreadPool(2, factory("SkyTrade-Scheduler"));
        int cores = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
        this.compute = new ThreadPoolExecutor(cores, cores, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64), factory("SkyTrade-Compute"),
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    private static ThreadFactory factory(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            t.setUncaughtExceptionHandler((th, e) -> Log.error("task.uncaught", e, "thread", th.getName()));
            return t;
        };
    }

    public ScheduledFuture<?> every(String name, long initialDelayMs, long periodMs, Runnable task) {
        return scheduler.scheduleWithFixedDelay(guard(name, task), initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> later(String name, long delayMs, Runnable task) {
        return scheduler.schedule(guard(name, task), delayMs, TimeUnit.MILLISECONDS);
    }

    public CompletableFuture<Void> run(String name, Runnable task) {
        if (shutdown) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(guard(name, task), compute);
    }

    public <T> CompletableFuture<T> supply(String name, Callable<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (shutdown) {
            f.cancel(false);
            return f;
        }
        compute.execute(() -> {
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                Log.error("task.failed", t, "task", name);
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    public Executor computeExecutor() {
        return compute;
    }

    private Runnable guard(String name, Runnable task) {
        return () -> {
            if (shutdown || Thread.currentThread().isInterrupted()) return;
            try {
                task.run();
            } catch (Throwable t) {
                Log.error("task.failed", t, "task", name);
            }
        };
    }

    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public void close() {
        shutdown = true;
        scheduler.shutdownNow();
        compute.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
            compute.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
