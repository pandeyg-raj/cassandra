package org.apache.cassandra.utils;
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
                long min = s.min;
                long max = s.max;
                sb.append(String.format("%s,%s,avg=%.2f,p95=%.2f,p99=%.2f,min=%d,max=%d,count=%d%n",keyspace, type, average,s.getPercentile(95),s.getPercentile(99),s.min, s.max,count));
            }
        }
        if (sb.length() == 0) return "nothing here";
        return sb.toString();
    }


    public static String resetBreakdownTime() {
        stats.clear(); // removes all keyspaces and types
        return "reset done";
    }

    // ---- IO stats: compression path analysis ----
    // Compression ON, path A: chunk was truly compressed (chunk.length < maxCompressedLength)
    public static final LongAdder compressedChunkCount    = new LongAdder();
    public static final LongAdder diskBytesCompressedPath = new LongAdder(); // bytes read from disk
    public static final LongAdder logicalBytesAfterDecomp = new LongAdder(); // bytes after decompression

    // Compression ON, path B: chunk was incompressible, stored raw
    public static final LongAdder incompressibleChunkCount = new LongAdder();
    public static final LongAdder diskBytesIncompressible  = new LongAdder();

    // Compression OFF: SimpleChunkReader reads raw bytes
    public static final LongAdder diskBytesNoCompression = new LongAdder();

    // Per-request IO tracking: how many channel.read() calls + bytes per single user read
    // int[0] = chunk read count, int[1] = bytes read
    public static final ThreadLocal<int[]> REQUEST_IO = ThreadLocal.withInitial(() -> new int[]{0, 0});

    public static void resetRequestIo()
    {
        int[] c = REQUEST_IO.get();
        c[0] = 0;
        c[1] = 0;
    }

    public static void recordRequestIoChunk(int bytes)
    {
        int[] c = REQUEST_IO.get();
        c[0]++;
        c[1] += bytes;
    }

    public static String getIoStats() {
        long compChunks   = compressedChunkCount.sum();
        long incompChunks = incompressibleChunkCount.sum();
        long diskComp     = diskBytesCompressedPath.sum();
        long logical      = logicalBytesAfterDecomp.sum();
        long diskIncomp   = diskBytesIncompressible.sum();
        long diskRaw      = diskBytesNoCompression.sum();
        double ratio      = logical == 0 ? 0.0 : (double) diskComp / logical;
        return String.format(
            "=== IO Stats ===%n" +
            "compression=ON  pathA(compressed):     chunks=%d  disk=%d bytes  logical=%d bytes  ratio=%.3f%n" +
            "compression=ON  pathB(incompressible):  chunks=%d  disk=%d bytes  (no gain, stored raw)%n" +
            "compression=OFF (SimpleChunkReader):    disk=%d bytes",
            compChunks,  diskComp,  logical, ratio,
            incompChunks, diskIncomp,
            diskRaw);
    }

    public static String resetIoStats() {
        compressedChunkCount.reset();
        diskBytesCompressedPath.reset();
        logicalBytesAfterDecomp.reset();
        incompressibleChunkCount.reset();
        diskBytesIncompressible.reset();
        diskBytesNoCompression.reset();
        return "IO stats reset";
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
}




