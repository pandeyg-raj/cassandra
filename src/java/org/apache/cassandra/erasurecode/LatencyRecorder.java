package org.apache.cassandra.erasurecode;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.HdrHistogram.Histogram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LatencyRecorder {
    private static final Logger logger = LoggerFactory.getLogger(LatencyRecorder.class);

    // Outer map = keyspace, Inner map = type -> LatencyStats
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, LatencyStats>> stats = new ConcurrentHashMap<>();

    // Cumulative byte counters (sum + count), separate from the latency histogram above.
    // Used for large per-event sizes (commitlog/flush/compaction write amounts) that would
    // overflow the latency Histogram's highestTrackableValue. Outer = keyspace, inner = type.
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, ByteStats>> byteStats = new ConcurrentHashMap<>();



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
     * Record a cumulative byte amount (e.g. bytes written to disk by a single commitlog
     * append, memtable flush, or compaction). Tracks sum and event count only; no histogram,
     * so it is safe for arbitrarily large values and is cheap/non-blocking.
     */
    public static void recordBytes(String keyspace, String type, long bytes) {
        byteStats.computeIfAbsent(keyspace, k -> new ConcurrentHashMap<>())
                 .computeIfAbsent(type, t -> new ByteStats())
                 .add(bytes);
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
                long min = s.min;
                long max = s.max;
                sb.append(String.format("%s,%s,avg=%.2f,p95=%.2f,p99=%.2f,count=%d%n",keyspace, type, average,s.getPercentile(95),s.getPercentile(99),count));
            }
        }

        // Byte counters (total bytes written to disk per site). Reports cumulative sum,
        // event count, and average bytes per event.
        for (var ksEntry : byteStats.entrySet()) {
            String keyspace = ksEntry.getKey();
            for (var typeEntry : ksEntry.getValue().entrySet()) {
                String type = typeEntry.getKey();
                ByteStats b = typeEntry.getValue();

                long count = b.count.sum();
                if (count == 0) continue;

                long total = b.total.sum();
                double totalGb = total / 1.0e9;   // 1 GB = 1000^3 bytes
                double average = ((double) total) / count;
                sb.append(String.format("%s,%s,total_gb=%.3f,avg_bytes=%.2f,count=%d%n", keyspace, type, totalGb, average, count));
            }
        }

        if (sb.length() == 0) return "nothing here";
        return sb.toString();
    }


    public static String resetBreakdownTime() {
        stats.clear(); // removes all keyspaces and types
        byteStats.clear();
        return "reset done";
    }
    /** Helper class to track total/count for a single keyspace/type */
    private static class LatencyStats {
        final LongAdder count = new LongAdder();
        final LongAdder total = new LongAdder();
        volatile long min = Long.MAX_VALUE;
        volatile long max = Long.MIN_VALUE;
        final Histogram histogram;
        LatencyStats() {
        // Track values from 1µs up to 1 minute (adjust as needed), 3 sigfigs precision
        this.histogram = new Histogram(1, 60_000_000, 3);
        }

        void add(long duration) {
            count.increment();
            total.add(duration);
            if (duration < min) min = duration;
            if (duration > max) max = duration;
            histogram.recordValue(duration);
        }
        
        double getPercentile(double p) {
        return histogram.getValueAtPercentile(p);
        }
    }

    /** Helper class to track cumulative sum and event count for a byte counter. */
    private static class ByteStats {
        final LongAdder count = new LongAdder();
        final LongAdder total = new LongAdder();

        void add(long bytes) {
            count.increment();
            total.add(bytes);
        }
    }
}





