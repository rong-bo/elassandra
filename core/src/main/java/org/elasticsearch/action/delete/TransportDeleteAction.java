/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.delete;

import java.io.IOException;

import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.RoutingMissingException;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.create.TransportCreateIndexAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.AutoCreateIndex;
import org.elasticsearch.action.support.replication.TransportReplicationAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.action.index.MappingUpdatedAction;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

/**
 * Performs the delete operation.
 */
public class TransportDeleteAction extends TransportReplicationAction<DeleteRequest, DeleteRequest, DeleteResponse> {

    private final AutoCreateIndex autoCreateIndex;
    private final TransportCreateIndexAction createIndexAction;
    private final ClusterService clusterService;
    
    @Inject
    public TransportDeleteAction(Settings settings, TransportService transportService, ClusterService clusterService,
                                 IndicesService indicesService, ThreadPool threadPool,
                                 TransportCreateIndexAction createIndexAction, ActionFilters actionFilters,
                                 IndexNameExpressionResolver indexNameExpressionResolver, MappingUpdatedAction mappingUpdatedAction,
                                 AutoCreateIndex autoCreateIndex) {
        super(settings, DeleteAction.NAME, transportService, clusterService, indicesService, threadPool,
                mappingUpdatedAction, actionFilters, indexNameExpressionResolver,
                DeleteRequest.class, DeleteRequest.class, ThreadPool.Names.INDEX);
        this.createIndexAction = createIndexAction;
        this.autoCreateIndex = autoCreateIndex;
        this.clusterService = clusterService;
    }

    @Override
    protected void doExecute(final Task task, final DeleteRequest request, final ActionListener<DeleteResponse> listener) {
        ClusterState state = clusterService.state();
        if (autoCreateIndex.shouldAutoCreate(request.index(), state)) {
            createIndexAction.execute(task, new CreateIndexRequest(request).index(request.index()).cause("auto(delete api)")
                .masterNodeTimeout(request.timeout()), new ActionListener<CreateIndexResponse>() {
                @Override
                public void onResponse(CreateIndexResponse result) {
                    innerExecute(task, request, listener);
                }

                @Override
                public void onFailure(Throwable e) {
                    if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException) {
                        // we have the index, do it
                        innerExecute(task, request, listener);
                    } else {
                        listener.onFailure(e);
                    }
                }
            });
        } else {
            innerExecute(task, request, listener);
        }
    }

    @Override
    protected void resolveRequest(final MetaData metaData, String concreteIndex, DeleteRequest request) {
        /*
        resolveAndValidateRouting(metaData, concreteIndex, request);
        ShardId shardId = clusterService.operationRouting().shardId(clusterService.state(), concreteIndex, request.type(),
                request.id(), request.routing());
        request.setShardId(shardId);
        */
    }

    public static void resolveAndValidateRouting(final MetaData metaData, String concreteIndex, DeleteRequest request) {
        request.routing(metaData.resolveIndexRouting(request.routing(), request.index()));
        if (metaData.hasIndex(concreteIndex)) {
            // check if routing is required, if so, throw error if routing wasn't specified
            MappingMetaData mappingMd = metaData.index(concreteIndex).mappingOrDefault(request.type());
            if (mappingMd != null && mappingMd.routing().required()) {
                if (request.routing() == null) {
                    if (request.versionType() != VersionType.INTERNAL) {
                        // TODO: implement this feature
                        throw new IllegalArgumentException("routing value is required for deleting documents of type [" + request.type()
                            + "] while using version_type [" + request.versionType() + "]");
                    }
                    throw new RoutingMissingException(concreteIndex, request.type(), request.id());
                }
            }
        }
    }

    protected void innerExecute(Task task, final DeleteRequest request, final ActionListener<DeleteResponse> listener) {
        try {
            clusterService.deleteRow(request.index(), request.type(), request.id(), request.consistencyLevel().toCassandraConsistencyLevel());
            DeleteResponse response = new DeleteResponse(request.index(), request.type(), request.id(), 0L, true);
            response.setShardInfo(clusterService.shardInfo(request.index(), request.consistencyLevel().toCassandraConsistencyLevel()));
            listener.onResponse(response);
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    @Override
    protected DeleteResponse newResponseInstance() {
        return new DeleteResponse();
    }

    @Override
    protected Tuple<DeleteResponse, DeleteRequest> shardOperationOnPrimary(MetaData metaData, DeleteRequest request) {
        IndexShard indexShard = indicesService.indexServiceSafe(request.shardId().getIndex()).shardSafe(request.shardId().id());
        final WriteResult<DeleteResponse> result = executeDeleteRequestOnPrimary(clusterService, request, indexShard);
        processAfterWrite(request.refresh(), indexShard, result.location);
        return new Tuple<>(result.response, request);
    }

    public static WriteResult<DeleteResponse> executeDeleteRequestOnPrimary(ClusterService clusterService, DeleteRequest request, IndexShard indexShard) {
        try {
            clusterService.deleteRow(request.index(), request.type(), request.id(), request.consistencyLevel().toCassandraConsistencyLevel());
            DeleteResponse response = new DeleteResponse(request.index(), request.type(), request.id(), 1L, true);
            response.setShardInfo(clusterService.shardInfo(request.index(), request.consistencyLevel().toCassandraConsistencyLevel()));
            return new WriteResult<>(response, null);
        } catch (RequestExecutionException | RequestValidationException | IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    protected void shardOperationOnReplica(DeleteRequest request) {

    }
}
