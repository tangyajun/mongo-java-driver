/*
 * Copyright (c) 2008-2014 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.operation;

import com.mongodb.MongoException;
import com.mongodb.MongoNamespace;
import com.mongodb.WriteConcern;
import com.mongodb.WriteConcernException;
import com.mongodb.WriteConcernResult;
import com.mongodb.async.MongoFuture;
import com.mongodb.async.SingleResultCallback;
import com.mongodb.async.SingleResultFuture;
import com.mongodb.binding.AsyncWriteBinding;
import com.mongodb.binding.WriteBinding;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteException;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.connection.Connection;
import com.mongodb.protocol.AcknowledgedWriteConcernResult;
import com.mongodb.protocol.WriteCommandProtocol;
import com.mongodb.protocol.WriteProtocol;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

import static com.mongodb.assertions.Assertions.notNull;
import static com.mongodb.operation.OperationHelper.AsyncCallableWithConnection;
import static com.mongodb.operation.OperationHelper.CallableWithConnection;
import static com.mongodb.operation.OperationHelper.DUPLICATE_KEY_ERROR_CODES;
import static com.mongodb.operation.OperationHelper.serverIsAtLeastVersionTwoDotSix;
import static com.mongodb.operation.OperationHelper.withConnection;
import static com.mongodb.operation.WriteRequest.Type.DELETE;
import static com.mongodb.operation.WriteRequest.Type.INSERT;
import static com.mongodb.operation.WriteRequest.Type.REPLACE;
import static com.mongodb.operation.WriteRequest.Type.UPDATE;


/**
 * Abstract base class for write operations.
 *
 * @since 3.0
 */
public abstract class BaseWriteOperation implements AsyncWriteOperation<WriteConcernResult>, WriteOperation<WriteConcernResult> {

    private final WriteConcern writeConcern;
    private final MongoNamespace namespace;
    private final boolean ordered;

    /**
     * Construct an instance
     * @param namespace the database and collection namespace for the operation.
     * @param ordered whether the writes are ordered.
     * @param writeConcern the write concern for the operation.
     */
    public BaseWriteOperation(final MongoNamespace namespace, final boolean ordered, final WriteConcern writeConcern) {
        this.ordered = ordered;
        this.namespace = notNull("namespace", namespace);
        this.writeConcern = notNull("writeConcern", writeConcern);
    }

    /**
     * Gets the namespace of the collection to write to.
     *
     * @return the namespace
     */
    public MongoNamespace getNamespace() {
        return namespace;
    }

    /**
     * Gets the write concern to apply
     *
     * @return the write concern
     */
    public WriteConcern getWriteConcern() {
        return writeConcern;
    }

    /**
     * Gets whether the writes are ordered.  If true, no more writes will be executed after the first failure.
     *
     * @return whether the writes are ordered
     */
    public boolean isOrdered() {
        return ordered;
    }

    @Override
    public WriteConcernResult execute(final WriteBinding binding) {
        return withConnection(binding, new CallableWithConnection<WriteConcernResult>() {
            @Override
            public WriteConcernResult call(final Connection connection) {
                try {
                    if (writeConcern.isAcknowledged() && serverIsAtLeastVersionTwoDotSix(connection)) {
                        return translateBulkWriteResult(getCommandProtocol().execute(connection));
                    } else {
                        return getWriteProtocol().execute(connection);
                    }
                } catch (BulkWriteException e) {
                    throw convertBulkWriteException(e);
                }
            }
        });
    }

    @Override
    public MongoFuture<WriteConcernResult> executeAsync(final AsyncWriteBinding binding) {
        return withConnection(binding, new AsyncCallableWithConnection<WriteConcernResult>() {

            @Override
            public MongoFuture<WriteConcernResult> call(final Connection connection) {
                final SingleResultFuture<WriteConcernResult> future = new SingleResultFuture<WriteConcernResult>();
                if (writeConcern.isAcknowledged() && serverIsAtLeastVersionTwoDotSix(connection)) {
                    getCommandProtocol().executeAsync(connection)
                                        .register(new SingleResultCallback<BulkWriteResult>() {
                                            @Override
                                            public void onResult(final BulkWriteResult result, final MongoException e) {
                                                if (e != null) {
                                                    future.init(null, translateException(e));
                                                } else {
                                                    future.init(translateBulkWriteResult(result), null);
                                                }
                                            }
                                        });
                } else {
                    getWriteProtocol().executeAsync(connection).register(new SingleResultCallback<WriteConcernResult>() {
                        @Override
                        public void onResult(final WriteConcernResult result, final MongoException e) {
                            if (e != null) {
                                future.init(null, translateException(e));
                            } else {
                                future.init(result, null);
                            }
                        }
                    });
                }
                return future;
            }
        });
    }

