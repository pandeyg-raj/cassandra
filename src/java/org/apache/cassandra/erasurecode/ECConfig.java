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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.ExecutorPlus;
import org.yaml.snakeyaml.Yaml;

public class ECConfig
{

    public static ExecutorPlus ECStage;

// --Commented out by Inspection START (4/25/25, 9:34 PM):
    private static final Logger logger = LoggerFactory.getLogger(ECConfig.class);

//
//
//    // raj debug start performance breckdown
//    // Normal read
//    /*
//    public static volatile long readCacheTime = 0;
//
//    public static volatile long readMemtableTime = 0;
//    public static volatile long readSSTableTime = 0;
//    public static volatile int readCacheTimeC = 0;
//    public static volatile int readMemtableTimeC = 0;
//    public static volatile int readSSTableTimeC = 0;
//    */
//// --Commented out by Inspection START (4/25/25, 9:34 PM):
// --Commented out by Inspection STOP (4/25/25, 9:34 PM)
//    public static AtomicInteger writeCount = new AtomicInteger(0);
//    public static AtomicInteger signalCount = new AtomicInteger(0);
// --Commented out by Inspection STOP (4/25/25, 9:34 PM)
    // raj debug end


    public static int DATA_SHARDS ;
    public static int PARITY_SHARDS ;
    public static int TOTAL_SHARDS ;
    public static String EC_COLUMN;
    // If true, the EC fragment write (in Keyspace.applySignalRMW) is made durable
    // (written to the commitlog). If false (default), the fragment skips the commitlog.
    public static boolean SIGNAL_WRITE_DURABLE ;
    public static String SignalStr ;
    // Pre-built signal strings, one per rotation r in [0, TOTAL_SHARDS).
    // SignalStrs[r] rotates every node's shard index by r (modulo TOTAL_SHARDS),
    // so each partition key can pick a different shard-role permutation and
    // every node ends up with a mix of data and parity shards across keys.
    public static String[] SignalStrs ;
    /*
    = "signal," +
                       String.valueOf(ECConfig.TOTAL_SHARDS) +"," +
// --Commented out by Inspection START (4/25/25, 9:34 PM):
//                       String.valueOf(ECConfig.DATA_SHARDS) +"," +
//                       "8,10.158.34.18:0,10.158.34.23:1,10.158.34.24:2,10.158.34.25:3,10.158.34.26:4,10.0.0.51:0,10.0.0.52:1,10.0.0.53:2";
// --Commented out by Inspection STOP (4/25/25, 9:34 PM)
    */

    public  static int wholeValueFound = 0;

    // Case 1: EC fragment replaced existing full value in Memtable (signal arrived before flush)
    // Case 2: EC fragment inserted as new Memtable entry (original already flushed to SSTable)
    public static final LongAdder case1Count = new LongAdder();
    public static final LongAdder case2Count = new LongAdder();

    public static String getCaseStats() {
        long c1 = case1Count.sum();
        long c2 = case2Count.sum();
        long total = c1 + c2;
        double pct1 = total == 0 ? 0.0 : 100.0 * c1 / total;
        double pct2 = total == 0 ? 0.0 : 100.0 * c2 / total;
        return String.format("case1(memtable_replace)=%d(%.1f%%) case2(sstable_insert)=%d(%.1f%%) total=%d",
                             c1, pct1, c2, pct2, total);
    }

    public static void resetCaseCounts() {
        case1Count.reset();
        case2Count.reset();
    }

    // ---- Read path counters: which block returned the result ----
    public static final LongAdder readWholeValueCount  = new LongAdder(); // whole value found in replica response
    public static final LongAdder readSimpleCombineCount = new LongAdder(); // all K data shards present, no decode
    public static final LongAdder readDecodeCount      = new LongAdder(); // erasure decode was needed

    public static String getReadPathStats() {
        long wv    = readWholeValueCount.sum();
        long sc    = readSimpleCombineCount.sum();
        long dec   = readDecodeCount.sum();
        long total = wv + sc + dec;
        double pWv  = total == 0 ? 0.0 : 100.0 * wv  / total;
        double pSc  = total == 0 ? 0.0 : 100.0 * sc  / total;
        double pDec = total == 0 ? 0.0 : 100.0 * dec / total;
        long tsMatch    = readShardTimestampMatchCount.sum();
        long tsMismatch = readShardTimestampMismatchCount.sum();
        return String.format(
                "whole_value=%d(%.1f%%) simple_combine=%d(%.1f%%) decode=%d(%.1f%%) total=%d | shard_ts_match=%d shard_ts_mismatch=%d",
                wv, pWv, sc, pSc, dec, pDec, total, tsMatch, tsMismatch);
    }

