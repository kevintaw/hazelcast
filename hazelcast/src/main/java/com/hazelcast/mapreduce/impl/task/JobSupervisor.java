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

import com.hazelcast.mapreduce.JobPartitionState;
import com.hazelcast.mapreduce.JobProcessInformation;
import com.hazelcast.mapreduce.JobTracker;
import com.hazelcast.mapreduce.Reducer;
import com.hazelcast.mapreduce.impl.AbstractJobTracker;
import com.hazelcast.mapreduce.impl.MapReduceService;
import com.hazelcast.mapreduce.impl.MapReduceUtil;
import com.hazelcast.mapreduce.impl.notification.IntermediateChunkNotification;
import com.hazelcast.mapreduce.impl.notification.LastChunkNotification;
import com.hazelcast.mapreduce.impl.notification.MapReduceNotification;
import com.hazelcast.mapreduce.impl.notification.ReducingFinishedNotification;
import com.hazelcast.mapreduce.impl.operation.GetResultOperationFactory;
import com.hazelcast.mapreduce.impl.operation.RequestPartitionProcessed;
import com.hazelcast.mapreduce.impl.operation.RequestPartitionResult;
import com.hazelcast.nio.Address;
import com.hazelcast.spi.NodeEngine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static com.hazelcast.mapreduce.JobPartitionState.State.REDUCING;
import static com.hazelcast.mapreduce.impl.operation.RequestPartitionResult.ResultState.SUCCESSFUL;

public class JobSupervisor {

    private final ConcurrentMap<Object, Reducer> reducers = new ConcurrentHashMap<Object, Reducer>();
    private final ConcurrentMap<Integer, Set<Address>> remoteReducers = new ConcurrentHashMap<Integer, Set<Address>>();
    private final AtomicReference<DefaultContext> context = new AtomicReference<DefaultContext>();

    private final Address jobOwner;
    private final boolean ownerNode;
    private final AbstractJobTracker jobTracker;
    private final JobTaskConfiguration configuration;
    private final MapReduceService mapReduceService;
    private final ExecutorService executorService;

    private final JobProcessInformationImpl jobProcessInformation;

    public JobSupervisor(JobTaskConfiguration configuration, AbstractJobTracker jobTracker,
                         boolean ownerNode, MapReduceService mapReduceService) {
        this.jobTracker = jobTracker;
        this.ownerNode = ownerNode;
        this.configuration = configuration;
        this.mapReduceService = mapReduceService;
        this.jobOwner = configuration.getJobOwner();
        this.executorService = mapReduceService.getExecutorService(configuration.getName());
        this.jobProcessInformation = new JobProcessInformationImpl(
                configuration.getNodeEngine().getPartitionService().getPartitionCount(), this);

        // Preregister reducer task to handle immediate reducing events
        String name = configuration.getName();
        String jobId = configuration.getJobId();
        jobTracker.registerReducerTask(new ReducerTask(name, jobId, this));
    }

    public MapReduceService getMapReduceService() {
        return mapReduceService;
    }

    public JobTracker getJobTracker() {
        return jobTracker;
    }

    public void startTasks(MappingPhase mappingPhase) {
        // Start map-combiner tasks
        jobTracker.registerMapCombineTask(new MapCombineTask(configuration, this, mappingPhase));
    }

    public void onNotification(MapReduceNotification event) {
        if (event instanceof IntermediateChunkNotification) {
            IntermediateChunkNotification icn = (IntermediateChunkNotification) event;
            ReducerTask reducerTask = jobTracker.getReducerTask(icn.getJobId());
            reducerTask.processChunk(icn.getChunk());
        } else if (event instanceof LastChunkNotification) {
            LastChunkNotification lcn = (LastChunkNotification) event;
            ReducerTask reducerTask = jobTracker.getReducerTask(lcn.getJobId());
            reducerTask.processChunk(lcn.getPartitionId(), lcn.getSender(), lcn.getChunk());
        } else if (event instanceof ReducingFinishedNotification) {
            ReducingFinishedNotification rfn = (ReducingFinishedNotification) event;
            processReducerFinished(rfn);
        }
    }

    public Map<Object, Object> getJobResults() {
        Map<Object, Object> result;
        if (configuration.getReducerFactory() != null) {
            result = new HashMap<Object, Object>();
            for (Map.Entry<Object, Reducer> entry : reducers.entrySet()) {
                result.put(entry.getKey(), entry.getValue().finalizeReduce());
            }
        } else {
            DefaultContext context = this.context.get();
            result = context.finish();
        }
        return result;
    }

