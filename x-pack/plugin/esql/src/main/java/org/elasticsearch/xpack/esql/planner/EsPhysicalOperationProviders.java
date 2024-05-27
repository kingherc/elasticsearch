/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.planner;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.ByteVectorValues;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.LeafMetaData;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.StoredFieldVisitor;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.TermVectors;
import org.apache.lucene.index.Terms;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnCollector;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Bits;
import org.elasticsearch.common.logging.HeaderWarning;
import org.elasticsearch.compute.aggregation.GroupingAggregator;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.ElementType;
import org.elasticsearch.compute.lucene.KeyValueOperator;
import org.elasticsearch.compute.lucene.LuceneCountOperator;
import org.elasticsearch.compute.lucene.LuceneOperator;
import org.elasticsearch.compute.lucene.LuceneSourceOperator;
import org.elasticsearch.compute.lucene.LuceneTopNSourceOperator;
import org.elasticsearch.compute.lucene.TimeSeriesSortedSourceOperatorFactory;
import org.elasticsearch.compute.lucene.ValuesSourceReaderOperator;
import org.elasticsearch.compute.operator.Operator;
import org.elasticsearch.compute.operator.OrdinalsGroupingOperator;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.IndexMode;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.get.GetResult;
import org.elasticsearch.index.mapper.BlockLoader;
import org.elasticsearch.index.mapper.FieldNamesFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.NestedLookup;
import org.elasticsearch.index.mapper.SourceLoader;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.search.NestedHelper;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.search.lookup.SearchLookup;
import org.elasticsearch.search.lookup.Source;
import org.elasticsearch.search.sort.SortAndFormats;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.EsQueryExec;
import org.elasticsearch.xpack.esql.plan.physical.EsQueryExec.FieldSort;
import org.elasticsearch.xpack.esql.plan.physical.FieldExtractExec;
import org.elasticsearch.xpack.esql.planner.LocalExecutionPlanner.DriverParallelism;
import org.elasticsearch.xpack.esql.planner.LocalExecutionPlanner.LocalExecutionPlannerContext;
import org.elasticsearch.xpack.esql.planner.LocalExecutionPlanner.PhysicalOperation;
import org.elasticsearch.xpack.esql.type.EsqlDataTypes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;

import static org.elasticsearch.common.lucene.search.Queries.newNonNestedFilter;
import static org.elasticsearch.compute.lucene.LuceneSourceOperator.NO_LIMIT;
import static org.elasticsearch.index.mapper.MappedFieldType.FieldExtractPreference.NONE;

public class EsPhysicalOperationProviders extends AbstractPhysicalOperationProviders {
    /**
     * Context of each shard we're operating against.
     */
    public interface ShardContext extends org.elasticsearch.compute.lucene.ShardContext {
        /**
         * Build something to load source {@code _source}.
         */
        SourceLoader newSourceLoader();

        /**
         * Convert a {@link QueryBuilder} into a real {@link Query lucene query}.
         */
        Query toQuery(QueryBuilder queryBuilder);

        /**
         * Returns something to load values from this field into a {@link Block}.
         */
        BlockLoader blockLoader(String name, boolean asUnsupportedSource, MappedFieldType.FieldExtractPreference fieldExtractPreference);
    }

    private final List<ShardContext> shardContexts;

    public EsPhysicalOperationProviders(List<ShardContext> shardContexts) {
        this.shardContexts = shardContexts;
    }

    // Only useful for the max doc
    protected class KvIndexReader extends LeafReader {

        @Override
        public CacheHelper getCoreCacheHelper() {
            return null;
        }

        @Override
        public Terms terms(String s) throws IOException {
            return null;
        }

        @Override
        public NumericDocValues getNumericDocValues(String s) throws IOException {
            return null;
        }

        @Override
        public BinaryDocValues getBinaryDocValues(String s) throws IOException {
            return null;
        }

        @Override
        public SortedDocValues getSortedDocValues(String s) throws IOException {
            return null;
        }

        @Override
        public SortedNumericDocValues getSortedNumericDocValues(String s) throws IOException {
            return null;
        }

