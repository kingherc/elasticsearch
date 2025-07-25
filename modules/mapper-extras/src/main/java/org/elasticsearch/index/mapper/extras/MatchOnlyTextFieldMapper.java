/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper.extras;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.intervals.Intervals;
import org.apache.lucene.queries.intervals.IntervalsSource;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOFunction;
import org.elasticsearch.common.CheckedIntFunction;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.text.UTF8DecodingReader;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.index.IndexVersions;
import org.elasticsearch.index.analysis.IndexAnalyzers;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.fielddata.FieldDataContext;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.SourceValueFetcherSortedBinaryIndexFieldData;
import org.elasticsearch.index.fielddata.StoredFieldSortedBinaryIndexFieldData;
import org.elasticsearch.index.fieldvisitor.StoredFieldLoader;
import org.elasticsearch.index.mapper.BlockLoader;
import org.elasticsearch.index.mapper.BlockSourceReader;
import org.elasticsearch.index.mapper.BlockStoredFieldsReader;
import org.elasticsearch.index.mapper.DocumentParserContext;
import org.elasticsearch.index.mapper.FieldMapper;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.MapperBuilderContext;
import org.elasticsearch.index.mapper.SourceValueFetcher;
import org.elasticsearch.index.mapper.StringFieldType;
import org.elasticsearch.index.mapper.StringStoredFieldFieldLoader;
import org.elasticsearch.index.mapper.TextFieldMapper;
import org.elasticsearch.index.mapper.TextFieldMapper.TextFieldType;
import org.elasticsearch.index.mapper.TextParams;
import org.elasticsearch.index.mapper.TextSearchInfo;
import org.elasticsearch.index.mapper.ValueFetcher;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.script.field.TextDocValuesField;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.lookup.SourceProvider;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.elasticsearch.index.mapper.TextFieldMapper.Builder.multiFieldsNotStoredByDefaultIndexVersionCheck;

/**
 * A {@link FieldMapper} for full-text fields that only indexes
 * {@link IndexOptions#DOCS} and runs positional queries by looking at the
 * _source.
 */
public class MatchOnlyTextFieldMapper extends FieldMapper {

    public static final String CONTENT_TYPE = "match_only_text";

    public static class Defaults {
        public static final FieldType FIELD_TYPE;

        static {
            final FieldType ft = new FieldType();
            ft.setTokenized(true);
            ft.setStored(false);
            ft.setStoreTermVectors(false);
            ft.setOmitNorms(true);
            ft.setIndexOptions(IndexOptions.DOCS);
            FIELD_TYPE = freezeAndDeduplicateFieldType(ft);
        }

    }

    public static class Builder extends FieldMapper.Builder {

        private final IndexVersion indexCreatedVersion;

        private final Parameter<Map<String, String>> meta = Parameter.metaParam();

        private final TextParams.Analyzers analyzers;
        private final boolean withinMultiField;
        private final boolean storedFieldInBinaryFormat;

        public Builder(
            String name,
            IndexVersion indexCreatedVersion,
            IndexAnalyzers indexAnalyzers,
            boolean withinMultiField,
            boolean storedFieldInBinaryFormat
        ) {
            super(name);
            this.indexCreatedVersion = indexCreatedVersion;
            this.analyzers = new TextParams.Analyzers(
                indexAnalyzers,
                m -> ((MatchOnlyTextFieldMapper) m).indexAnalyzer,
                m -> ((MatchOnlyTextFieldMapper) m).positionIncrementGap,
                indexCreatedVersion
            );
            this.withinMultiField = withinMultiField;
            this.storedFieldInBinaryFormat = storedFieldInBinaryFormat;
        }

        @Override
        protected Parameter<?>[] getParameters() {
            return new Parameter<?>[] { meta };
        }

