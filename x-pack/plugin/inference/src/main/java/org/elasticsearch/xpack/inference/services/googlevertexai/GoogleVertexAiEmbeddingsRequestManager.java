/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.googlevertexai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InputType;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.inference.common.Truncator;
import org.elasticsearch.xpack.inference.external.http.retry.RequestSender;
import org.elasticsearch.xpack.inference.external.http.retry.ResponseHandler;
import org.elasticsearch.xpack.inference.external.http.sender.EmbeddingsInput;
import org.elasticsearch.xpack.inference.external.http.sender.ExecutableInferenceRequest;
import org.elasticsearch.xpack.inference.external.http.sender.InferenceInputs;
import org.elasticsearch.xpack.inference.services.googlevertexai.embeddings.GoogleVertexAiEmbeddingsModel;
import org.elasticsearch.xpack.inference.services.googlevertexai.request.GoogleVertexAiEmbeddingsRequest;
import org.elasticsearch.xpack.inference.services.googlevertexai.response.GoogleVertexAiEmbeddingsResponseEntity;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.inference.common.Truncator.truncate;

public class GoogleVertexAiEmbeddingsRequestManager extends GoogleVertexAiRequestManager {

    private static final Logger logger = LogManager.getLogger(GoogleVertexAiEmbeddingsRequestManager.class);

    private static final ResponseHandler HANDLER = createEmbeddingsHandler();

    private static ResponseHandler createEmbeddingsHandler() {
        return new GoogleVertexAiResponseHandler("google vertex ai embeddings", GoogleVertexAiEmbeddingsResponseEntity::fromResponse);
    }

    private final GoogleVertexAiEmbeddingsModel model;

    private final Truncator truncator;

    public GoogleVertexAiEmbeddingsRequestManager(GoogleVertexAiEmbeddingsModel model, Truncator truncator, ThreadPool threadPool) {
        super(threadPool, model, RateLimitGrouping.of(model));
        this.model = Objects.requireNonNull(model);
        this.truncator = Objects.requireNonNull(truncator);
    }

    record RateLimitGrouping(int projectIdHash) {
        public static RateLimitGrouping of(GoogleVertexAiEmbeddingsModel model) {
            Objects.requireNonNull(model);

            return new RateLimitGrouping(model.rateLimitServiceSettings().projectId().hashCode());
        }
    }

    @Override
    public void execute(
        InferenceInputs inferenceInputs,
        RequestSender requestSender,
        Supplier<Boolean> hasRequestCompletedFunction,
        ActionListener<InferenceServiceResults> listener
    ) {
        EmbeddingsInput input = EmbeddingsInput.of(inferenceInputs);
        List<String> docsInput = input.getStringInputs();
        InputType inputType = input.getInputType();

        var truncatedInput = truncate(docsInput, model.getServiceSettings().maxInputTokens());
        var request = new GoogleVertexAiEmbeddingsRequest(truncator, truncatedInput, inputType, model);

        execute(new ExecutableInferenceRequest(requestSender, logger, request, HANDLER, hasRequestCompletedFunction, listener));
    }
}