        @Override
        public SortedSetDocValues getSortedSetDocValues(String s) throws IOException {
            return null;
        }

        @Override
        public NumericDocValues getNormValues(String s) throws IOException {
            return null;
        }

        @Override
        public FloatVectorValues getFloatVectorValues(String s) throws IOException {
            return null;
        }

        @Override
        public ByteVectorValues getByteVectorValues(String s) throws IOException {
            return null;
        }

        @Override
        public void searchNearestVectors(String s, float[] floats, KnnCollector knnCollector, Bits bits) throws IOException {

        }

        @Override
        public void searchNearestVectors(String s, byte[] bytes, KnnCollector knnCollector, Bits bits) throws IOException {

        }

        @Override
        public FieldInfos getFieldInfos() {
            return null;
        }

        @Override
        public Bits getLiveDocs() {
            return null;
        }

        @Override
        public PointValues getPointValues(String s) throws IOException {
            return null;
        }

        @Override
        public void checkIntegrity() throws IOException {

        }

        @Override
        public LeafMetaData getMetaData() {
            return null;
        }

        @Override
        public Fields getTermVectors(int i) throws IOException {
            return null;
        }

        @Override
        public TermVectors termVectors() throws IOException {
            return null;
        }

        @Override
        public int numDocs() {
            return 999;
        }

        @Override
        public int maxDoc() {
            return 999;
        }

        @Override
        public void document(int i, StoredFieldVisitor storedFieldVisitor) throws IOException {

        }

        @Override
        public StoredFields storedFields() throws IOException {
            return null;
        }

        @Override
        protected void doClose() throws IOException {

        }

        @Override
        public CacheHelper getReaderCacheHelper() {
            return null;
        }
    }

    @Override
    public final PhysicalOperation fieldExtractPhysicalOperation(FieldExtractExec fieldExtractExec, PhysicalOperation source) {
        Layout.Builder layout = source.layout.builder();
        var sourceAttr = fieldExtractExec.sourceAttribute();
        List<ValuesSourceReaderOperator.ShardContext> readers = shardContexts.stream().map(s -> {
            if (s instanceof KvShardContext kvShardContext) {
                return new ValuesSourceReaderOperator.ShardContext(
                    new KvIndexReader(),
                    s::newSourceLoader,
                    new ShardId(kvShardContext.ctx.index(), kvShardContext.ctx.getShardId())
                );
            } else {
                return new ValuesSourceReaderOperator.ShardContext(s.searcher().getIndexReader(), s::newSourceLoader);
            }
        }).toList();
        List<ValuesSourceReaderOperator.FieldInfo> fields = new ArrayList<>();
        int docChannel = source.layout.get(sourceAttr.id()).channel();
        var docValuesAttrs = fieldExtractExec.docValuesAttributes();
        for (Attribute attr : fieldExtractExec.attributesToExtract()) {
            layout.append(attr);
            DataType dataType = attr.dataType();
            MappedFieldType.FieldExtractPreference fieldExtractPreference = PlannerUtils.extractPreference(docValuesAttrs.contains(attr));
            ElementType elementType = PlannerUtils.toElementType(dataType, fieldExtractPreference);
            String fieldName = attr.name();
            boolean isUnsupported = EsqlDataTypes.isUnsupported(dataType);
            IntFunction<BlockLoader> loader = s -> shardContexts.get(s).blockLoader(fieldName, isUnsupported, fieldExtractPreference);
            fields.add(new ValuesSourceReaderOperator.FieldInfo(fieldName, elementType, loader));
        }
        return source.with(new ValuesSourceReaderOperator.Factory(fields, readers, docChannel), layout.build());
    }

    public Function<org.elasticsearch.compute.lucene.ShardContext, Query> querySupplier(QueryBuilder builder) {
        QueryBuilder qb = builder == null ? QueryBuilders.matchAllQuery() : builder;
        return ctx -> shardContexts.get(ctx.index()).toQuery(qb);
    }

