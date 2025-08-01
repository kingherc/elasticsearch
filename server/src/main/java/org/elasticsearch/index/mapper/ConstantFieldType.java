/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import org.apache.lucene.util.automaton.CharacterRunAutomaton;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.SearchExecutionContext;

import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A {@link MappedFieldType} that has the same value for all documents.
 * Factory methods for queries are called at rewrite time so they should be
 * cheap. In particular they should not read data from disk or perform a
 * network call. Furthermore they may only return a {@link MatchAllDocsQuery}
 * or a {@link MatchNoDocsQuery}.
 */
public abstract class ConstantFieldType extends MappedFieldType {

    @SuppressWarnings("this-escape")
    public ConstantFieldType(String name, Map<String, String> meta) {
        super(name, true, false, true, TextSearchInfo.SIMPLE_MATCH_WITHOUT_TERMS, meta);
        assert isSearchable();
    }

    @Override
    public final boolean isAggregatable() {
        return true;
    }

    /**
     * Return whether the constant value of this field matches the provided {@code pattern}
     * as documented in {@link Regex#simpleMatch}.
     */
    protected abstract boolean matches(String pattern, boolean caseInsensitive, QueryRewriteContext context);

    private static String valueToString(Object value) {
        return value instanceof BytesRef ? ((BytesRef) value).utf8ToString() : value.toString();
    }

    @Override
    public final Query termQuery(Object value, SearchExecutionContext context) {
        return internalTermQuery(value, context);
    }

    public final Query internalTermQuery(Object value, QueryRewriteContext context) {
        String pattern = valueToString(value);
        if (matches(pattern, false, context)) {
            return Queries.newMatchAllQuery();
        } else {
            return new MatchNoDocsQuery();
        }
    }

    @Override
    public final Query termQueryCaseInsensitive(Object value, SearchExecutionContext context) {
        return internalTermQueryCaseInsensitive(value, context);
    }

    public final Query internalTermQueryCaseInsensitive(Object value, QueryRewriteContext context) {
        String pattern = valueToString(value);
        if (matches(pattern, true, context)) {
            return Queries.newMatchAllQuery();
        } else {
            return new MatchNoDocsQuery();
        }
    }

    @Override
    public final Query termsQuery(Collection<?> values, SearchExecutionContext context) {
        return innerTermsQuery(values, context);
    }

    public final Query innerTermsQuery(Collection<?> values, QueryRewriteContext context) {
        for (Object value : values) {
            String pattern = valueToString(value);
            if (matches(pattern, false, context)) {
                // `terms` queries are a disjunction, so one matching term is enough
                return Queries.newMatchAllQuery();
            }
        }
        return new MatchNoDocsQuery();
    }

    @Override
    public final Query prefixQuery(
        String prefix,
        @Nullable MultiTermQuery.RewriteMethod method,
        boolean caseInsensitive,
        SearchExecutionContext context
    ) {
        return prefixQuery(prefix, caseInsensitive, context);
    }

    public final Query prefixQuery(String prefix, boolean caseInsensitive, QueryRewriteContext context) {
        String pattern = prefix + "*";
        if (matches(pattern, caseInsensitive, context)) {
            return Queries.newMatchAllQuery();
        } else {
            return new MatchNoDocsQuery();
        }
    }

    @Override
    public final Query wildcardQuery(
        String value,
        @Nullable MultiTermQuery.RewriteMethod method,
        boolean caseInsensitive,
        SearchExecutionContext context
    ) {
        return wildcardQuery(value, caseInsensitive, context);
    }

    public final Query wildcardQuery(String value, boolean caseInsensitive, QueryRewriteContext context) {
        if (matches(value, caseInsensitive, context)) {
            return Queries.newMatchAllQuery();
        } else {
            return new MatchNoDocsQuery();
        }
    }

    /**
     * Returns a query that matches all documents or no documents
     * It usually calls {@link #wildcardQuery(String, boolean, QueryRewriteContext)}
     * except for IndexFieldType which overrides this method to use its own matching logic.
     */
    public Query wildcardLikeQuery(String value, boolean caseInsensitive, QueryRewriteContext context) {
        return wildcardQuery(value, caseInsensitive, context);
    }

    @Override
    public final boolean fieldHasValue(FieldInfos fieldInfos) {
        // We consider constant field types to always have value.
        return true;
    }

    /**
     * Returns the constant value of this field as a string.
     * Based on the field type, we need to get it in a different way.
     */
    public abstract String getConstantFieldValue(SearchExecutionContext context);

    /**
     * Returns a query that matches all documents or no documents
     * depending on whether the constant value of this field matches or not
     */
    @Override
    public Query automatonQuery(
        Supplier<Automaton> automatonSupplier,
        Supplier<CharacterRunAutomaton> characterRunAutomatonSupplier,
        @Nullable MultiTermQuery.RewriteMethod method,
        SearchExecutionContext context,
        String description
    ) {
        CharacterRunAutomaton compiled = characterRunAutomatonSupplier.get();
        boolean matches = compiled.run(getConstantFieldValue(context));
        if (matches) {
            return new MatchAllDocsQuery();
        } else {
            return new MatchNoDocsQuery(
                "The \"" + context.getFullyQualifiedIndex().getName() + "\" query was rewritten to a \"match_none\" query."
            );
        }
    }
}
