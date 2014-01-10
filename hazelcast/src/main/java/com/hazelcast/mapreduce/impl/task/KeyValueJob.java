/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.mapreduce.impl.task;

import com.hazelcast.cluster.ClusterService;
import com.hazelcast.core.CompletableFuture;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.mapreduce.Collator;
import com.hazelcast.mapreduce.KeyValueSource;
import com.hazelcast.mapreduce.impl.AbstractJob;
import com.hazelcast.mapreduce.impl.AbstractJobTracker;
import com.hazelcast.mapreduce.impl.MapReduceService;
import com.hazelcast.mapreduce.impl.MapReduceUtil;
import com.hazelcast.mapreduce.impl.operation.KeyValueJobOperation;
import com.hazelcast.mapreduce.impl.operation.StartProcessingJobOperation;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.Operation;

import java.util.Collection;

public class KeyValueJob<KeyIn, ValueIn>
        extends AbstractJob<KeyIn, ValueIn> {

    private final NodeEngine nodeEngine;
    private final MapReduceService mapReduceService;

    public KeyValueJob(String name, AbstractJobTracker jobTracker, NodeEngine nodeEngine,
                       MapReduceService mapReduceService,
                       KeyValueSource<KeyIn, ValueIn> keyValueSource) {
        super(name, jobTracker, keyValueSource);
        this.nodeEngine = nodeEngine;
        this.mapReduceService = mapReduceService;
    }

    @Override
    protected <T> CompletableFuture<T> invoke(Collator collator) {
        AbstractJobTracker jobTracker = (AbstractJobTracker) this.jobTracker;
        TrackableJobFuture<T> jobFuture = new TrackableJobFuture<T>(name, jobId, jobTracker, nodeEngine, collator);
        if (jobTracker.registerTrackableJob(jobFuture)) {
            return startSupervisionTask(jobFuture, jobTracker);
        }
        throw new IllegalStateException("Could not register map reduce job");
    }

    private <T> CompletableFuture<T> startSupervisionTask(TrackableJobFuture<T> jobFuture,
                                                          AbstractJobTracker jobTracker) {

        if (chunkSize == -1) {
            chunkSize = jobTracker.getJobTrackerConfig().getChunkSize();
        }

        ClusterService cs = nodeEngine.getClusterService();
        Collection<MemberImpl> members = cs.getMemberList();
        for (MemberImpl member : members) {
            Operation operation = new KeyValueJobOperation<KeyIn, ValueIn>(name, jobId, chunkSize,
                    keyValueSource, mapper, combinerFactory, reducerFactory);

            MapReduceUtil.executeOperation(operation, member.getAddress(), mapReduceService, nodeEngine);
        }

        // After we prepared all the remote systems we can now start the processing
        for (MemberImpl member : members) {
            Operation operation = new StartProcessingJobOperation<KeyIn>(name, jobId, keys, predicate, mapper);
            MapReduceUtil.executeOperation(operation, member.getAddress(), mapReduceService, nodeEngine);
        }
        return jobFuture;
    }

}