    @Override
    public final PhysicalOperation sourcePhysicalOperation(EsQueryExec esQueryExec, LocalExecutionPlannerContext context) {
        final LuceneOperator.Factory luceneFactory;

        List<FieldSort> sorts = esQueryExec.sorts();
        List<SortBuilder<?>> fieldSorts = null;
        assert esQueryExec.estimatedRowSize() != null : "estimated row size not initialized";
        int rowEstimatedSize = esQueryExec.estimatedRowSize();
        int limit = esQueryExec.limit() != null ? (Integer) esQueryExec.limit().fold() : NO_LIMIT;
        if (sorts != null && sorts.isEmpty() == false) {
            fieldSorts = new ArrayList<>(sorts.size());
            for (FieldSort sort : sorts) {
                fieldSorts.add(sort.fieldSortBuilder());
            }
            luceneFactory = new LuceneTopNSourceOperator.Factory(
                shardContexts,
                querySupplier(esQueryExec.query()),
                context.queryPragmas().dataPartitioning(),
                context.queryPragmas().taskConcurrency(),
                context.pageSize(rowEstimatedSize),
                limit,
                fieldSorts
            );
        } else {
            if (esQueryExec.indexMode() == IndexMode.TIME_SERIES) {
                luceneFactory = TimeSeriesSortedSourceOperatorFactory.create(
                    limit,
                    context.pageSize(rowEstimatedSize),
                    context.queryPragmas().taskConcurrency(),
                    TimeValue.ZERO,
                    shardContexts,
                    querySupplier(esQueryExec.query())
                );
            } else {
                var firstShardContext = shardContexts.get(0);
                if (firstShardContext instanceof KvShardContext) {
                    luceneFactory = new KeyValueOperator.Factory(
                        shardContexts,
                        querySupplier(esQueryExec.query()),
                        context.queryPragmas().dataPartitioning(),
                        context.queryPragmas().taskConcurrency(),
                        context.pageSize(rowEstimatedSize),
                        limit
                    );
                } else {
                    luceneFactory = new LuceneSourceOperator.Factory(
                        shardContexts,
                        querySupplier(esQueryExec.query()),
                        context.queryPragmas().dataPartitioning(),
                        context.queryPragmas().taskConcurrency(),
                        context.pageSize(rowEstimatedSize),
                        limit
                    );
                }
            }
        }
        Layout.Builder layout = new Layout.Builder();
        layout.append(esQueryExec.output());
        int instanceCount = Math.max(1, luceneFactory.taskConcurrency());
        context.driverParallelism(new DriverParallelism(DriverParallelism.Type.DATA_PARALLELISM, instanceCount));
        return PhysicalOperation.fromSource(luceneFactory, layout.build());
    }

    /**
     * Build a {@link SourceOperator.SourceOperatorFactory} that counts documents in the search index.
     */
    public LuceneCountOperator.Factory countSource(LocalExecutionPlannerContext context, QueryBuilder queryBuilder, Expression limit) {
        return new LuceneCountOperator.Factory(
            shardContexts,
            querySupplier(queryBuilder),
            context.queryPragmas().dataPartitioning(),
            context.queryPragmas().taskConcurrency(),
            limit == null ? NO_LIMIT : (Integer) limit.fold()
        );
    }

    @Override
    public final Operator.OperatorFactory ordinalGroupingOperatorFactory(
        LocalExecutionPlanner.PhysicalOperation source,
        AggregateExec aggregateExec,
        List<GroupingAggregator.Factory> aggregatorFactories,
        Attribute attrSource,
        ElementType groupElementType,
        LocalExecutionPlannerContext context
    ) {
        var sourceAttribute = FieldExtractExec.extractSourceAttributesFrom(aggregateExec.child());
        int docChannel = source.layout.get(sourceAttribute.id()).channel();
        List<ValuesSourceReaderOperator.ShardContext> vsShardContexts = shardContexts.stream()
            .map(s -> new ValuesSourceReaderOperator.ShardContext(s.searcher().getIndexReader(), s::newSourceLoader))
            .toList();
        // The grouping-by values are ready, let's group on them directly.
        // Costin: why are they ready and not already exposed in the layout?
        boolean isUnsupported = EsqlDataTypes.isUnsupported(attrSource.dataType());
        return new OrdinalsGroupingOperator.OrdinalsGroupingOperatorFactory(
            shardIdx -> shardContexts.get(shardIdx).blockLoader(attrSource.name(), isUnsupported, NONE),
            vsShardContexts,
            groupElementType,
            docChannel,
            attrSource.name(),
            aggregatorFactories,
            context.pageSize(aggregateExec.estimatedRowSize())
        );
    }

