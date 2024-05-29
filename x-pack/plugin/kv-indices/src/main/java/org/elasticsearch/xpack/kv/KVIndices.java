/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.kv;

import org.apache.lucene.index.IndexCommit;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.core.IOUtils;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineFactory;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.EnginePlugin;
import org.elasticsearch.plugins.Plugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class KVIndices extends Plugin implements ActionPlugin, EnginePlugin {

    private static final Logger logger = LogManager.getLogger(KVIndices.class);

    @Override
    public Optional<EngineFactory> getEngineFactory(IndexSettings indexSettings) {
        if (indexSettings.getValue(RocksEngine.INDEX_KV)) {
            return Optional.of(RocksEngine::new);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(RocksEngine.INDEX_KV);
    }

    @Override
    public void onIndexModule(IndexModule indexModule) {
        indexModule.setIndexCommitListener(new Engine.IndexCommitListener() {
            @Override
            public void onNewCommit(
                ShardId shardId,
                Store store,
                long primaryTerm,
                Engine.IndexCommitRef indexCommitRef,
                Set<String> additionalFiles
            ) {
                logger.info(
                    "New commit for index {}, primary term {}, generation {}",
                    shardId.getIndexName(),
                    primaryTerm,
                    indexCommitRef.getIndexCommit().getGeneration()
                );
                try {
                    IOUtils.close(indexCommitRef);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onIndexCommitDelete(ShardId shardId, IndexCommit deletedCommit) {

            }
        });
    }
}
