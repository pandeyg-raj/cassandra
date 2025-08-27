package org.apache.cassandra.utils;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LatencyRecorder {
    private static final Logger logger = LoggerFactory.getLogger(LatencyRecorder.class);

    // Thread-safe buffer storing log lines as StringBuilder
    private static final ConcurrentLinkedQueue<StringBuilder> buffer = new ConcurrentLinkedQueue<>();
    private static final AtomicLong counter = new AtomicLong(0);

    private static final String OUTPUT_FILE = "cassandra_latencies.log";
    private static final long BUFFER_SIZE_TRIGGER = 1_000_000;

    /**
     * Record a latency measurement.
     * @param keyspace Keyspace name
     * @param type     Measurement type ("CommitLog", "Memtable", etc.)
     * @param duration Duration in nanoseconds
     */
    public static void record(String keyspace, String type, long duration) {
        // Build the line: keyspace,type,duration
        StringBuilder sb = new StringBuilder(keyspace.length() + type.length() + 32);
        sb.append(keyspace).append(',').append(type).append(',').append(duration);

        buffer.offer(sb);

        // Increment counter and check trigger
        long count = counter.incrementAndGet();
        if (count >= BUFFER_SIZE_TRIGGER) {
            flush();
        }
    }

    /** Flush buffered data to disk. Thread-safe. */
    public static synchronized void flush() {
        if (buffer.isEmpty()) return;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE, true))) {
            StringBuilder sb;
            long flushed = 0;
            while ((sb = buffer.poll()) != null) {
                writer.write(sb.toString());
                writer.newLine();
                flushed++;
            }
            counter.addAndGet(-flushed);
        } catch (IOException e) {
            logger.error("LatencyRecorder write error", e);
        }
    }
}
