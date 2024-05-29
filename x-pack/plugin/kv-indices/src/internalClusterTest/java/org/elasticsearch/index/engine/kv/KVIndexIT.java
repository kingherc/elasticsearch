/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.index.engine.kv;

import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.Assertions;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.LocalStateCompositeXPackPlugin;
import org.elasticsearch.xpack.esql.action.EsqlQueryAction;
import org.elasticsearch.xpack.esql.action.EsqlQueryRequest;
import org.elasticsearch.xpack.esql.action.EsqlQueryResponse;
import org.elasticsearch.xpack.esql.plugin.EsqlPlugin;
import org.elasticsearch.xpack.kv.KVIndices;
import org.elasticsearch.xpack.kv.RocksEngine;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.index.IndexingPressure.MAX_INDEXING_BYTES;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@ESIntegTestCase.ClusterScope(numDataNodes = 0)
@ESTestCase.WithoutSecurityManager
public class KVIndexIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return List.of(KVIndices.class, LocalStateCompositeXPackPlugin.class, EsqlPlugin.class);
    }

    @Override
    protected boolean addMockInternalEngine() {
        return false;
    }

    protected EsqlQueryResponse run(String query) {
        var request = new EsqlQueryRequest();
        request.query(query);
        try {
            return client().execute(EsqlQueryAction.INSTANCE, request).actionGet(30, TimeUnit.SECONDS);
        } catch (ElasticsearchTimeoutException e) {
            throw new AssertionError("timeout", e);
        }
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal, Settings otherSettings) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal, otherSettings)).put(MAX_INDEXING_BYTES.getKey(), "90%").build();
    }

    public void testPutGetAndESQL() {
        internalCluster().ensureAtLeastNumDataNodes(1);
        boolean kvEnabled = true;

        String indexName = "myindex";
        int numOfShards = randomIntBetween(1, 1);
        int numOfReplicas = randomIntBetween(0, 0);
        assertAcked(
            indicesAdmin().prepareCreate(indexName)
                .setSettings(
                    indexSettings(numOfShards, numOfReplicas).put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                        .put(RocksEngine.INDEX_KV.getKey(), kvEnabled)
                        .build()
                )
        );
        ensureGreen(indexName);

        var bulkRequest = client().prepareBulk();
        int numOfIndexRequests = randomIntBetween(3, 3);
        for (int i = 0; i < numOfIndexRequests; i++) {
            var indexRequest = new IndexRequest(indexName).source("myid", i, "myint", 900 + i, "mytext", "myvalue" + i);
            indexRequest.id(String.valueOf(i));
            bulkRequest.add(indexRequest);
        }
        BulkResponse response = bulkRequest.get();
        assertNoFailures(response);

        List<String> ids = Arrays.stream(response.getItems()).map(BulkItemResponse::getId).toList();
        for (String id : ids) {
            var getResponse = client().prepareGet().setIndex(indexName).setId(id).get();
            logger.warn("Fetching response for ID {}: {}", id, getResponse);
            assertTrue(getResponse.isExists());
        }

        if (kvEnabled == false) {
            refresh("myindex");
        }

        logger.warn("Running ESQL");

        try (EsqlQueryResponse results = run("from myindex")) {
            logger.warn("ESQL results: [{}]", results);
        }

        try (EsqlQueryResponse results = run("from myindex | STATS SUM(myint)")) {
            logger.warn("ESQL results: [{}]", results);
        }

        try (EsqlQueryResponse results = run("from myindex METADATA _source, _id | keep _source, _id")) {
            logger.warn("ESQL results: [{}]", results);
        }

        try (EsqlQueryResponse results = run("from myindex | WHERE myid IN (1, 2)")) {
            logger.warn("ESQL results: [{}]", results);
        }

        System.exit(0);
    }

    private void testPerformance(boolean kvEnabled) throws Exception {
        if (Assertions.ENABLED) {
            throw new AssertionError("This test should be run without assertions, configure -da in IntelliJ run configuration");
        }

        internalCluster().ensureAtLeastNumDataNodes(1);

        String indexName = "myindex";
        int numOfShards = randomIntBetween(1, 1);
        int numOfReplicas = randomIntBetween(0, 0);
        assertAcked(
            indicesAdmin().prepareCreate(indexName)
                .setSettings(
                    indexSettings(numOfShards, numOfReplicas).put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), -1)
                        .put(RocksEngine.INDEX_KV.getKey(), kvEnabled)
                        .build()
                )
                .setMapping("myid", "type=long")
        );
        ensureGreen(indexName);

        int numOfIndexRequests = 2_000_000;
        int bulkSize = numOfIndexRequests / 5;
        logger.info("Running bulk requests for a total of {} index requests", numOfIndexRequests);

        long indexingStart = System.nanoTime();
        List<String> ids = new ArrayList<>(numOfIndexRequests);
        for (int i = 0; i < numOfIndexRequests; i += bulkSize) {
            var bulkRequest = client().prepareBulk();
            for (int j = 0; j < bulkSize; j++) {
                var indexRequest = new IndexRequest(indexName).source("myid", i + j);
                indexRequest.id(String.valueOf(i + j));
                bulkRequest.add(indexRequest);
            }
            BulkResponse response = bulkRequest.get();
            assertNoFailures(response);
            logger.info("Indexed {} documents", i + bulkSize);
            ids.addAll(Arrays.stream(response.getItems()).map(BulkItemResponse::getId).toList());
        }
        long indexingEnd = System.nanoTime();

        long diskStart = System.nanoTime();
        if (kvEnabled) {
            logger.info("Flushing RocksDB (writes memtable to disk)");
            RocksEngine engine = (RocksEngine) internalCluster().getInstance(IndicesService.class)
                .indexService(resolveIndex(indexName))
                .getShard(0)
                .getEngineOrNull();
            engine.writeIndexingBuffer();
        } else {
            logger.info("Refreshing index (writes also indexing buffer to disk)");
            refresh("myindex");
        }
        long diskEnd = System.nanoTime();

        long queryingStart = System.nanoTime();
        var queries = 100_000;
        logger.info("Running {} ESQL queries, each getting 5 random keys", queries);
        for (int i = 0; i < queries; i++) {
            var randomIds = List.of(
                String.valueOf(randomIntBetween(1, 1_999_998)),
                String.valueOf(randomIntBetween(1, 1_999_998)),
                String.valueOf(randomIntBetween(1, 1_999_998)),
                String.valueOf(randomIntBetween(1, 1_999_998)),
                String.valueOf(randomIntBetween(1, 1_999_998))
            );
            var query = "from myindex | WHERE myid IN (" + String.join(", ", randomIds) + ")";
            try (EsqlQueryResponse results = run(query)) {
                long sum = 0;
                var it = results.column(0);
                while (it.hasNext()) {
                    sum += (Long) it.next();
                }
                assertThat((int) sum, greaterThanOrEqualTo(0)); // to compare with expected sum, we should ensure randomIds are unique
            }
        }
        long queryingEnd = System.nanoTime();

        logger.info("Finished!");

        String csv = System.nanoTime()
            + ","
            + kvEnabled
            + ","
            + (indexingEnd - indexingStart)
            + ","
            + (diskEnd - diskStart)
            + ","
            + (queryingEnd - queryingStart)
            + ",\n";
        Files.write(Paths.get("/tmp/results.csv"), csv.getBytes(), StandardOpenOption.APPEND);

        System.exit(0);
    }

    public void testPerformanceLucene() throws Exception {
        testPerformance(false);
    }

    public void testPerformanceRocksDb() throws Exception {
        testPerformance(true);
    }
}
