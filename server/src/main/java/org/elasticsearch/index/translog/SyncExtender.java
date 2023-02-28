/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.index.translog;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.index.shard.ShardId;

import java.util.Optional;

@FunctionalInterface
public interface SyncExtender {

    /**
     * This method is called when an {@link org.elasticsearch.index.shard.IndexShard} syncs its translog, and can be used to extend
     * the sync logic. The sync will be completed with the supplied listener.
     *
     * @param shardId the shard for which the translog sync was called
     * @param location the optional location to sync; if empty, the whole translog needs to be synced
     * @param listener the listener to be completed
     */
    void syncCalled(ShardId shardId, Optional<Translog.Location> location, ActionListener<Void> listener);
}
