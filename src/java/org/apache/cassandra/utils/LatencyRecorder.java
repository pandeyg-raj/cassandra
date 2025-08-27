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
       for (var ksEntry : stats.entrySet()) {
                String keyspace = ksEntry.getKey();
                for (var typeEntry : ksEntry.getValue().entrySet()) {
                    String type = typeEntry.getKey();
                    LatencyStats s = typeEntry.getValue();

                    long count = s.count.sum();
                    if (count == 0) continue;

                    double average = ((double) s.total.sum()) / count;
                    logger.info(String.format("%s,%s,avg=%.2f,count=%d%n", keyspace, type, average, count));

                }
       }
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
