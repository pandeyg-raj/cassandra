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
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PriorityThreadPoolUtil {

    private static final Logger logger = LoggerFactory.getLogger(PriorityThreadPoolUtil.class);
    private static  ThreadPoolExecutor executor ;

    public static void setExecutor(int poolSize, int priority) {
        executor = PriorityThreadPoolUtil.createFixedPriorityPool(poolSize, priority);
    }
    public static final LongAdder TimeTakenThreadSpawn = new LongAdder();
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
        poolSize * 5,                  // max pool size = core (fixed)
        30, TimeUnit.SECONDS, // keep alive time
        new LinkedBlockingQueue<>(), // unbounded queue (or customize)
        new PriorityThreadFactory(priority)
        );
    }

    public static void printThreadPollInfo()
    {
        int active = executor.getActiveCount();
        int maxPoolSize = executor.getMaximumPoolSize();
        int currentPoolSize = executor.getPoolSize();
        int queued = executor.getQueue().size();
        long completed = executor.getCompletedTaskCount();

        //logger.info("=== Thread Pool Status ===\n");
        //logger.info("Active Threads   : " + active);
        //logger.info("Total Pool Size  : " + poolSize);
        //logger.info("Queued Tasks     : " + queued);
        //logger.info("Completed Tasks  : " + completed);

        // Monitor logic
        if (active == maxPoolSize && queued > 0) {
            logger.info("🚨 FULLY LOADED: Max threads active, and queue is growing — system saturated.");
        } else if (active == maxPoolSize && queued == 0) {
            logger.info("✅ Max threads in use, but queue is empty — high throughput under control.");
        } else if (active < maxPoolSize && queued == 0) {
            logger.info("🟢 UNDERUTILIZED: Idle capacity and no waiting tasks.");
        } else if (active < maxPoolSize && queued > 0) {
            logger.info("⚠️ Queue building up even with spare threads — potential inefficiency or blocking.");
        }
    }
}