    public static class DefaultShardContext implements ShardContext {
        private final int index;
        private final SearchExecutionContext ctx;
        private final AliasFilter aliasFilter;

        public DefaultShardContext(int index, SearchExecutionContext ctx, AliasFilter aliasFilter) {
            this.index = index;
            this.ctx = ctx;
            this.aliasFilter = aliasFilter;
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public IndexSearcher searcher() {
            return ctx.searcher();
        }

        @Override
        public Optional<SortAndFormats> buildSort(List<SortBuilder<?>> sorts) throws IOException {
            return SortBuilder.buildSort(sorts, ctx);
        }

        @Override
        public String shardIdentifier() {
            return ctx.getFullyQualifiedIndex().getName() + ":" + ctx.getShardId();
        }

        @Override
        public SourceLoader newSourceLoader() {
            return ctx.newSourceLoader(false);
        }

        @Override
        public Query toQuery(QueryBuilder queryBuilder) {
            Query query = ctx.toQuery(queryBuilder).query();
            NestedLookup nestedLookup = ctx.nestedLookup();
            if (nestedLookup != NestedLookup.EMPTY) {
                NestedHelper nestedHelper = new NestedHelper(nestedLookup, ctx::isFieldMapped);
                if (nestedHelper.mightMatchNestedDocs(query)) {
                    // filter out nested documents
                    query = new BooleanQuery.Builder().add(query, BooleanClause.Occur.MUST)
                        .add(newNonNestedFilter(ctx.indexVersionCreated()), BooleanClause.Occur.FILTER)
                        .build();
                }
            }
            if (aliasFilter != AliasFilter.EMPTY) {
                Query filterQuery = ctx.toQuery(aliasFilter.getQueryBuilder()).query();
                query = new BooleanQuery.Builder().add(query, BooleanClause.Occur.MUST)
                    .add(filterQuery, BooleanClause.Occur.FILTER)
                    .build();
            }
            return query;
        }

        @Override
        public BlockLoader blockLoader(
            String name,
            boolean asUnsupportedSource,
            MappedFieldType.FieldExtractPreference fieldExtractPreference
        ) {
            if (asUnsupportedSource) {
                return BlockLoader.CONSTANT_NULLS;
            }
            MappedFieldType fieldType = ctx.getFieldType(name);
            if (fieldType == null) {
                // the field does not exist in this context
                return BlockLoader.CONSTANT_NULLS;
            }
            BlockLoader loader = fieldType.blockLoader(new MappedFieldType.BlockLoaderContext() {
                @Override
                public String indexName() {
                    return ctx.getFullyQualifiedIndex().getName();
                }

                @Override
                public MappedFieldType.FieldExtractPreference fieldExtractPreference() {
                    return fieldExtractPreference;
                }

                @Override
                public SearchLookup lookup() {
                    return ctx.lookup();
                }

                @Override
                public Set<String> sourcePaths(String name) {
                    return ctx.sourcePath(name);
                }

                @Override
                public String parentField(String field) {
                    return ctx.parentPath(field);
                }

                @Override
                public FieldNamesFieldMapper.FieldNamesFieldType fieldNames() {
                    return (FieldNamesFieldMapper.FieldNamesFieldType) ctx.lookup().fieldType(FieldNamesFieldMapper.NAME);
                }
            });
            if (loader == null) {
                HeaderWarning.addWarning("Field [{}] cannot be retrieved, it is unsupported or not indexed; returning null", name);
                return BlockLoader.CONSTANT_NULLS;
            }

            return loader;
        }
    }

