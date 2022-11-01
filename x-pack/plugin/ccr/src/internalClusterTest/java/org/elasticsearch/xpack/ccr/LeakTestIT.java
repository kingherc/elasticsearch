/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.ccr;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.flush.FlushResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.CheckedRunnable;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SystemIndexPlugin;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.aggregations.metrics.Sum;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xpack.CcrIntegTestCase;
import org.elasticsearch.xpack.core.ccr.AutoFollowMetadata;
import org.elasticsearch.xpack.core.ccr.AutoFollowStats;
import org.elasticsearch.xpack.core.ccr.action.ActivateAutoFollowPatternAction;
import org.elasticsearch.xpack.core.ccr.action.CcrStatsAction;
import org.elasticsearch.xpack.core.ccr.action.DeleteAutoFollowPatternAction;
import org.elasticsearch.xpack.core.ccr.action.GetAutoFollowPatternAction;
import org.elasticsearch.xpack.core.ccr.action.PutAutoFollowPatternAction;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.elasticsearch.search.aggregations.AggregationBuilders.sum;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertTrue;

public class LeakTestIT extends CcrIntegTestCase {

    @Override
    protected boolean reuseClusters() {
        return false;
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Stream.concat(super.nodePlugins().stream(), Stream.of(FakeSystemIndex.class)).collect(Collectors.toList());
    }

    public static class FakeSystemIndex extends Plugin implements SystemIndexPlugin {
        public static final String SYSTEM_INDEX_NAME = ".fake-system-index";

        @Override
        public Collection<SystemIndexDescriptor> getSystemIndexDescriptors(Settings settings) {
            return Collections.singletonList(new SystemIndexDescriptor(SYSTEM_INDEX_NAME + "*", "test index"));
        }

        @Override
        public String getFeatureName() {
            return "fake system index";
        }

        @Override
        public String getFeatureDescription() {
            return "fake system index";
        }
    }

    public void testAutoFollow() throws Exception {
        Settings leaderIndexSettings = Settings.builder()
            .put(IndexMetadata.INDEX_NUMBER_OF_SHARDS_SETTING.getKey(), 1)
            .put(IndexMetadata.INDEX_NUMBER_OF_REPLICAS_SETTING.getKey(), 0)
            .build();
        putAutoFollowPatterns("my-pattern", new String[] { "logs-*", "transactions-*" });
        createLeaderIndex("logs-201901", leaderIndexSettings);
        //assertLongBusy(() -> { assertTrue(ESIntegTestCase.indexExists("copy-logs-201901", followerClient())); });
        assertLongBusy(() -> {
            AutoFollowStats autoFollowStats = getAutoFollowStats();
            assertThat(autoFollowStats.getNumberOfSuccessfulFollowIndices(), equalTo(1L));
        });

        SearchResponse searchResponse = leaderClient().prepareSearch("logs-201901")
            .addAggregation(sum("thesum").field("totalBrutto"))
            .get();
        assertNoFailures(searchResponse);
        Sum thesum = searchResponse.getAggregations().get("thesum");
        System.err.println("Final sum: " + thesum.value());
        assertThat(thesum.value(), equalTo(45.));
    }

    private void putAutoFollowPatterns(String name, String[] patterns) {
        putAutoFollowPatterns(name, patterns, Collections.emptyList());
    }

    private void putAutoFollowPatterns(String name, String[] patterns, List<String> exclusionPatterns) {
        PutAutoFollowPatternAction.Request request = new PutAutoFollowPatternAction.Request();
        request.setName(name);
        request.setRemoteCluster("leader_cluster");
        request.setLeaderIndexPatterns(Arrays.asList(patterns));
        request.setLeaderIndexExclusionPatterns(exclusionPatterns);
        // Need to set this, because following an index in the same cluster
        request.setFollowIndexNamePattern("copy-{{leader_index}}");

        assertTrue(followerClient().execute(PutAutoFollowPatternAction.INSTANCE, request).actionGet().isAcknowledged());
    }

    private void deleteAutoFollowPattern(final String name) {
        DeleteAutoFollowPatternAction.Request request = new DeleteAutoFollowPatternAction.Request(name);
        assertTrue(followerClient().execute(DeleteAutoFollowPatternAction.INSTANCE, request).actionGet().isAcknowledged());
    }

