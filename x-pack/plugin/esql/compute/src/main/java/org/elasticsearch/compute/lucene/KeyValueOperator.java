/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.lucene;

import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.DocVector;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.IntVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.InternalEngine;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class KeyValueOperator extends SourceOperator {

    private final BlockFactory blockFactory;
    private final int remainingDocs;
    boolean doneCollecting;
    private final List<? extends ShardContext> contexts;

    public static class Factory extends LuceneOperator.Factory {

        List<? extends ShardContext> contexts;

        public Factory(
            List<? extends ShardContext> contexts,
            Function<ShardContext, Query> queryFunction,
            DataPartitioning dataPartitioning,
            int taskConcurrency,
            int maxPageSize,
            int limit
        ) {
            super(contexts, queryFunction, dataPartitioning, 1, limit, ScoreMode.COMPLETE_NO_SCORES, null);
            this.contexts = contexts;
        }

        @Override
        public SourceOperator get(DriverContext driverContext) {
            return new KeyValueOperator(driverContext.blockFactory(), contexts, limit);
        }

        @Override
        public String describe() {
            return "KeyValueOperator[dataPartitioning = " + dataPartitioning + ", limit = " + limit + "]";
        }
    }

    public KeyValueOperator(BlockFactory blockFactory, List<? extends ShardContext> contexts, int limit) {
        this.blockFactory = blockFactory;
        this.remainingDocs = limit;
        this.contexts = contexts;
    }

    @Override
    public boolean isFinished() {
        return doneCollecting;
    }

    @Override
    public Page getOutput() {
        if (isFinished()) {
            return null;
        }
        var blocks = new ArrayList<>();
        int currentPagePos = 0;
        for (var i = 0; i < contexts.size(); i++) {
            var context = contexts.get(i);
            IntBlock shard = null;
            IntBlock leaf = null;
            IntVector docs = null;
            try {
                IndicesService indicesService = context.searchService().getIndicesService();
                ShardId shardId = context.shardId();
                IndexService indexService = indicesService.indexServiceSafe(shardId.getIndex());
                IndexShard indexShard = indexService.getShard(shardId.getId());
                InternalEngine internalEngine = (InternalEngine) indexShard.getEngineOrNull();
                int[] keys = internalEngine.getKvKeys();
                docs = blockFactory.newIntArrayVector(keys, keys.length);

                currentPagePos += docs.getPositionCount();
                shard = blockFactory.newConstantIntBlockWith(context.index(), currentPagePos);
                leaf = blockFactory.newConstantIntBlockWith(0, currentPagePos);

                blocks.add(new DocVector(shard.asVector(), leaf.asVector(), docs, true).asBlock());
            } catch (Exception e) {
                Releasables.closeExpectNoException(shard, leaf, docs);
            }
        }
        finish();
        return new Page(blocks.toArray(new Block[blocks.size()]));
    }

    @Override
    public void finish() {
        doneCollecting = true;
    }

    @Override
    public void close() {
        ;
    }
}
