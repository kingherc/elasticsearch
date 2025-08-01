/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.inference.rerank;

import org.elasticsearch.compute.data.DoubleBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.core.inference.action.InferenceAction;
import org.elasticsearch.xpack.core.inference.results.ChatCompletionResults;
import org.elasticsearch.xpack.core.inference.results.RankedDocsResults;
import org.elasticsearch.xpack.esql.inference.InferenceOperator;

import java.util.Comparator;
import java.util.Iterator;
import java.util.stream.IntStream;

/**
 * Builds the output page for the {@link RerankOperator} by adding
 *  * reranked relevance scores into the specified score channel of the input page.
 */

public class RerankOperatorOutputBuilder implements InferenceOperator.OutputBuilder {

    private final Page inputPage;
    private final DoubleBlock.Builder scoreBlockBuilder;
    private final int scoreChannel;

    public RerankOperatorOutputBuilder(DoubleBlock.Builder scoreBlockBuilder, Page inputPage, int scoreChannel) {
        this.inputPage = inputPage;
        this.scoreBlockBuilder = scoreBlockBuilder;
        this.scoreChannel = scoreChannel;
    }

    @Override
    public void close() {
        Releasables.close(scoreBlockBuilder);
    }

    /**
     * Constructs a new output {@link Page} which contains all original blocks from the input page, with the reranked scores
     * inserted at {@code scoreChannel}.
     */
    @Override
    public Page buildOutput() {
        Page outputPage = inputPage.appendBlock(scoreBlockBuilder.build());

        if (scoreChannel == inputPage.getBlockCount()) {
            // Just need to append the block at the end
            // We can just return the output page we have just created
            return outputPage;
        }

        try {
            // We need to project the last column to the score channel.
            int[] blockNapping = IntStream.range(0, inputPage.getBlockCount())
                .map(channel -> channel == scoreChannel ? inputPage.getBlockCount() : channel)
                .toArray();

            return outputPage.projectBlocks(blockNapping);
        } finally {
            // Releasing the output page since projection is incrementing block references.
            releasePageOnAnyThread(outputPage);
        }
    }

    /**
     * Extracts the ranked document results from the inference response and appends their relevance scores to the score block builder.
     * <p>
     * If the response is not of type {@link ChatCompletionResults} an {@link IllegalStateException} is thrown.
     * </p>
     * <p>
     * The responses must be added in the same order as the corresponding inference requests were generated.
     * Failing to preserve order may lead to incorrect or misaligned output rows.
     * </p>
     */
    @Override
    public void addInferenceResponse(InferenceAction.Response inferenceResponse) {
        Iterator<RankedDocsResults.RankedDoc> sortedRankedDocIterator = inferenceResults(inferenceResponse).getRankedDocs()
            .stream()
            .sorted(Comparator.comparingInt(RankedDocsResults.RankedDoc::index))
            .iterator();
        while (sortedRankedDocIterator.hasNext()) {
            scoreBlockBuilder.appendDouble(sortedRankedDocIterator.next().relevanceScore());
        }
    }

    private RankedDocsResults inferenceResults(InferenceAction.Response inferenceResponse) {
        return InferenceOperator.OutputBuilder.inferenceResults(inferenceResponse, RankedDocsResults.class);
    }
}