    public static void resetReadPathCounts() {
        readWholeValueCount.reset();
        readSimpleCombineCount.reset();
        readDecodeCount.reset();
        readShardTimestampMatchCount.reset();
        readShardTimestampMismatchCount.reset();
    }

    // ---- Shard timestamp agreement (combine/decode paths only) ----
    public static final LongAdder readShardTimestampMatchCount    = new LongAdder(); // all available shards share the same timestamp
    public static final LongAdder readShardTimestampMismatchCount = new LongAdder(); // shards have differing timestamps

    // ---- IO stats: compression path analysis ----
    // Compression ON, path A: chunk was truly compressed (chunk.length < chunkLength)
    public static final LongAdder compressedChunkCount    = new LongAdder();
    public static final LongAdder diskBytesCompressedPath = new LongAdder(); // bytes read from disk (compressed)
    public static final LongAdder logicalBytesAfterDecomp = new LongAdder(); // bytes after decompression

    // Compression ON, path B: chunk was incompressible, stored raw (chunk.length == chunkLength)
    public static final LongAdder incompressibleChunkCount = new LongAdder();
    public static final LongAdder diskBytesIncompressible  = new LongAdder(); // bytes read from disk (raw, no gain)

    // Compression OFF: SimpleChunkReader reads raw bytes
    public static final LongAdder diskBytesNoCompression = new LongAdder();

    public static String getIoStats() {
        long compChunks  = compressedChunkCount.sum();
        long incompChunks = incompressibleChunkCount.sum();
        long diskComp    = diskBytesCompressedPath.sum();
        long logical     = logicalBytesAfterDecomp.sum();
        long diskIncomp  = diskBytesIncompressible.sum();
        long diskRaw     = diskBytesNoCompression.sum();
        double compRatio = logical == 0 ? 0.0 : (double) diskComp / logical;
        return String.format(
            "=== IO Stats ===%n" +
            "compression=ON  pathA(compressed):     chunks=%d  disk=%d bytes  logical=%d bytes  ratio=%.3f%n" +
            "compression=ON  pathB(incompressible):  chunks=%d  disk=%d bytes  (no gain, stored raw)%n" +
            "compression=OFF (SimpleChunkReader):    disk=%d bytes",
            compChunks,  diskComp,   logical, compRatio,
            incompChunks, diskIncomp,
            diskRaw);
    }

    public static void resetIoStats() {
        compressedChunkCount.reset();
        diskBytesCompressedPath.reset();
        logicalBytesAfterDecomp.reset();
        incompressibleChunkCount.reset();
        diskBytesIncompressible.reset();
        diskBytesNoCompression.reset();
    }
// --Commented out by Inspection START (4/25/25, 9:34 PM):
    //public  static AtomicInteger TotalSignalReceived ;
    //public  static AtomicInteger TotalReplicateWriteReceived ;
    //public  static AtomicInteger TotalEcWriteReceived ;//    public  static int DecodingNeeded = 0;
    //public  static AtomicInteger TotalSignalSent ;
    //public  static AtomicInteger TotalReplicateWriteSent ;
    //public  static AtomicInteger TotalSignalApplied;

// --Commented out by Inspection STOP (4/25/25, 9:34 PM)
    //public  static PrintWriter myWriter ;


    //public static final String[] ADDRESSES = {"10.0.0.20","10.0.0.186","10.0.0.106","10.0.0.15"};

    private static HashMap<String, Integer> map = new HashMap<>();

// --Commented out by Inspection START (4/25/25, 9:34 PM):
//    //public static final int QUORUM = (int) Math.ceil ( (ECConfig.num_server + ECConfig.num_intersect) / 2);
//
//
//// --Commented out by Inspection START (4/25/25, 9:34 PM):
// --Commented out by Inspection STOP (4/25/25, 9:34 PM)
//    // Convert the byte array to String to send back to client
//    public static String byteToString(byte[] bytes) {
// --Commented out by Inspection START (4/25/25, 9:34 PM):
////        return new String(bytes, StandardCharsets.UTF_8);
////    }
//// --Commented out by Inspection STOP (4/25/25, 9:34 PM)
//
//    // Convert incoming String value
//    public static byte[] stringToByte(String value) {
//       return value.getBytes(StandardCharsets.UTF_8);
//       }
// --Commented out by Inspection STOP (4/25/25, 9:34 PM)

    // Create the Empty Codes based on what I set
    public static byte[] emptyCodes(int length) {
        byte[] arr = new byte[length];
        for (int i = 0; i < length; i++) {
            arr[i] = '0';
        }
        return arr;
    }

