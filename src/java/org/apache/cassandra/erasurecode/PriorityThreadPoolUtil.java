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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PriorityThreadPoolUtil {

    private static final Logger logger = LoggerFactory.getLogger(PriorityThreadPoolUtil.class);
    private static final ThreadPoolExecutor executor = PriorityThreadPoolUtil.createFixedPriorityPool(8, Thread.MIN_PRIORITY);

    public static ExecutorService getExecutor() {
        return executor;
    }

    public static void shutdownExecutor() {
        executor.shutdown();
    }


    // Custom ThreadFactory that sets thread priority
    public static class PriorityThreadFactory implements ThreadFactory {
        private final int priority;
        private final ThreadFactory defaultFactory = Executors.defaultThreadFactory();
        private int count = 0;

        public PriorityThreadFactory(int priority) {
            this.priority = priority;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = defaultFactory.newThread(r);
            t.setPriority(priority);
            t.setName("priority-thread-" + count++);
            return t;
        }
    }

    // Creates a ThreadPoolExecutor with specified priority and monitoring
    public static ThreadPoolExecutor createFixedPriorityPool(int poolSize, int priority) {
        return new ThreadPoolExecutor(
        poolSize,                  // core pool size
        poolSize * 2,                  // max pool size = core (fixed)
        60, TimeUnit.SECONDS, // keep alive time
        new LinkedBlockingQueue<>(), // unbounded queue (or customize)
        new PriorityThreadFactory(priority)
        );
    }

    public static void printThreadPollInfo()
    {
        int active = executor.getActiveCount();
        int poolSize = executor.getPoolSize();
        int queued = executor.getQueue().size();
        long completed = executor.getCompletedTaskCount();

        //logger.info("=== Thread Pool Status ===\n");
        //logger.info("Active Threads   : " + active);
        //logger.info("Total Pool Size  : " + poolSize);
        //logger.info("Queued Tasks     : " + queued);
        //logger.info("Completed Tasks  : " + completed);

        // Monitor logic
        if (active == poolSize && queued > 0) {
            logger.info("🚨 POOL FULL + QUEUE BACKED UP: System is likely saturated or under-provisioned.");
        } else if (active == poolSize && queued == 0) {
            logger.info("✅ Pool is fully utilized with no backlog — efficient usage.");
        } else if (active < poolSize && queued == 0) {
            logger.info("🟢 Underutilized: Idle threads available and no tasks waiting.");
        } else if (active < poolSize && queued > 0) {
            logger.info("⚠️ Tasks are queued even though threads are idle — potential inefficiency or blocking.");
        }
    }
}
