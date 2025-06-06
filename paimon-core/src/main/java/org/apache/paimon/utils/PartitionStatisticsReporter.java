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

package org.apache.paimon.utils;

import org.apache.paimon.Snapshot;
import org.apache.paimon.fs.Path;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.partition.PartitionStatistics;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.PartitionHandler;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.ScanMode;
import org.apache.paimon.table.source.snapshot.SnapshotReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.apache.paimon.utils.PartitionPathUtils.extractPartitionSpecFromPath;

/** Action to report the table statistic from the latest snapshot to HMS. */
public class PartitionStatisticsReporter implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionStatisticsReporter.class);

    private final PartitionHandler partitionHandler;
    private final SnapshotReader snapshotReader;
    private final SnapshotManager snapshotManager;

    public PartitionStatisticsReporter(FileStoreTable table, PartitionHandler partitionHandler) {
        this.partitionHandler =
                Preconditions.checkNotNull(
                        partitionHandler, "the partition handler factory is null");
        this.snapshotReader = table.newSnapshotReader();
        this.snapshotManager = table.snapshotManager();
    }

    public void report(String partition, long modifyTimeMillis) throws Exception {
        Snapshot snapshot = snapshotManager.latestSnapshot();
        if (snapshot != null) {
            LinkedHashMap<String, String> partitionSpec =
                    extractPartitionSpecFromPath(new Path(partition));
            List<DataSplit> splits =
                    new ArrayList<>(
                            snapshotReader
                                    .withMode(ScanMode.ALL)
                                    .withPartitionFilter(partitionSpec)
                                    .withSnapshot(snapshot)
                                    .read()
                                    .dataSplits());
            long rowCount = 0;
            long totalSize = 0;
            long fileCount = 0;
            for (DataSplit split : splits) {
                List<DataFileMeta> fileMetas = split.dataFiles();
                rowCount += split.rowCount();
                fileCount += fileMetas.size();
                for (DataFileMeta fileMeta : fileMetas) {
                    totalSize += fileMeta.fileSize();
                }
            }

            PartitionStatistics partitionStats =
                    new PartitionStatistics(
                            partitionSpec, rowCount, totalSize, fileCount, modifyTimeMillis);
            LOG.info("alter partition {} with statistic {}.", partitionSpec, partitionStats);
            partitionHandler.alterPartitions(Collections.singletonList(partitionStats));
        }
    }

    @Override
    public void close() throws IOException {
        try {
            partitionHandler.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