    private AutoFollowStats getAutoFollowStats() {
        CcrStatsAction.Request request = new CcrStatsAction.Request();
        return followerClient().execute(CcrStatsAction.INSTANCE, request).actionGet().getAutoFollowStats();
    }

    private void createLeaderIndex(String index, Settings settings) {
        CreateIndexRequest request = new CreateIndexRequest(index);
        request.settings(settings);
        leaderClient().admin().indices().create(request).actionGet();

        int numdocs = 10;
        logger.info("--> indexing [{}] documents into [{}]", numdocs, index);
        IndexRequestBuilder[] builders = new IndexRequestBuilder[numdocs];
        for (int i = 0; i < builders.length; i++) {
            builders[i] = leaderClient().prepareIndex(index, "doc").setId(Integer.toString(i)).setSource("totalBrutto",  i);
        }

        BulkRequestBuilder bulkBuilder = leaderClient().prepareBulk();
        for (IndexRequestBuilder indexRequestBuilder : builders) {
            bulkBuilder.add(indexRequestBuilder);
        }
        BulkResponse actionGet = bulkBuilder.execute().actionGet();
        assertThat(actionGet.hasFailures() ? actionGet.buildFailureMessage() : "", actionGet.hasFailures(), equalTo(false));

        FlushResponse actionGet2 = leaderClient().admin().indices().prepareFlush(index).execute().actionGet();
        for (DefaultShardOperationFailedException failure : actionGet2.getShardFailures()) {
            assertThat("unexpected flush failure " + failure.reason(), failure.status(), equalTo(RestStatus.SERVICE_UNAVAILABLE));
        }

        RefreshResponse actionGet3 = leaderClient().admin()
            .indices()
            .prepareRefresh(index)
            .setIndicesOptions(IndicesOptions.STRICT_EXPAND_OPEN_HIDDEN_FORBID_CLOSED)
            .execute()
            .actionGet();
        assertNoFailures(actionGet);

        long count = leaderClient().search(
            new SearchRequest(new SearchRequest(index).source(new SearchSourceBuilder().size(0).trackTotalHits(true)))
        ).actionGet().getHits().getTotalHits().value;
        assertTrue(count == numdocs);
    }

    private void pauseAutoFollowPattern(final String name) {
        ActivateAutoFollowPatternAction.Request request = new ActivateAutoFollowPatternAction.Request(name, false);
        assertAcked(followerClient().execute(ActivateAutoFollowPatternAction.INSTANCE, request).actionGet());
    }

    private void resumeAutoFollowPattern(final String name) {
        ActivateAutoFollowPatternAction.Request request = new ActivateAutoFollowPatternAction.Request(name, true);
        assertAcked(followerClient().execute(ActivateAutoFollowPatternAction.INSTANCE, request).actionGet());
    }

    private AutoFollowMetadata.AutoFollowPattern getAutoFollowPattern(final String name) {
        GetAutoFollowPatternAction.Request request = new GetAutoFollowPatternAction.Request();
        request.setName(name);
        GetAutoFollowPatternAction.Response response = followerClient().execute(GetAutoFollowPatternAction.INSTANCE, request).actionGet();
        assertTrue(response.getAutoFollowPatterns().containsKey(name));
        return response.getAutoFollowPatterns().get(name);
    }

    private void assertLongBusy(CheckedRunnable<Exception> codeBlock) throws Exception {
        try {
            assertBusy(codeBlock, 120L, TimeUnit.SECONDS);
        } catch (AssertionError ae) {
            AutoFollowStats autoFollowStats = null;
            try {
                autoFollowStats = getAutoFollowStats();
            } catch (Exception e) {
                ae.addSuppressed(e);
            }
            final AutoFollowStats finalAutoFollowStats = autoFollowStats;
            logger.warn(
                () -> String.format(
                    "AssertionError when waiting for auto-follower, auto-follow stats are: %s",
                    finalAutoFollowStats != null ? Strings.toString(finalAutoFollowStats) : "null"
                ),
                ae
            );
            throw ae;
        }
    }
}
