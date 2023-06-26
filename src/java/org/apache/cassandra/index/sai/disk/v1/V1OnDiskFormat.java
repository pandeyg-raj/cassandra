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

package org.apache.cassandra.index.sai.disk.v1;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Gauge;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.lifecycle.LifecycleNewTracker;
import org.apache.cassandra.index.sai.IndexContext;
import org.apache.cassandra.index.sai.SSTableContext;
import org.apache.cassandra.index.sai.StorageAttachedIndex;
import org.apache.cassandra.index.sai.disk.PerColumnIndexWriter;
import org.apache.cassandra.index.sai.disk.PerSSTableIndexWriter;
import org.apache.cassandra.index.sai.disk.PrimaryKeyMap;
import org.apache.cassandra.index.sai.disk.RowMapping;
import org.apache.cassandra.index.sai.disk.SSTableIndex;
import org.apache.cassandra.index.sai.disk.format.IndexComponent;
import org.apache.cassandra.index.sai.disk.format.IndexDescriptor;
import org.apache.cassandra.index.sai.disk.format.OnDiskFormat;
import org.apache.cassandra.index.sai.disk.v1.segment.SegmentBuilder;
import org.apache.cassandra.index.sai.metrics.AbstractMetrics;
import org.apache.cassandra.index.sai.utils.NamedMemoryLimiter;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.metrics.CassandraMetricsRegistry;
import org.apache.cassandra.metrics.DefaultNameFactory;
import org.apache.lucene.store.IndexInput;

import static org.apache.cassandra.utils.FBUtilities.prettyPrintMemory;

public class V1OnDiskFormat implements OnDiskFormat
{
    private static final Logger logger = LoggerFactory.getLogger(V1OnDiskFormat.class);

    private static final Set<IndexComponent> PER_SSTABLE_COMPONENTS = EnumSet.of(IndexComponent.GROUP_COMPLETION_MARKER,
                                                                                 IndexComponent.GROUP_META,
                                                                                 IndexComponent.TOKEN_VALUES,
                                                                                 IndexComponent.PRIMARY_KEY_TRIE,
                                                                                 IndexComponent.PRIMARY_KEY_BLOCKS,
                                                                                 IndexComponent.PRIMARY_KEY_BLOCK_OFFSETS);
    private static final Set<IndexComponent> LITERAL_COMPONENTS = EnumSet.of(IndexComponent.COLUMN_COMPLETION_MARKER,
                                                                             IndexComponent.META,
                                                                             IndexComponent.TERMS_DATA,
                                                                             IndexComponent.POSTING_LISTS);

    /**
     * Global limit on heap consumed by all index segment building that occurs outside the context of Memtable flush.
     *
     * Note that to avoid flushing small index segments, a segment is only flushed when
     * both the global size of all building segments has breached the limit and the size of the
     * segment in question reaches (segment_write_buffer_space_mb / # currently building column indexes).
     *
     * ex. If there is only one column index building, it can buffer up to segment_write_buffer_space_mb.
     *
     * ex. If there is one column index building per table across 8 compactors, each index will be
     *     eligible to flush once it reaches (segment_write_buffer_space_mb / 8) MBs.
     */
    public static final long SEGMENT_BUILD_MEMORY_LIMIT = DatabaseDescriptor.getSAISegmentWriteBufferSpace().toBytes();

    public static final NamedMemoryLimiter SEGMENT_BUILD_MEMORY_LIMITER = new NamedMemoryLimiter(SEGMENT_BUILD_MEMORY_LIMIT,
                                                                                                 "Storage Attached Index Segment Builder");

    static
    {
        CassandraMetricsRegistry.MetricName bufferSpaceUsed = DefaultNameFactory.createMetricName(AbstractMetrics.TYPE, "SegmentBufferSpaceUsedBytes", null);
        CassandraMetricsRegistry.Metrics.register(bufferSpaceUsed, (Gauge<Long>) SEGMENT_BUILD_MEMORY_LIMITER::currentBytesUsed);

        CassandraMetricsRegistry.MetricName bufferSpaceLimit = DefaultNameFactory.createMetricName(AbstractMetrics.TYPE, "SegmentBufferSpaceLimitBytes", null);
        CassandraMetricsRegistry.Metrics.register(bufferSpaceLimit, (Gauge<Long>) () -> SEGMENT_BUILD_MEMORY_LIMIT);

        CassandraMetricsRegistry.MetricName buildsInProgress = DefaultNameFactory.createMetricName(AbstractMetrics.TYPE, "ColumnIndexBuildsInProgress", null);
        CassandraMetricsRegistry.Metrics.register(buildsInProgress, (Gauge<Integer>) SegmentBuilder::getActiveBuilderCount);
    }

    public static final V1OnDiskFormat instance = new V1OnDiskFormat();

    protected V1OnDiskFormat()
    {}