    public static class KvShardContext implements ShardContext {
        private final int index;
        private final SearchExecutionContext ctx;
        private final AliasFilter aliasFilter;
        private final SourceLoader sourceLoader;
        private final Function<Integer, Source> docIdToSource;
        private final SearchService searchService;
        private final ShardId shardId;

        public KvShardContext(int index, SearchExecutionContext ctx, AliasFilter aliasFilter, SearchService searchService) {
            this.index = index;
            this.ctx = ctx;
            this.aliasFilter = aliasFilter;
            this.searchService = searchService;
            this.shardId = new ShardId(ctx.index(), ctx.getShardId());

            IndicesService indicesService = searchService.getIndicesService();
            IndexService indexService = indicesService.indexServiceSafe(ctx.index());
            IndexShard indexShard = indexService.getShard(ctx.getShardId());

            this.docIdToSource = (docId) -> {
                try {
                    GetResult result = indexShard.getService()
                        .get(String.valueOf(docId), null, true, -3, VersionType.INTERNAL, null, false);
                    return Source.fromMap(result.sourceAsMap(), XContentType.JSON);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            };

            this.sourceLoader = new SourceLoader() {
                @Override
                public boolean reordersFieldValues() {
                    return false;
                }

                @Override
                public Leaf leaf(LeafReader reader, int[] docIdsInLeaf) throws IOException {
                    return (storedFieldLoader, docId) -> docIdToSource.apply(docId);
                }

                @Override
                public Set<String> requiredStoredFields() {
                    return Set.of();
                }
            };
        }

        @Override
        public int index() {
            return index;
        }

        @Override
        public IndexSearcher searcher() {
            return ctx.searcher();
        }

        @Override
        public Optional<SortAndFormats> buildSort(List<SortBuilder<?>> sorts) throws IOException {
            return null;
        }

        @Override
        public String shardIdentifier() {
            return ctx.getFullyQualifiedIndex().getName() + ":" + ctx.getShardId();
        }

        @Override
        public SourceLoader newSourceLoader() {
            return sourceLoader;
        }

        @Override
        public Query toQuery(QueryBuilder queryBuilder) {
            return null;
        }

        @Override
        public ShardId shardId() {
            return shardId;
        }

        @Override
        public SearchService searchService() {
            return searchService;
        }

        @Override
        public BlockLoader blockLoader(
            String name,
            boolean asUnsupportedSource,
            MappedFieldType.FieldExtractPreference fieldExtractPreference
        ) {
            if (asUnsupportedSource) {
                return BlockLoader.CONSTANT_NULLS;
            }
            MappedFieldType fieldType = ctx.getFieldType(name);
            if (fieldType == null) {
                // the field does not exist in this context
                return BlockLoader.CONSTANT_NULLS;
            }
            fieldType.alwaysFromSource = true;
            BlockLoader loader = fieldType.blockLoader(new MappedFieldType.BlockLoaderContext() {
                @Override
                public String indexName() {
                    return ctx.getFullyQualifiedIndex().getName();
                }

                @Override
                public MappedFieldType.FieldExtractPreference fieldExtractPreference() {
                    return fieldExtractPreference;
                }

                @Override
                public SearchLookup lookup() {
                    return new SearchLookup(ctx.lookup(), Collections.emptySet()) {
                        @Override
                        public Source getSource(LeafReaderContext ctx, int docId) throws IOException {
                            return docIdToSource.apply(docId);
                        }
                    };
                }

                @Override
                public Set<String> sourcePaths(String name) {
                    return ctx.sourcePath(name);
                }

                @Override
                public String parentField(String field) {
                    return ctx.parentPath(field);
                }

                @Override
                public FieldNamesFieldMapper.FieldNamesFieldType fieldNames() {
                    return (FieldNamesFieldMapper.FieldNamesFieldType) ctx.lookup().fieldType(FieldNamesFieldMapper.NAME);
                }
            });
            if (loader == null) {
                HeaderWarning.addWarning("Field [{}] cannot be retrieved, it is unsupported or not indexed; returning null", name);
                return BlockLoader.CONSTANT_NULLS;
            }

            return loader;
        }
    }
}