    /**
     * Gets the write protocol
     *
     * @return the write protocol
     */
    protected abstract WriteProtocol getWriteProtocol();

    /**
     * Gets the write command protocol.
     *
     * @return the write command protocol
     */
    protected abstract WriteCommandProtocol getCommandProtocol();

    private MongoException translateException(final MongoException e) {
        MongoException checkedError = e;
        if (e instanceof BulkWriteException) {
            checkedError = convertBulkWriteException((BulkWriteException) e);
        }
        return checkedError;
    }

    @SuppressWarnings("deprecation")
    private MongoException convertBulkWriteException(final BulkWriteException e) {
        BulkWriteError lastError = getLastError(e);
        if (lastError != null) {
            if (DUPLICATE_KEY_ERROR_CODES.contains(lastError.getCode())) {
                return new MongoException.DuplicateKey(manufactureGetLastErrorResponse(e), e.getServerAddress(),
                                                       translateBulkWriteResult(e.getWriteResult()));
            } else {
                return new WriteConcernException(manufactureGetLastErrorResponse(e), e.getServerAddress(),
                                                 translateBulkWriteResult(e.getWriteResult()));
            }
        } else {
            return new WriteConcernException(manufactureGetLastErrorResponse(e), e.getServerAddress(),
                                             translateBulkWriteResult(e.getWriteResult()));
        }

    }

    private BsonDocument manufactureGetLastErrorResponse(final BulkWriteException e) {
        BsonDocument response = new BsonDocument();
        addBulkWriteResultToResponse(e.getWriteResult(), response);
        if (e.getWriteConcernError() != null) {
            response.putAll(e.getWriteConcernError().getDetails());
        }
        if (getLastError(e) != null) {
            response.put("err", new BsonString(getLastError(e).getMessage()));
            response.put("code", new BsonInt32(getLastError(e).getCode()));
            response.putAll(getLastError(e).getDetails());

        } else if (e.getWriteConcernError() != null) {
            response.put("err", new BsonString(e.getWriteConcernError().getMessage()));
            response.put("code", new BsonInt32(e.getWriteConcernError().getCode()));
        }
        return response;
    }

    private void addBulkWriteResultToResponse(final BulkWriteResult bulkWriteResult, final BsonDocument response) {
        response.put("ok", new BsonInt32(1));
        if (getType() == INSERT) {
            response.put("n", new BsonInt32(0));
        } else if (getType() == DELETE) {
            response.put("n", new BsonInt32(bulkWriteResult.getRemovedCount()));
        } else if (getType() == UPDATE || getType() == REPLACE) {
            response.put("n", new BsonInt32(bulkWriteResult.getMatchedCount() + bulkWriteResult.getUpserts().size()));
            if (bulkWriteResult.getUpserts().isEmpty()) {
                response.put("updatedExisting", BsonBoolean.TRUE);
            } else {
                response.put("updatedExisting", BsonBoolean.FALSE);
                response.put("upserted", bulkWriteResult.getUpserts().get(0).getId());
            }
        }
    }

    private WriteConcernResult translateBulkWriteResult(final BulkWriteResult bulkWriteResult) {
        return new AcknowledgedWriteConcernResult(getCount(bulkWriteResult), getUpdatedExisting(bulkWriteResult),
                                           bulkWriteResult.getUpserts().isEmpty()
                                           ? null : bulkWriteResult.getUpserts().get(0).getId());
    }

    protected abstract WriteRequest.Type getType();

    protected abstract int getCount(BulkWriteResult bulkWriteResult);

    protected boolean getUpdatedExisting(final BulkWriteResult bulkWriteResult) {
        return false;
    }

    private BulkWriteError getLastError(final BulkWriteException e) {
        return e.getWriteErrors().isEmpty() ? null : e.getWriteErrors().get(e.getWriteErrors().size() - 1);

    }
}