/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.erasurecode;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Thread-safe hybrid latency recorder.
 * - Multiple measurement types supported (e.g., Memtable, SSTable, CommitLog)
 * - Periodic flush to file
 * - Buffer-size trigger flush
 * - Final flush at shutdown
 */
public class LatencyRecorder {

    private static final Logger logger = LoggerFactory.getLogger(LatencyRecorder.class);
    // Thread-safe buffer storing "<type>,<duration-nanos>"
    private static final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();

    // Background scheduler for periodic flush
    //private static final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();

    // Output file path
    private static final String OUTPUT_FILE = "/tmp/cassandra_latencies.log";

    // Flush parameters
    // private static final int FLUSH_INTERVAL_SECONDS = 300;     // periodic flush interval
    private static final int BUFFER_SIZE_TRIGGER = 1_000_000; // flush if buffer exceeds this many entries

    /*
    static {
        // Schedule periodic flush
        flusher.scheduleAtFixedRate(() -> flush(), FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // Register shutdown hook to flush remaining data
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flush();
            flusher.shutdown();
        }));
    }
    */

    /**
     * Record a latency measurement.
     * @param type     Measurement type (e.g., "Memtable", "SSTable", "CommitLog")
     * @param duration Duration in nanoseconds
     */
    public static void record(String type, long duration) {
        buffer.add(type + "," + duration);

        // Flush if buffer exceeds trigger size
        if (buffer.size() >= BUFFER_SIZE_TRIGGER) {
            flush();
        }
    }

    /**
     * Flush buffered data to disk. Thread-safe.
     */
    public static synchronized void flush() {
        if (buffer.isEmpty()) return;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_FILE, true))) {
            String entry;
            while ((entry = buffer.poll()) != null) {
                writer.write(entry);
                writer.newLine();
            }
        } catch (IOException e) {
            logger.error("Latecy logger File flushed exception");
            System.err.println("[LatencyRecorderHybrid] Error writing log: " + e.getMessage());
        }
        logger.error("Latecy logger File flushed sucessfully");
    }
}

