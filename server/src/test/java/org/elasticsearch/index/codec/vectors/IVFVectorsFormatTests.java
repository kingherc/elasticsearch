/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.index.codec.vectors;

import com.carrotsearch.randomizedtesting.generators.RandomPicks;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.VectorEncoding;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.index.BaseKnnVectorsFormatTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.codec.vectors.reflect.OffHeapByteSizeUtils;
import org.junit.Before;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.String.format;
import static org.elasticsearch.index.codec.vectors.IVFVectorsFormat.MAX_CENTROIDS_PER_PARENT_CLUSTER;
import static org.elasticsearch.index.codec.vectors.IVFVectorsFormat.MAX_VECTORS_PER_CLUSTER;
import static org.elasticsearch.index.codec.vectors.IVFVectorsFormat.MIN_CENTROIDS_PER_PARENT_CLUSTER;
import static org.elasticsearch.index.codec.vectors.IVFVectorsFormat.MIN_VECTORS_PER_CLUSTER;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.oneOf;

public class IVFVectorsFormatTests extends BaseKnnVectorsFormatTestCase {

    static {
        LogConfigurator.loadLog4jPlugins();
        LogConfigurator.configureESLogging(); // native access requires logging to be initialized
    }
    KnnVectorsFormat format;

    @Before
    @Override
    public void setUp() throws Exception {
        if (rarely()) {
            format = new IVFVectorsFormat(
                random().nextInt(2 * MIN_VECTORS_PER_CLUSTER, IVFVectorsFormat.MAX_VECTORS_PER_CLUSTER),
                random().nextInt(8, IVFVectorsFormat.MAX_CENTROIDS_PER_PARENT_CLUSTER)
            );
        } else {
            // run with low numbers to force many clusters with parents
            format = new IVFVectorsFormat(
                random().nextInt(MIN_VECTORS_PER_CLUSTER, 2 * MIN_VECTORS_PER_CLUSTER),
                random().nextInt(MIN_CENTROIDS_PER_PARENT_CLUSTER, 8)
            );
        }
        super.setUp();
    }

    @Override
    protected VectorSimilarityFunction randomSimilarity() {
        return RandomPicks.randomFrom(
            random(),
            List.of(
                VectorSimilarityFunction.DOT_PRODUCT,
                VectorSimilarityFunction.EUCLIDEAN,
                VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT
            )
        );
    }

    @Override
    protected VectorEncoding randomVectorEncoding() {
        return VectorEncoding.FLOAT32;
    }

    @Override
    public void testSearchWithVisitedLimit() {
        // ivf doesn't enforce visitation limit
    }

    @Override
    protected Codec getCodec() {
        return TestUtil.alwaysKnnVectorsFormat(format);
    }

    @Override
    public void testAdvance() throws Exception {
        // TODO re-enable with hierarchical IVF, clustering as it is is flaky
    }

    public void testToString() {
        FilterCodec customCodec = new FilterCodec("foo", Codec.getDefault()) {
            @Override
            public KnnVectorsFormat knnVectorsFormat() {
                return new IVFVectorsFormat(128, 4);
            }
        };
        String expectedPattern = "IVFVectorsFormat(vectorPerCluster=128)";

        var defaultScorer = format(Locale.ROOT, expectedPattern, "DefaultFlatVectorScorer");
        var memSegScorer = format(Locale.ROOT, expectedPattern, "Lucene99MemorySegmentFlatVectorsScorer");
        assertThat(customCodec.knnVectorsFormat().toString(), is(oneOf(defaultScorer, memSegScorer)));
    }

    public void testLimits() {
        expectThrows(IllegalArgumentException.class, () -> new IVFVectorsFormat(MIN_VECTORS_PER_CLUSTER - 1, 16));
        expectThrows(IllegalArgumentException.class, () -> new IVFVectorsFormat(MAX_VECTORS_PER_CLUSTER + 1, 16));
        expectThrows(IllegalArgumentException.class, () -> new IVFVectorsFormat(128, MIN_CENTROIDS_PER_PARENT_CLUSTER - 1));
        expectThrows(IllegalArgumentException.class, () -> new IVFVectorsFormat(128, MAX_CENTROIDS_PER_PARENT_CLUSTER + 1));
    }

    public void testSimpleOffHeapSize() throws IOException {
        float[] vector = randomVector(random().nextInt(12, 500));
        try (Directory dir = newDirectory(); IndexWriter w = new IndexWriter(dir, newIndexWriterConfig())) {
            Document doc = new Document();
            doc.add(new KnnFloatVectorField("f", vector, VectorSimilarityFunction.EUCLIDEAN));
            w.addDocument(doc);
            w.commit();
            try (IndexReader reader = DirectoryReader.open(w)) {
                LeafReader r = getOnlyLeafReader(reader);
                if (r instanceof CodecReader codecReader) {
                    KnnVectorsReader knnVectorsReader = codecReader.getVectorReader();
                    if (knnVectorsReader instanceof PerFieldKnnVectorsFormat.FieldsReader fieldsReader) {
                        knnVectorsReader = fieldsReader.getFieldReader("f");
                    }
                    var fieldInfo = r.getFieldInfos().fieldInfo("f");
                    var offHeap = OffHeapByteSizeUtils.getOffHeapByteSize(knnVectorsReader, fieldInfo);
                    assertEquals(0, offHeap.size());
                }
            }
        }
    }

    // this is a modified version of lucene's TestSearchWithThreads test case
    public void testWithThreads() throws Exception {
        final int numThreads = random().nextInt(2, 5);
        final int numSearches = atLeast(100);
        final int numDocs = atLeast(1000);
        final int dimensions = random().nextInt(12, 500);
        try (Directory dir = newDirectory(); IndexWriter w = new IndexWriter(dir, newIndexWriterConfig())) {
            for (int docCount = 0; docCount < numDocs; docCount++) {
                final Document doc = new Document();
                doc.add(new KnnFloatVectorField("f", randomVector(dimensions), VectorSimilarityFunction.EUCLIDEAN));
                w.addDocument(doc);
            }
            w.forceMerge(1);
            try (IndexReader reader = DirectoryReader.open(w)) {
                final AtomicBoolean failed = new AtomicBoolean();
                Thread[] threads = new Thread[numThreads];
                for (int threadID = 0; threadID < numThreads; threadID++) {
                    threads[threadID] = new Thread(() -> {
                        try {
                            long totSearch = 0;
                            for (; totSearch < numSearches && failed.get() == false; totSearch++) {
                                float[] vector = randomVector(dimensions);
                                LeafReader leafReader = getOnlyLeafReader(reader);
                                leafReader.searchNearestVectors("f", vector, 10, leafReader.getLiveDocs(), Integer.MAX_VALUE);
                            }
                            assertTrue(totSearch > 0);
                        } catch (Exception exc) {
                            failed.set(true);
                            throw new RuntimeException(exc);
                        }
                    });
                    threads[threadID].setDaemon(true);
                }

                for (Thread t : threads) {
                    t.start();
                }

                for (Thread t : threads) {
                    t.join();
                }
            }
        }
    }
}