    private static final ScheduledExecutorService MONITOR_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    public static void startThreadPoolLogger() {
        MONITOR_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                PriorityThreadPoolUtil.printThreadPollInfo();
            } catch (Exception e) {
                logger.warn("Failed to log EC thread pool status", e);
            }
        }, 0, 10, TimeUnit.SECONDS); // log every 10 seconds
    }


    public static void initECConfig() {

        //TotalSignalReceived = new AtomicInteger(0);
        //TotalEcWriteReceived = new AtomicInteger(0);
        //TotalReplicateWriteReceived = new AtomicInteger(0);
        //TotalSignalSent = new AtomicInteger(0);
        //TotalReplicateWriteSent = new AtomicInteger(0);
        //TotalSignalApplied = new AtomicInteger(0);
        logger.error("EC service initializing");
        try {

            InputStream inputStream = new FileInputStream(new File("./conf/ECConfig.yaml"));
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(inputStream);
            DATA_SHARDS = (int) data.get("data_shards");
            PARITY_SHARDS = (int) data.get("parity_shards");
            TOTAL_SHARDS = DATA_SHARDS + PARITY_SHARDS ;
            EC_COLUMN = data.get("ec_column").toString();

            // Optional: controls whether the EC fragment write is durable (commitlog).
            // Defaults to false (skip commitlog) when the key is absent, preserving prior behavior.
            Object signalDurableObj = data.get("signal_write_durable");
            SIGNAL_WRITE_DURABLE = signalDurableObj != null && (boolean) signalDurableObj;
            logger.error("EC SIGNAL_WRITE_DURABLE = {}", SIGNAL_WRITE_DURABLE);

            SignalStr ="signal," +
                       String.valueOf(TOTAL_SHARDS) +"," +
                       String.valueOf(DATA_SHARDS) +"," +
                       data.get("ec_configs").toString();

            // Build SignalStrs[r] for each rotation r.
            // ec_configs format: "<totalServer>,<ip>:<idx>,<ip>:<idx>,..."
            // For rotation r, each node's shard idx becomes (origIdx + r) % TOTAL_SHARDS.
            // IPs stay in their original positions so each replica still finds itself.
            {
                String ecConfigStr = data.get("ec_configs").toString();
                String[] parts = ecConfigStr.split(",");
                int totalServer = Integer.parseInt(parts[0]);
                String[] ips = new String[totalServer];
                int[] origIdx = new int[totalServer];
                for (int i = 0; i < totalServer; i++) {
                    String entry = parts[1 + i];
                    int colon = entry.indexOf(':');
                    ips[i] = entry.substring(0, colon);
                    origIdx[i] = Integer.parseInt(entry.substring(colon + 1));
                }
                SignalStrs = new String[TOTAL_SHARDS];
                for (int r = 0; r < TOTAL_SHARDS; r++) {
                    StringBuilder sb = new StringBuilder("signal,")
                                       .append(TOTAL_SHARDS).append(',')
                                       .append(DATA_SHARDS).append(',')
                                       .append(totalServer);
                    for (int i = 0; i < totalServer; i++) {
                        sb.append(',').append(ips[i]).append(':')
                          .append((origIdx[i] + r) % TOTAL_SHARDS);
                    }
                    SignalStrs[r] = sb.toString();
                }
                logger.error("EC rotation signals built: TOTAL_SHARDS={} totalServer={} SignalStrs[0]={}",
                             TOTAL_SHARDS, totalServer, SignalStrs[0]);
            }

            //myWriter = new PrintWriter("Decodings.txt", StandardCharsets.UTF_8);

            PriorityThreadPoolUtil.setExecutor(32,Thread.NORM_PRIORITY);
            //startThreadPoolLogger();
            /*
            ECStage =
            SharedExecutorPool.SHARED.newExecutor(
            32,                         // maxConcurrency
            "org.apache.cassandra.request", // jmxPath
            "ECStage"                 // thread pool name
            );
            */

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public static HashMap<String, Integer> getAddressMap() {
        return map;
    }

    public static void PrintBreackdown()
    {
        /* logger.info(" Cache  access Count :" + readCacheTimeC + "Total value: " +readCacheTime + "(ns)\n" +
                    " Memtbl access Count :" + readMemtableTimeC + "Total value: " +readMemtableTime + "(ms)\n" +
                    " sstabl access Count :" + readSSTableTimeC + "Total value: " +readSSTableTime + "(ms)\n") ;

        logger.info(" TotalReplicateWriteSent :" + TotalReplicateWriteSent + "\n" +
                    " TotalSignalSent :" + TotalSignalSent + "\n" +
                    " TotalReplicateWriteReceived :" + TotalReplicateWriteReceived + "\n" +
                    " TotalSignalReceived :" + TotalSignalReceived + "\n" +
                    " TotalSignalApplied :" + TotalSignalApplied + "\n" +
                    " TotalEcWriteReceived :" + TotalEcWriteReceived + "\n") ;
            */
    }
    public static void freeECConfig()
    {
        PriorityThreadPoolUtil.shutdownExecutor();
        logger.error("EC service freed");
    }
}