        private MatchOnlyTextFieldType buildFieldType(MapperBuilderContext context) {
            NamedAnalyzer searchAnalyzer = analyzers.getSearchAnalyzer();
            NamedAnalyzer searchQuoteAnalyzer = analyzers.getSearchQuoteAnalyzer();
            NamedAnalyzer indexAnalyzer = analyzers.getIndexAnalyzer();
            TextSearchInfo tsi = new TextSearchInfo(Defaults.FIELD_TYPE, null, searchAnalyzer, searchQuoteAnalyzer);
            MatchOnlyTextFieldType ft = new MatchOnlyTextFieldType(
                context.buildFullName(leafName()),
                tsi,
                indexAnalyzer,
                context.isSourceSynthetic(),
                meta.getValue(),
                withinMultiField,
                multiFieldsBuilder.hasSyntheticSourceCompatibleKeywordField(),
                storedFieldInBinaryFormat
            );
            return ft;
        }

        @Override
        public MatchOnlyTextFieldMapper build(MapperBuilderContext context) {
            MatchOnlyTextFieldType tft = buildFieldType(context);
            final boolean storeSource;
            if (multiFieldsNotStoredByDefaultIndexVersionCheck(indexCreatedVersion)) {
                storeSource = context.isSourceSynthetic()
                    && withinMultiField == false
                    && multiFieldsBuilder.hasSyntheticSourceCompatibleKeywordField() == false;
            } else {
                storeSource = context.isSourceSynthetic();
            }
            return new MatchOnlyTextFieldMapper(leafName(), Defaults.FIELD_TYPE, tft, builderParams(this, context), storeSource, this);
        }
    }

    private static boolean isSyntheticSourceStoredFieldInBinaryFormat(IndexVersion indexCreatedVersion) {
        return indexCreatedVersion.onOrAfter(IndexVersions.MATCH_ONLY_TEXT_STORED_AS_BYTES)
            || indexCreatedVersion.between(
                IndexVersions.MATCH_ONLY_TEXT_STORED_AS_BYTES_BACKPORT_8_X,
                IndexVersions.UPGRADE_TO_LUCENE_10_0_0
            );
    }

    public static final TypeParser PARSER = new TypeParser(
        (n, c) -> new Builder(
            n,
            c.indexVersionCreated(),
            c.getIndexAnalyzers(),
            c.isWithinMultiField(),
            isSyntheticSourceStoredFieldInBinaryFormat(c.indexVersionCreated())
        )
    );

    public static class MatchOnlyTextFieldType extends StringFieldType {

        private final Analyzer indexAnalyzer;
        private final TextFieldType textFieldType;
        private final String originalName;

        private final boolean withinMultiField;
        private final boolean hasCompatibleMultiFields;
        private final boolean storedFieldInBinaryFormat;

        public MatchOnlyTextFieldType(
            String name,
            TextSearchInfo tsi,
            Analyzer indexAnalyzer,
            boolean isSyntheticSource,
            Map<String, String> meta,
            boolean withinMultiField,
            boolean hasCompatibleMultiFields,
            boolean storedFieldInBinaryFormat
        ) {
            super(name, true, false, false, tsi, meta);
            this.indexAnalyzer = Objects.requireNonNull(indexAnalyzer);
            this.textFieldType = new TextFieldType(name, isSyntheticSource);
            this.originalName = isSyntheticSource ? name + "._original" : null;
            this.withinMultiField = withinMultiField;
            this.hasCompatibleMultiFields = hasCompatibleMultiFields;
            this.storedFieldInBinaryFormat = storedFieldInBinaryFormat;
        }

