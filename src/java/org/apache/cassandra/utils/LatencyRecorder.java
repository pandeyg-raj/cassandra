package org.apache.cassandra.utils;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

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


    public static String getBreakdownTime() {
        StringBuilder sb = new StringBuilder();
        for (var ksEntry : stats.entrySet()) {
            String keyspace = ksEntry.getKey();
            for (var typeEntry : ksEntry.getValue().entrySet()) {
                String type = typeEntry.getKey();
                LatencyStats s = typeEntry.getValue();

                long count = s.count.sum();
                if (count == 0) continue;

                double average = ((double) s.total.sum()) / count;
                long min = s.min.get();
                long max = s.max.get();
                sb.append(String.format("%s,%s,avg=%.2f,min=%d,max=%d,count=%d%n",keyspace, type, average, min, max, count));
            }
        }
        if (sb.length() == 0) return "nothing here";
        return sb.toString();
    }


    public static String resetBreakdownTime() {
        stats.clear(); // removes all keyspaces and types
        return "reset done";
    }
    /** Helper class to track total/count for a single keyspace/type */
    private static class LatencyStats {
        final LongAdder count = new LongAdder();
        final LongAdder total = new LongAdder();
        final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong max = new AtomicLong(Long.MIN_VALUE);

        void add(long duration) {
            count.increment();
            total.add(duration);
            // Update min atomically
            min.getAndUpdate(current -> Math.min(current, duration));
            // Update max atomically
            max.getAndUpdate(current -> Math.max(current, duration));
        }
    }
}


