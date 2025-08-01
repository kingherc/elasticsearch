/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.vector;

import org.apache.lucene.util.VectorUtil;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.function.scalar.BinaryScalarFunction;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.expression.function.Example;
import org.elasticsearch.xpack.esql.expression.function.FunctionAppliesTo;
import org.elasticsearch.xpack.esql.expression.function.FunctionAppliesToLifecycle;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.Param;

import java.io.IOException;

public class L2Norm extends VectorSimilarityFunction {

    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(Expression.class, "L2Norm", L2Norm::new);
    static final SimilarityEvaluatorFunction SIMILARITY_FUNCTION = L2Norm::calculateSimilarity;

    @FunctionInfo(
        returnType = "double",
        preview = true,
        description = "Calculates the l2 norm between two dense_vectors.",
        examples = { @Example(file = "vector-l2-norm", tag = "vector-l2-norm") },
        appliesTo = { @FunctionAppliesTo(lifeCycle = FunctionAppliesToLifecycle.DEVELOPMENT) }
    )
    public L2Norm(
        Source source,
        @Param(
            name = "left",
            type = { "dense_vector" },
            description = "first dense_vector to calculate l2 norm similarity"
        ) Expression left,
        @Param(
            name = "right",
            type = { "dense_vector" },
            description = "second dense_vector to calculate l2 norm similarity"
        ) Expression right
    ) {
        super(source, left, right);
    }

    private L2Norm(StreamInput in) throws IOException {
        super(in);
    }

    @Override
    protected BinaryScalarFunction replaceChildren(Expression newLeft, Expression newRight) {
        return new L2Norm(source(), newLeft, newRight);
    }

    @Override
    protected SimilarityEvaluatorFunction getSimilarityFunction() {
        return SIMILARITY_FUNCTION;
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, L2Norm::new, left(), right());
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    public static float calculateSimilarity(float[] leftScratch, float[] rightScratch) {
        return (float) Math.sqrt(VectorUtil.squareDistance(leftScratch, rightScratch));
    }

}
