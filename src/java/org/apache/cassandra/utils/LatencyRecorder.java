package org.apache.cassandra.utils;

import java.util.concurrent.ConcurrentHashMap;
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
                sb.append(String.format("%s,%s,avg=%.2f,p95=%.2f,p99=%.2f,min=%d,max=%d,count=%d%n",
                          keyspace, type, average, s.getPercentile(95), s.getPercentile(99),
                          s.min, s.max, count));
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
                sb.append(String.format("%s,%s,total_gb=%.3f,avg_bytes=%.2f,count=%d%n",
                          keyspace, type, totalGb, average, count));
            }
        }

        if (sb.length() == 0) return "nothing here";
        return sb.toString();
    }

    public static String resetBreakdownTime() {
        stats.clear();
        byteStats.clear();
        return "reset done";
    }

    // -------------------------------------------------------------------------
    // Flag: set true on compaction threads so IO counters route to compaction buckets
    // -------------------------------------------------------------------------
    public static final ThreadLocal<Boolean> IS_COMPACTION = ThreadLocal.withInitial(() -> false);

    // -------------------------------------------------------------------------
    // IO stats — USER READS (compression ON, path A: genuinely compressed chunk)
    // -------------------------------------------------------------------------
    public static final LongAdder compressedChunkCount    = new LongAdder();
    public static final LongAdder diskBytesCompressedPath = new LongAdder();
    public static final LongAdder logicalBytesAfterDecomp = new LongAdder();

    // IO stats — USER READS (compression ON, path B: incompressible, stored raw)
    public static final LongAdder incompressibleChunkCount = new LongAdder();
    public static final LongAdder diskBytesIncompressible  = new LongAdder();

    // IO stats — USER READS (compression OFF: SimpleChunkReader)
    public static final LongAdder noCompressionChunkCount = new LongAdder();
    public static final LongAdder diskBytesNoCompression  = new LongAdder();

    // -------------------------------------------------------------------------
    // IO stats — COMPACTION (same three buckets, separated so user-read I/O is clean)
    // -------------------------------------------------------------------------
    public static final LongAdder compactionCompressedChunkCount    = new LongAdder();
    public static final LongAdder compactionDiskBytesCompressedPath = new LongAdder();
    public static final LongAdder compactionLogicalBytesAfterDecomp = new LongAdder();

    public static final LongAdder compactionIncompressibleChunkCount = new LongAdder();
    public static final LongAdder compactionDiskBytesIncompressible  = new LongAdder();

    public static final LongAdder compactionNoCompressionChunkCount = new LongAdder();
    public static final LongAdder compactionDiskBytesNoCompression  = new LongAdder();

    // -------------------------------------------------------------------------
    // Per-request IO tracking — resets before each SSTable read, read after
    // int[0]=chunk reads (disk hits), int[1]=bytes read, int[2]=sstables in view
    // -------------------------------------------------------------------------
    public static final ThreadLocal<int[]> REQUEST_IO = ThreadLocal.withInitial(() -> new int[]{0, 0, 0});

    public static void resetRequestIo() {
        int[] c = REQUEST_IO.get();
        c[0] = 0; c[1] = 0; c[2] = 0;
    }

    public static void recordRequestIoChunk(int bytes) {
        int[] c = REQUEST_IO.get();
        c[0]++;
        c[1] += bytes;
    }

    public static void recordRequestSStable(int sstableCount) {
        REQUEST_IO.get()[2] = sstableCount;
    }

    // -------------------------------------------------------------------------
    // Formatted output
    // -------------------------------------------------------------------------
    public static String getIoStats() {
        // User reads
        long uc   = compressedChunkCount.sum();
        long ud   = diskBytesCompressedPath.sum();
        long ul   = logicalBytesAfterDecomp.sum();
        long uic  = incompressibleChunkCount.sum();
        long uid  = diskBytesIncompressible.sum();
        long uncc = noCompressionChunkCount.sum();
        long urd  = diskBytesNoCompression.sum();
        double uratio = ul == 0 ? 0.0 : (double) ud / ul;

        // Compaction reads
        long cc   = compactionCompressedChunkCount.sum();
        long cd   = compactionDiskBytesCompressedPath.sum();
        long cl   = compactionLogicalBytesAfterDecomp.sum();
        long cic  = compactionIncompressibleChunkCount.sum();
        long cid  = compactionDiskBytesIncompressible.sum();
        long cncc = compactionNoCompressionChunkCount.sum();
        long crd  = compactionDiskBytesNoCompression.sum();
        double cratio = cl == 0 ? 0.0 : (double) cd / cl;

        return String.format(
            "=== IO Stats (USER READS) ===%n" +
            "  compression=ON  pathA(compressed):      chunks=%d  disk=%d  logical=%d  ratio=%.3f%n" +
            "  compression=ON  pathB(incompressible):  chunks=%d  disk=%d%n" +
            "  compression=OFF (SimpleChunkReader):    chunks=%d  disk=%d%n" +
            "=== IO Stats (COMPACTION) ===%n" +
            "  compression=ON  pathA(compressed):      chunks=%d  disk=%d  logical=%d  ratio=%.3f%n" +
            "  compression=ON  pathB(incompressible):  chunks=%d  disk=%d%n" +
            "  compression=OFF (SimpleChunkReader):    chunks=%d  disk=%d%n",
            uc, ud, ul, uratio,
            uic, uid,
            uncc, urd,
            cc, cd, cl, cratio,
            cic, cid,
            cncc, crd);
    }

    public static String resetIoStats() {
        compressedChunkCount.reset();       diskBytesCompressedPath.reset();  logicalBytesAfterDecomp.reset();
        incompressibleChunkCount.reset();   diskBytesIncompressible.reset();
        noCompressionChunkCount.reset();    diskBytesNoCompression.reset();
        compactionCompressedChunkCount.reset(); compactionDiskBytesCompressedPath.reset(); compactionLogicalBytesAfterDecomp.reset();
        compactionIncompressibleChunkCount.reset(); compactionDiskBytesIncompressible.reset();
        compactionNoCompressionChunkCount.reset(); compactionDiskBytesNoCompression.reset();
        return "IO stats reset";
    }

    public static String resetAllStats() {
        stats.clear();
        byteStats.clear();
        resetIoStats();
        return "all stats reset";
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------
    private static class LatencyStats {
        final LongAdder count = new LongAdder();
        final LongAdder total = new LongAdder();
        volatile long min = Long.MAX_VALUE;
        volatile long max = Long.MIN_VALUE;
        final Histogram histogram;

        LatencyStats() {
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
