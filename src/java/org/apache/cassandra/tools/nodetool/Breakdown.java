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
package org.apache.cassandra.tools.nodetool;

import io.airlift.airline.Command;
import io.airlift.airline.Option;

import org.apache.cassandra.tools.NodeTool.NodeToolCmd;
import org.apache.cassandra.tools.NodeProbe;

@Command(name = "breakdown", description = "Show or reset latency, IO, cache, flush, and SSTable stats")
public class Breakdown extends NodeToolCmd
{
    @Option(name = {"--reset"}, description = "Reset all stats (latency, IO, cache)")
    private boolean reset = false;

    @Option(name = {"--io"}, description = "Show only IO stats (user reads vs compaction, user+compaction split)")
    private boolean io = false;

    @Option(name = {"--full"}, description = "Show full stats: latency + IO (user/compaction split) + cache hit rate")
    private boolean full = false;

    @Option(name = {"--sstables"}, description = "Show live SSTable count per table (current snapshot)")
    private boolean sstables = false;

    @Override
    public void execute(NodeProbe probe)
    {
        if (reset)
        {
            probe.output().out.println(probe.resetAllStats());
        }
        else if (sstables)
        {
            probe.output().out.println(probe.getLiveSStableStats());
        }
        else if (io)
        {
            probe.output().out.println(probe.getIoStats());
        }
        else if (full)
        {
            probe.output().out.println(probe.getFullStats());
        }
        else
        {
            // default: latency breakdown + IO split
            probe.output().out.println(probe.getBreakdownTime());
            probe.output().out.println(probe.getIoStats());
        }
    }
}
