package org.apache.cassandra.utils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LatencyRecorder {
    private static final Logger logger = LoggerFactory.getLogger(LatencyRecorder.class);

    // Outer map = keyspace, Inner map = type -> LatencyStats
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, LatencyStats>> stats = new ConcurrentHashMap<>();

    // Prevent concurrent flushes
    private static final AtomicBoolean flushing = new AtomicBoolean(false);

    // Configurable output file
    private static final String OUTPUT_FILE =
    System.getProperty("cassandra.latency.log", "cassandra_latencies.log");

    /**
     * Record a latency measurement (non-blocking and cheap).
     * Signature kept same as before.
     */
    public static void record(String keyspace, String type, long duration) {
        stats.computeIfAbsent(keyspace, k -> new ConcurrentHashMap<>())
             .computeIfAbsent(type, t -> new LatencyStats())
             .add(duration);
    }

    /**
     * Flush all current aggregated stats to disk.
     * Writes average (total/count) and count per keyspace/type.
     */
    public static void flush() {
        if (!flushing.compareAndSet(false, true)) {
            // Another flush is in progress
            return;
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE, true))) {
            for (var ksEntry : stats.entrySet()) {
                String keyspace = ksEntry.getKey();
                for (var typeEntry : ksEntry.getValue().entrySet()) {
                    String type = typeEntry.getKey();
                    LatencyStats s = typeEntry.getValue();

                    long count = s.count.sum();
                    if (count == 0) continue;

                    double average = ((double) s.total.sum()) / count;
                    writer.write(String.format("%s,%s,avg=%.2f,count=%d%n",
                                               keyspace, type, average, count));
                }
            }
        } catch (IOException e) {
            logger.error("LatencyRecorder aggregate flush error", e);
        } finally {
            flushing.set(false);
        }
    }

    /** Approximate number of keyspace/type pairs being tracked */
    public static long approxTrackedPairs() {
        return stats.size();
    }

    /** Helper class to track total/count for a single keyspace/type */
    private static class LatencyStats {
        final LongAdder count = new LongAdder();
        final LongAdder total = new LongAdder();

        void add(long duration) {
            count.increment();
            total.add(duration);
        }
    }
}