    @Override
    public PrimaryKeyMap.Factory newPrimaryKeyMapFactory(IndexDescriptor indexDescriptor, SSTableReader sstable)
    {
        return new RowAwarePrimaryKeyMap.RowAwarePrimaryKeyMapFactory(indexDescriptor, sstable);
    }

    @Override
    public SSTableIndex newSSTableIndex(SSTableContext sstableContext, IndexContext indexContext)
    {
        return new V1SSTableIndex(sstableContext, indexContext);
    }

    @Override
    public PerSSTableIndexWriter newPerSSTableIndexWriter(IndexDescriptor indexDescriptor) throws IOException
    {
        return new SSTableComponentsWriter(indexDescriptor);
    }

    @Override
    public PerColumnIndexWriter newPerColumnIndexWriter(StorageAttachedIndex index,
                                                        IndexDescriptor indexDescriptor,
                                                        LifecycleNewTracker tracker,
                                                        RowMapping rowMapping)
    {
        // If we're not flushing, or we haven't yet started the initialization build, flush from SSTable contents.
        if (tracker.opType() != OperationType.FLUSH || !index.isInitBuildStarted())
        {
            NamedMemoryLimiter limiter = SEGMENT_BUILD_MEMORY_LIMITER;
            logger.info(index.getIndexContext().logMessage("Starting a compaction index build. Global segment memory usage: {}"),
                        prettyPrintMemory(limiter.currentBytesUsed()));

            return new SSTableIndexWriter(indexDescriptor, index.getIndexContext(), limiter, index.isIndexValid());
        }

        return new MemtableIndexWriter(index.getIndexContext().getMemtableIndexManager().getPendingMemtableIndex(tracker),
                                       indexDescriptor,
                                       index.getIndexContext(),
                                       rowMapping);
    }

    @Override
    public boolean isPerSSTableIndexBuildComplete(IndexDescriptor indexDescriptor)
    {
        return indexDescriptor.hasComponent(IndexComponent.GROUP_COMPLETION_MARKER);
    }

    @Override
    public boolean isPerColumnIndexBuildComplete(IndexDescriptor indexDescriptor, IndexContext indexContext)
    {
        return indexDescriptor.hasComponent(IndexComponent.GROUP_COMPLETION_MARKER) &&
               indexDescriptor.hasComponent(IndexComponent.COLUMN_COMPLETION_MARKER, indexContext);
    }

    @Override
    public boolean validatePerSSTableIndexComponents(IndexDescriptor indexDescriptor, boolean checksum)
    {
        for (IndexComponent indexComponent : perSSTableIndexComponents())
        {
            if (isNotBuildCompletionMarker(indexComponent))
            {
                try (IndexInput input = indexDescriptor.openPerSSTableInput(indexComponent))
                {
                    if (checksum)
                        SAICodecUtils.validateChecksum(input);
                    else
                        SAICodecUtils.validate(input);
                }
                catch (Throwable e)
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug(indexDescriptor.logMessage("{} failed for index component {} on SSTable {}"),
                                     (checksum ? "Checksum validation" : "Validation"),
                                     indexComponent,
                                     indexDescriptor.sstableDescriptor);
                    }
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean validatePerColumnIndexComponents(IndexDescriptor indexDescriptor, IndexContext indexContext, boolean checksum)
    {
        for (IndexComponent indexComponent : perColumnIndexComponents(indexContext))
        {
            if (isNotBuildCompletionMarker(indexComponent))
            {
                try (IndexInput input = indexDescriptor.openPerIndexInput(indexComponent, indexContext))
                {
                    if (checksum)
                        SAICodecUtils.validateChecksum(input);
                    else
                        SAICodecUtils.validate(input);
                }
                catch (Throwable e)
                {
                    if (logger.isDebugEnabled())
                    {
                        logger.debug(indexDescriptor.logMessage("{} failed for index component {} on SSTable {}"),
                                     (checksum ? "Checksum validation" : "Validation"),
                                     indexComponent,
                                     indexDescriptor.sstableDescriptor);
                    }
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public Set<IndexComponent> perSSTableIndexComponents()
    {
        return PER_SSTABLE_COMPONENTS;
    }

    @Override
    public Set<IndexComponent> perColumnIndexComponents(IndexContext indexContext)
    {
        return LITERAL_COMPONENTS;
    }

    @Override
    public int openFilesPerSSTableIndex()
    {
        // For the V1 format there are always 4 open files per SSTable - token values, primary key trie,
        // primary key blocks, primary key block offsets
        return 4;
    }

    @Override
    public int openFilesPerColumnIndex(IndexContext indexContext)
    {
        // For the V1 format there are always 2 open files per index - index (kdtree or terms) + postings
        return 2;
    }

    protected boolean isNotBuildCompletionMarker(IndexComponent indexComponent)
    {
        return indexComponent != IndexComponent.GROUP_COMPLETION_MARKER &&
               indexComponent != IndexComponent.COLUMN_COMPLETION_MARKER;
    }
}