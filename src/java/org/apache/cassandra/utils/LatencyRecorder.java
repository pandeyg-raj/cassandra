package org.apache.cassandra.utils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LatencyRecorder {
    private static final Logger logger = LoggerFactory.getLogger(LatencyRecorder.class);

    // Producers enqueue simple CSV lines: keyspace,type,duration
    private static final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();

    // Avoid expensive size() calls
    private static final AtomicLong counter = new AtomicLong(0);

    // Prevent concurrent flushes
    private static final AtomicBoolean flushing = new AtomicBoolean(false);

    // Configurable output file (system property allows overriding in tests/run)
    private static final String OUTPUT_FILE =
    System.getProperty("cassandra.latency.log", "cassandra_latencies.log");

    // How many queued entries before we trigger an async flush
    private static final long BUFFER_SIZE_TRIGGER =
    Long.getLong("cassandra.latency.trigger", 1_000_000L);

    // Batch buffer for writes (1 MB)
    private static final int WRITE_BATCH_BUFFER_BYTES =
    Integer.getInteger("cassandra.latency.writeBufferBytes", 1024 * 1024);

    // Single background thread for flushing
    private static final ExecutorService flushExecutor =
    Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LatencyRecorder-Flush");
        t.setDaemon(true);
        return t;
    });

    /**
     * Record a latency measurement (non-blocking for callers).
     *
     * @param keyspace Keyspace name
     * @param type     Measurement type ("CommitLog", "Memtable", etc.)
     * @param duration Duration in nanoseconds
     */
    public static void record(String keyspace, String type, long duration) {
        // Build the CSV line on the producer thread (single allocation for String)
        buffer.offer(keyspace + "," + type + "," + duration);

        // Cheap trigger check. Only one thread will successfully schedule the async flush.
        long count = counter.incrementAndGet();
        if (count >= BUFFER_SIZE_TRIGGER && flushing.compareAndSet(false, true)) {
            // Reset the counter immediately (new increments will start counting from 0)
            counter.set(0);
            flushExecutor.submit(() -> {
                try {
                    flushInternal();
                } finally {
                    flushing.set(false);
                }
            });
        }
    }

    /**
     * Force an immediate flush on the calling thread. This blocks until the current
     * queued entries are written to the file. Useful for shutdown/critical checkpoints.
     */
    public static void flushBlocking() {
        // If a flush is already in progress, just return (or optionally wait).
        if (!flushing.compareAndSet(false, true)) {
            // Another flush is running; best-effort return.
            return;
        }
        try {
            flushInternal();
        } finally {
            flushing.set(false);
        }
    }

    /**
     * Background/internal flush implementation — writes everything currently in the queue
     * to disk in large batches to reduce I/O syscalls.
     */
    private static void flushInternal() {
        if (buffer.isEmpty()) return;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE, true))) {
            StringBuilder sb = new StringBuilder(WRITE_BATCH_BUFFER_BYTES);
            String line;
            while ((line = buffer.poll()) != null) {
                sb.append(line).append('\n');
                if (sb.length() >= WRITE_BATCH_BUFFER_BYTES) {
                    writer.write(sb.toString());
                    sb.setLength(0);
                }
            }
            if (sb.length() > 0) {
                writer.write(sb.toString());
            }
        } catch (IOException e) {
            logger.error("LatencyRecorder write error", e);
        }
    }

    /**
     * Flush remaining data and shutdown the background executor. Call at process shutdown.
     * This will attempt a final blocking flush before asking the executor to terminate.
     */
    public static void shutdownAndFlush() {
        // First try to flush synchronously to avoid relying solely on the executor.
        flushBlocking();

        // Then shutdown the executor and wait briefly for any submitted tasks to finish.
        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                // If background flushes are still running, request termination and proceed.
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Approximate number of queued entries (for monitoring).
     */
    public static long approxQueued() {
        return counter.get();
    }
}
