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

package org.apache.paimon.flink.compact;

import org.apache.paimon.append.AppendCompactTask;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.flink.FlinkConnectorOptions;
import org.apache.paimon.flink.sink.AppendTableCompactSink;
import org.apache.paimon.flink.source.AppendTableCompactSource;
import org.apache.paimon.manifest.PartitionEntry;
import org.apache.paimon.options.Options;
import org.apache.paimon.partition.PartitionPredicate;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.utils.Preconditions;

import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.transformations.PartitionTransformation;
import org.apache.flink.streaming.runtime.partitioner.RebalancePartitioner;

import javax.annotation.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Build for unaware-bucket table flink compaction job.
 *
 * <p>Note: This compaction job class is only used for unaware-bucket compaction, at start-up, it
 * scans all the files from the latest snapshot, filter large file, and add small files into memory,
 * generates compaction task for them. At continuous, it scans the delta files from the follow-up
 * snapshot. We need to enable checkpoint for this compaction job, checkpoint will trigger committer
 * stage to commit all the compacted files.
 */
public class AppendTableCompactBuilder {

    private final transient StreamExecutionEnvironment env;
    private final String tableIdentifier;
    private final FileStoreTable table;

    private boolean isContinuous = false;

    @Nullable private PartitionPredicate partitionPredicate;
    @Nullable private Duration partitionIdleTime = null;

    public AppendTableCompactBuilder(
            StreamExecutionEnvironment env, String tableIdentifier, FileStoreTable table) {
        this.env = env;
        this.tableIdentifier = tableIdentifier;
        this.table = table;
    }

    public void withContinuousMode(boolean isContinuous) {
        this.isContinuous = isContinuous;
    }

    public void withPartitionPredicate(PartitionPredicate partitionPredicate) {
        this.partitionPredicate = partitionPredicate;
    }

    public void withPartitionIdleTime(@Nullable Duration partitionIdleTime) {
        this.partitionIdleTime = partitionIdleTime;
    }

    public void build() {
        // build source from UnawareSourceFunction
        DataStreamSource<AppendCompactTask> source = buildSource();
        if (isContinuous) {
            Preconditions.checkArgument(
                    partitionIdleTime == null, "Streaming mode does not support partitionIdleTime");
        } else if (partitionIdleTime != null) {
            Map<BinaryRow, Long> partitionInfo = getPartitionInfo(table);
            long historyMilli =
                    LocalDateTime.now()
                            .minus(partitionIdleTime)
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli();
            SingleOutputStreamOperator<AppendCompactTask> filterStream =
                    source.filter(
                            task -> {
                                BinaryRow partition = task.partition();
                                return partitionInfo.get(partition) <= historyMilli;
                            });
            source = new DataStreamSource<>(filterStream);
        }

        // from source, construct the full flink job
        sinkFromSource(source);
    }

    private Map<BinaryRow, Long> getPartitionInfo(FileStoreTable table) {
        List<PartitionEntry> partitions = table.newSnapshotReader().partitionEntries();
        return partitions.stream()
                .collect(
                        Collectors.toMap(
                                PartitionEntry::partition, PartitionEntry::lastFileCreationTime));
    }

    private DataStreamSource<AppendCompactTask> buildSource() {

        long scanInterval = table.coreOptions().continuousDiscoveryInterval().toMillis();
        AppendTableCompactSource source =
                new AppendTableCompactSource(table, isContinuous, scanInterval, partitionPredicate);

        return AppendTableCompactSource.buildSource(env, source, tableIdentifier);
    }

    private void sinkFromSource(DataStreamSource<AppendCompactTask> input) {
        DataStream<AppendCompactTask> rebalanced = rebalanceInput(input);

        AppendTableCompactSink.sink(table, rebalanced);
    }

    private DataStream<AppendCompactTask> rebalanceInput(
            DataStreamSource<AppendCompactTask> input) {
        Options conf = Options.fromMap(table.options());
        Integer compactionWorkerParallelism =
                conf.get(FlinkConnectorOptions.UNAWARE_BUCKET_COMPACTION_PARALLELISM);
        PartitionTransformation<AppendCompactTask> transformation =
                new PartitionTransformation<>(
                        input.getTransformation(), new RebalancePartitioner<>());
        if (compactionWorkerParallelism != null) {
            transformation.setParallelism(compactionWorkerParallelism);
        } else {
            // cause source function for unaware-bucket table compaction has only one parallelism,
            // we need to set to default parallelism by hand.
            transformation.setParallelism(env.getParallelism());
        }
        return new DataStream<>(env, transformation);
    }
}