        public MatchOnlyTextFieldType(String name) {
            this(
                name,
                new TextSearchInfo(Defaults.FIELD_TYPE, null, Lucene.STANDARD_ANALYZER, Lucene.STANDARD_ANALYZER),
                Lucene.STANDARD_ANALYZER,
                false,
                Collections.emptyMap(),
                false,
                false,
                false
            );
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public String familyTypeName() {
            return TextFieldMapper.CONTENT_TYPE;
        }

        @Override
        public ValueFetcher valueFetcher(SearchExecutionContext context, String format) {
            return SourceValueFetcher.toString(name(), context, format);
        }

        private IOFunction<LeafReaderContext, CheckedIntFunction<List<Object>, IOException>> getValueFetcherProvider(
            SearchExecutionContext searchExecutionContext
        ) {
            if (searchExecutionContext.isSourceEnabled() == false) {
                throw new IllegalArgumentException(
                    "Field [" + name() + "] of type [" + CONTENT_TYPE + "] cannot run positional queries since [_source] is disabled."
                );
            }
            if (searchExecutionContext.isSourceSynthetic() && withinMultiField) {
                String parentField = searchExecutionContext.parentPath(name());
                var parent = searchExecutionContext.lookup().fieldType(parentField);

                if (parent instanceof KeywordFieldMapper.KeywordFieldType keywordParent
                    && keywordParent.ignoreAbove() != Integer.MAX_VALUE) {
                    if (parent.isStored()) {
                        return storedFieldFetcher(parentField, keywordParent.originalName());
                    } else if (parent.hasDocValues()) {
                        var ifd = searchExecutionContext.getForField(parent, MappedFieldType.FielddataOperation.SEARCH);
                        return combineFieldFetchers(docValuesFieldFetcher(ifd), storedFieldFetcher(keywordParent.originalName()));
                    }
                }

                if (parent.isStored()) {
                    return storedFieldFetcher(parentField);
                } else if (parent.hasDocValues()) {
                    var ifd = searchExecutionContext.getForField(parent, MappedFieldType.FielddataOperation.SEARCH);
                    return docValuesFieldFetcher(ifd);
                } else {
                    assert false : "parent field should either be stored or have doc values";
                }
            } else if (searchExecutionContext.isSourceSynthetic() && hasCompatibleMultiFields) {
                var mapper = (MatchOnlyTextFieldMapper) searchExecutionContext.getMappingLookup().getMapper(name());
                var kwd = TextFieldMapper.SyntheticSourceHelper.getKeywordFieldMapperForSyntheticSource(mapper);

                if (kwd != null) {
                    var fieldType = kwd.fieldType();

                    if (fieldType.ignoreAbove() != Integer.MAX_VALUE) {
                        if (fieldType.isStored()) {
                            return storedFieldFetcher(fieldType.name(), fieldType.originalName());
                        } else if (fieldType.hasDocValues()) {
                            var ifd = searchExecutionContext.getForField(fieldType, MappedFieldType.FielddataOperation.SEARCH);
                            return combineFieldFetchers(docValuesFieldFetcher(ifd), storedFieldFetcher(fieldType.originalName()));
                        }
                    }

                    if (fieldType.isStored()) {
                        return storedFieldFetcher(fieldType.name());
                    } else if (fieldType.hasDocValues()) {
                        var ifd = searchExecutionContext.getForField(fieldType, MappedFieldType.FielddataOperation.SEARCH);
                        return docValuesFieldFetcher(ifd);
                    } else {
                        assert false : "multi field should either be stored or have doc values";
                    }
                } else {
                    assert false : "multi field of type keyword should exist";
                }
            } else if (searchExecutionContext.isSourceSynthetic()) {
                String name = storedFieldNameForSyntheticSource();
                return storedFieldFetcher(name);
            }
            return context -> {
                ValueFetcher valueFetcher = valueFetcher(searchExecutionContext, null);
                SourceProvider sourceProvider = searchExecutionContext.lookup();
                valueFetcher.setNextReader(context);
                return docID -> {
                    try {
                        return valueFetcher.fetchValues(sourceProvider.getSource(context, docID), docID, new ArrayList<>());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                };
            };
        }

        private static IOFunction<LeafReaderContext, CheckedIntFunction<List<Object>, IOException>> docValuesFieldFetcher(
            IndexFieldData<?> ifd
        ) {
            return context -> {
                var sortedBinaryDocValues = ifd.load(context).getBytesValues();
                return docId -> {
                    if (sortedBinaryDocValues.advanceExact(docId)) {
                        var values = new ArrayList<>(sortedBinaryDocValues.docValueCount());
                        for (int i = 0; i < sortedBinaryDocValues.docValueCount(); i++) {
                            values.add(sortedBinaryDocValues.nextValue().utf8ToString());
                        }
                        return values;
                    } else {
                        return List.of();
                    }
                };
            };
        }

        private static IOFunction<LeafReaderContext, CheckedIntFunction<List<Object>, IOException>> storedFieldFetcher(String... names) {
            var loader = StoredFieldLoader.create(false, Set.of(names));
            return context -> {
                var leafLoader = loader.getLoader(context, null);
                return docId -> {
                    leafLoader.advanceTo(docId);
                    var storedFields = leafLoader.storedFields();
                    if (names.length == 1) {
                        return storedFields.get(names[0]);
                    }

                    List<Object> values = new ArrayList<>();
                    for (var name : names) {
                        var currValues = storedFields.get(name);
                        if (currValues != null) {
                            values.addAll(currValues);
                        }
                    }

                    return values;
                };
            };
        }

        private static IOFunction<LeafReaderContext, CheckedIntFunction<List<Object>, IOException>> combineFieldFetchers(
            IOFunction<LeafReaderContext, CheckedIntFunction<List<Object>, IOException>> primaryFetcher,
            IOFunction<LeafReaderContext, CheckedIntFunction<List<Object>, IOException>> secondaryFetcher
        ) {
            return context -> {
                var primaryGetter = primaryFetcher.apply(context);
                var secondaryGetter = secondaryFetcher.apply(context);
                return docId -> {
                    List<Object> values = new ArrayList<>();
                    var primary = primaryGetter.apply(docId);
                    if (primary != null) {
                        values.addAll(primary);
                    }

                    var secondary = secondaryGetter.apply(docId);
                    if (secondary != null) {
                        values.addAll(secondary);
                    }

                    assert primary != null || secondary != null;

                    return values;
                };
            };
        }

        private Query toQuery(Query query, SearchExecutionContext searchExecutionContext) {
            return new ConstantScoreQuery(
                new SourceConfirmedTextQuery(query, getValueFetcherProvider(searchExecutionContext), indexAnalyzer)
            );
        }

        private IntervalsSource toIntervalsSource(
            IntervalsSource source,
            Query approximation,
            SearchExecutionContext searchExecutionContext
        ) {
            return new SourceIntervalsSource(source, approximation, getValueFetcherProvider(searchExecutionContext), indexAnalyzer);
        }

        @Override
        public Query termQuery(Object value, SearchExecutionContext context) {
            // Disable scoring
            return new ConstantScoreQuery(super.termQuery(value, context));
        }

        @Override
        public Query fuzzyQuery(
            Object value,
            Fuzziness fuzziness,
            int prefixLength,
            int maxExpansions,
            boolean transpositions,
            SearchExecutionContext context,
            MultiTermQuery.RewriteMethod rewriteMethod
        ) {
            // Disable scoring
            return new ConstantScoreQuery(
                super.fuzzyQuery(value, fuzziness, prefixLength, maxExpansions, transpositions, context, rewriteMethod)
            );
        }

        @Override
        public IntervalsSource termIntervals(BytesRef term, SearchExecutionContext context) {
            return toIntervalsSource(Intervals.term(term), new TermQuery(new Term(name(), term)), context);
        }

        @Override
        public IntervalsSource prefixIntervals(BytesRef term, SearchExecutionContext context) {
            return toIntervalsSource(
                Intervals.prefix(term, IndexSearcher.getMaxClauseCount()),
                new PrefixQuery(new Term(name(), term)),
                context
            );
        }

        @Override
        public IntervalsSource fuzzyIntervals(
            String term,
            int maxDistance,
            int prefixLength,
            boolean transpositions,
            SearchExecutionContext context
        ) {
            FuzzyQuery fuzzyQuery = new FuzzyQuery(
                new Term(name(), term),
                maxDistance,
                prefixLength,
                IndexSearcher.getMaxClauseCount(),
                transpositions,
                MultiTermQuery.CONSTANT_SCORE_BLENDED_REWRITE
            );
            IntervalsSource fuzzyIntervals = Intervals.multiterm(fuzzyQuery.getAutomata(), IndexSearcher.getMaxClauseCount(), term);
            return toIntervalsSource(fuzzyIntervals, fuzzyQuery, context);
        }

        @Override
        public IntervalsSource wildcardIntervals(BytesRef pattern, SearchExecutionContext context) {
            return toIntervalsSource(
                Intervals.wildcard(pattern, IndexSearcher.getMaxClauseCount()),
                new MatchAllDocsQuery(), // wildcard queries can be expensive, what should the approximation be?
                context
            );
        }

        @Override
        public IntervalsSource regexpIntervals(BytesRef pattern, SearchExecutionContext context) {
            return toIntervalsSource(
                Intervals.regexp(pattern, IndexSearcher.getMaxClauseCount()),
                new MatchAllDocsQuery(), // regexp queries can be expensive, what should the approximation be?
                context
            );
        }

        @Override
        public IntervalsSource rangeIntervals(
            BytesRef lowerTerm,
            BytesRef upperTerm,
            boolean includeLower,
            boolean includeUpper,
            SearchExecutionContext context
        ) {
            return toIntervalsSource(
                Intervals.range(lowerTerm, upperTerm, includeLower, includeUpper, IndexSearcher.getMaxClauseCount()),
                new MatchAllDocsQuery(), // range queries can be expensive, what should the approximation be?
                context
            );
        }

        @Override
        public Query phraseQuery(TokenStream stream, int slop, boolean enablePosIncrements, SearchExecutionContext queryShardContext)
            throws IOException {
            final Query query = textFieldType.phraseQuery(stream, slop, enablePosIncrements, queryShardContext);
            return toQuery(query, queryShardContext);
        }

        @Override
        public Query multiPhraseQuery(
            TokenStream stream,
            int slop,
            boolean enablePositionIncrements,
            SearchExecutionContext queryShardContext
        ) throws IOException {
            final Query query = textFieldType.multiPhraseQuery(stream, slop, enablePositionIncrements, queryShardContext);
            return toQuery(query, queryShardContext);
        }

        @Override
        public Query phrasePrefixQuery(TokenStream stream, int slop, int maxExpansions, SearchExecutionContext queryShardContext)
            throws IOException {
            final Query query = textFieldType.phrasePrefixQuery(stream, slop, maxExpansions, queryShardContext);
            return toQuery(query, queryShardContext);
        }

        private static class BytesFromMixedStringsBytesRefBlockLoader extends BlockStoredFieldsReader.StoredFieldsBlockLoader {
            BytesFromMixedStringsBytesRefBlockLoader(String field) {
                super(field);
            }

            @Override
            public Builder builder(BlockFactory factory, int expectedCount) {
                return factory.bytesRefs(expectedCount);
            }

            @Override
            public RowStrideReader rowStrideReader(LeafReaderContext context) throws IOException {
                return new BlockStoredFieldsReader.Bytes(field) {
                    private final BytesRef scratch = new BytesRef();

                    @Override
                    protected BytesRef toBytesRef(Object v) {
                        if (v instanceof BytesRef b) {
                            return b;
                        } else {
                            assert v instanceof String;
                            return BlockSourceReader.toBytesRef(scratch, v.toString());
                        }
                    }
                };
            }
        }

        @Override
        public BlockLoader blockLoader(BlockLoaderContext blContext) {
            if (textFieldType.isSyntheticSource()) {
                if (storedFieldInBinaryFormat) {
                    return new BlockStoredFieldsReader.BytesFromBytesRefsBlockLoader(storedFieldNameForSyntheticSource());
                } else {
                    return new BytesFromMixedStringsBytesRefBlockLoader(storedFieldNameForSyntheticSource());
                }
            }
            SourceValueFetcher fetcher = SourceValueFetcher.toString(blContext.sourcePaths(name()));
            // MatchOnlyText never has norms, so we have to use the field names field
            BlockSourceReader.LeafIteratorLookup lookup = BlockSourceReader.lookupFromFieldNames(blContext.fieldNames(), name());
            return new BlockSourceReader.BytesRefsBlockLoader(fetcher, lookup);
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(FieldDataContext fieldDataContext) {
            if (fieldDataContext.fielddataOperation() != FielddataOperation.SCRIPT) {
                throw new IllegalArgumentException(CONTENT_TYPE + " fields do not support sorting and aggregations");
            }
            if (textFieldType.isSyntheticSource()) {
                return (cache, breaker) -> new StoredFieldSortedBinaryIndexFieldData(
                    storedFieldNameForSyntheticSource(),
                    CoreValuesSourceType.KEYWORD,
                    TextDocValuesField::new
                ) {
                    @Override
                    protected BytesRef storedToBytesRef(Object stored) {
                        if (stored instanceof BytesRef storedBytes) {
                            return storedBytes;
                        } else {
                            assert stored instanceof String;
                            return new BytesRef(stored.toString());
                        }
                    }
                };
            }
            return new SourceValueFetcherSortedBinaryIndexFieldData.Builder(
                name(),
                CoreValuesSourceType.KEYWORD,
                SourceValueFetcher.toString(fieldDataContext.sourcePathsLookup().apply(name())),
                fieldDataContext.lookupSupplier().get(),
                TextDocValuesField::new
            );
        }

        private String storedFieldNameForSyntheticSource() {
            return originalName;
        }
    }

    private final IndexVersion indexCreatedVersion;
    private final IndexAnalyzers indexAnalyzers;
    private final NamedAnalyzer indexAnalyzer;
    private final int positionIncrementGap;
    private final boolean storeSource;
    private final FieldType fieldType;
    private final boolean withinMultiField;
    private final boolean storedFieldInBinaryFormat;

    private MatchOnlyTextFieldMapper(
        String simpleName,
        FieldType fieldType,
        MatchOnlyTextFieldType mappedFieldType,
        BuilderParams builderParams,
        boolean storeSource,
        Builder builder
    ) {
        super(simpleName, mappedFieldType, builderParams);
        assert mappedFieldType.getTextSearchInfo().isTokenized();
        assert mappedFieldType.hasDocValues() == false;
        this.fieldType = freezeAndDeduplicateFieldType(fieldType);
        this.indexCreatedVersion = builder.indexCreatedVersion;
        this.indexAnalyzers = builder.analyzers.indexAnalyzers;
        this.indexAnalyzer = builder.analyzers.getIndexAnalyzer();
        this.positionIncrementGap = builder.analyzers.positionIncrementGap.getValue();
        this.storeSource = storeSource;
        this.withinMultiField = builder.withinMultiField;
        this.storedFieldInBinaryFormat = builder.storedFieldInBinaryFormat;
    }

    @Override
    public Map<String, NamedAnalyzer> indexAnalyzers() {
        return Map.of(mappedFieldType.name(), indexAnalyzer);
    }

    @Override
    public FieldMapper.Builder getMergeBuilder() {
        return new Builder(leafName(), indexCreatedVersion, indexAnalyzers, withinMultiField, storedFieldInBinaryFormat).init(this);
    }

    @Override
    protected void parseCreateField(DocumentParserContext context) throws IOException {
        final var value = context.parser().optimizedTextOrNull();

        if (value == null) {
            return;
        }

        final var utfBytes = value.bytes();
        Field field = new Field(fieldType().name(), new UTF8DecodingReader(utfBytes), fieldType);
        context.doc().add(field);
        context.addToFieldNames(fieldType().name());

        if (storeSource) {
            if (storedFieldInBinaryFormat) {
                final var bytesRef = new BytesRef(utfBytes.bytes(), utfBytes.offset(), utfBytes.length());
                context.doc().add(new StoredField(fieldType().storedFieldNameForSyntheticSource(), bytesRef));
            } else {
                context.doc().add(new StoredField(fieldType().storedFieldNameForSyntheticSource(), value.string()));
            }
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    public MatchOnlyTextFieldType fieldType() {
        return (MatchOnlyTextFieldType) super.fieldType();
    }

    @Override
    protected SyntheticSourceSupport syntheticSourceSupport() {
        if (storeSource) {
            return new SyntheticSourceSupport.Native(
                () -> new StringStoredFieldFieldLoader(fieldType().storedFieldNameForSyntheticSource(), fieldType().name(), leafName()) {
                    @Override
                    protected void write(XContentBuilder b, Object value) throws IOException {
                        if (value instanceof BytesRef valueBytes) {
                            b.value(valueBytes.utf8ToString());
                        } else {
                            assert value instanceof String;
                            b.value(value.toString());
                        }
                    }
                }
            );
        } else {
            var kwd = TextFieldMapper.SyntheticSourceHelper.getKeywordFieldMapperForSyntheticSource(this);
            if (kwd != null) {
                return new SyntheticSourceSupport.Native(() -> kwd.syntheticFieldLoader(fullPath(), leafName()));
            }
            assert false : "there should be a suite field mapper with native synthetic source support";
            return super.syntheticSourceSupport();
        }
    }
}
