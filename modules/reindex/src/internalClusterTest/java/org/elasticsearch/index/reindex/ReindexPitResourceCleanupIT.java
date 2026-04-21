/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.reindex;

import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.reindex.ReindexPlugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Arrays;
import java.util.Collection;

import static org.hamcrest.Matchers.equalTo;

/**
 * Integration coverage for reindex releasing point-in-time reader contexts before the request completes.
 * Without that ordering, {@code indices.stats} can briefly report {@code open_contexts > 0} on the source index
 * (see yaml suite {@code reindex/10_basic}).
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST)
public class ReindexPitResourceCleanupIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(ReindexPlugin.class);
    }

    /**
     * After a local PIT-based reindex finishes, the source index must not retain search reader contexts from that reindex.
     */
    public void testSourceIndexSearchOpenContextsZeroAfterReindexUpdatedPath() {
        assumeTrue("reindex PIT path not active on this JVM", ReindexPlugin.REINDEX_PIT_SEARCH_ENABLED);

        createIndex("source");
        createIndex("dest");
        indexRandom(
            true,
            prepareIndex("source").setId("1").setSource("text", "test"),
            prepareIndex("dest").setId("1").setSource("text", "test")
        );

        BulkByScrollResponse reindexResponse = new ReindexRequestBuilder(client()).source("source")
            .destination("dest")
            .refresh(true)
            .get();
        assertThat(reindexResponse.getUpdated(), equalTo(1L));
        assertThat(reindexResponse.getCreated(), equalTo(0L));

        IndicesStatsResponse stats = indicesAdmin().prepareStats("source").get();
        assertThat(stats.getTotal().getSearch().getOpenContexts(), equalTo(0L));
    }
}