    public <KeyIn, ValueIn, ValueOut> Reducer<KeyIn, ValueIn, ValueOut> getReducerByKey(Object key) {
        Reducer reducer = reducers.get(key);
        if (reducer == null && configuration.getReducerFactory() != null) {
            reducer = configuration.getReducerFactory().newReducer(key);
            Reducer oldReducer = reducers.putIfAbsent(key, reducer);
            if (oldReducer != null) {
                reducer = oldReducer;
            } else {
                reducer.beginReduce(key);
            }
        }
        return reducer;
    }

    public void checkFullyProcessed(JobProcessInformation processInformation) {
        if (isOwnerNode()) {
            JobPartitionState[] partitionStates = processInformation.getPartitionStates();
            for (JobPartitionState partitionState : partitionStates) {
                if (partitionState == null
                        || partitionState.getState() != JobPartitionState.State.PROCESSED) {
                    return;
                }
            }

            String name = configuration.getName();
            String jobId = configuration.getJobId();
            NodeEngine nodeEngine = configuration.getNodeEngine();
            List<Map> results = MapReduceUtil.executeOperation(new GetResultOperationFactory(name, jobId),
                    mapReduceService, nodeEngine, true);

            boolean reducedResult = configuration.getReducerFactory() != null;

            if (results != null) {
                Map<Object, Object> mergedResults = new HashMap<Object, Object>();
                for (Map<?, ?> map : results) {
                    for (Map.Entry entry : map.entrySet()) {
                        if (reducedResult) {
                            mergedResults.put(entry.getKey(), entry.getValue());

                        } else {
                            List<Object> list = (List) mergedResults.get(entry.getKey());
                            if (list == null) {
                                list = new ArrayList<Object>();
                                mergedResults.put(entry.getKey(), list);
                            }
                            for (Object value : (List) entry.getValue()) {
                                list.add(value);
                            }
                        }
                    }
                }

                // Get the initial future object to eventually set the result
                TrackableJobFuture future = jobTracker.getTrackableJob(jobId);
                future.setResult(mergedResults);
            }
        }
    }

    public <K, V> DefaultContext<K, V> createContext(MapCombineTask mapCombineTask, int partitionId) {
        DefaultContext<K, V> context = new DefaultContext<K, V>(
                configuration.getCombinerFactory(), partitionId, mapCombineTask);

        if (this.context.compareAndSet(null, context)) {
            return context;
        }
        return this.context.get();
    }

    public void registerReducerEventInterests(int partitionId, Set<Address> remoteReducers) {
        Set<Address> addresses = this.remoteReducers.get(partitionId);
        if (addresses == null) {
            addresses = new CopyOnWriteArraySet<Address>();
            Set<Address> oldSet = this.remoteReducers.putIfAbsent(partitionId, addresses);
            if (oldSet != null) {
                addresses = oldSet;
            }
        }
        addresses.addAll(remoteReducers);
    }

    public Collection<Address> getReducerEventInterests(int partitionId) {
        return this.remoteReducers.get(partitionId);
    }

    public JobProcessInformationImpl getJobProcessInformation() {
        return jobProcessInformation;
    }

    public Address getJobOwner() {
        return jobOwner;
    }

    public boolean isOwnerNode() {
        return ownerNode;
    }

    public JobTaskConfiguration getConfiguration() {
        return configuration;
    }

    private void processReducerFinished(final ReducingFinishedNotification notification) {
        // Just offload it to free the event queue
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                processReducerFinished0(notification);
            }
        });
    }

    private void processReducerFinished0(ReducingFinishedNotification notification) {
        String name = configuration.getName();
        String jobId = configuration.getJobId();
        int partitionId = notification.getPartitionId();
        Address reducerAddress = notification.getAddress();

        if (checkPartitionReductionCompleted(partitionId, reducerAddress)) {
            try {
                RequestPartitionResult result = mapReduceService.processRequest(jobOwner,
                        new RequestPartitionProcessed(name, jobId, partitionId, REDUCING));

                if (result.getResultState() != SUCCESSFUL) {
                    throw new RuntimeException("Could not finalize processing for partitionId " + partitionId);
                }
            } catch (Exception ignore) {
                ignore.printStackTrace();
            }
        }
    }

    private boolean checkPartitionReductionCompleted(int partitionId, Address reducerAddress) {
        Set<Address> remoteAddresses = this.remoteReducers.get(partitionId);
        if (remoteAddresses == null) {
            throw new RuntimeException("Reducer for partition " + partitionId + " not registered");
        }

        remoteAddresses.remove(reducerAddress);
        if (remoteAddresses.size() == 0) {
            if (this.remoteReducers.remove(partitionId) != null) {
                return true;
            }
        }
        return false;
    }

}
