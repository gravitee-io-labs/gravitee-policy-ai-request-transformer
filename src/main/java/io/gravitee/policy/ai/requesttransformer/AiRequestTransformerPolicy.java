/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.ai.requesttransformer;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.ExecutionWarn;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.policy.ai.requesttransformer.configuration.AiRequestTransformerPolicyConfiguration;
import io.gravitee.policy.ai.requesttransformer.configuration.ErrorMode;
import io.gravitee.policy.ai.requesttransformer.llm.EndpointGroupResolver;
import io.gravitee.policy.ai.requesttransformer.llm.ResolvedEndpoint;
import io.gravitee.policy.ai.requesttransformer.llm.TransformerLlmClient;
import io.gravitee.policy.api.annotations.OnRequest;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiRequestTransformerPolicy implements HttpPolicy {

  static final String METRIC_TRANSFORMED_COUNT =
    "long_ai-request-transformer_transformed-count";
  static final String METRIC_TRANSFORM_TIME_MS =
    "long_ai-request-transformer_processing-time-ms";

  private static final String WARN_KEY_FAIL_OPEN =
    "AI_REQUEST_TRANSFORMER_FAIL_OPEN";
  private static final String FAILURE_KEY =
    "AI_REQUEST_TRANSFORMER_BAD_REQUEST";
  private static final String TEMPLATE_MARKER_OPEN = "{#";
  private static final String TEMPLATE_MARKER_OPEN_ALT = "${";

  private static final Logger LOGGER = LoggerFactory.getLogger(
    AiRequestTransformerPolicy.class
  );

  private final AiRequestTransformerPolicyConfiguration configuration;
  private final EndpointGroupResolver endpointResolver;
  private final TransformerLlmClient llmClient;

  public AiRequestTransformerPolicy(
    AiRequestTransformerPolicyConfiguration configuration
  ) {
    this(
      configuration,
      new EndpointGroupResolver(),
      new TransformerLlmClient()
    );
  }

  AiRequestTransformerPolicy(
    AiRequestTransformerPolicyConfiguration configuration,
    EndpointGroupResolver endpointResolver,
    TransformerLlmClient llmClient
  ) {
    this.configuration = configuration == null
      ? new AiRequestTransformerPolicyConfiguration()
      : configuration;
    this.endpointResolver = endpointResolver;
    this.llmClient = llmClient;
  }

  @Override
  public String id() {
    return "ai-request-transformer";
  }

  @OnRequest
  @Override
  public Completable onRequest(HttpPlainExecutionContext ctx) {
    return ctx
      .request()
      .onBody(onBody ->
        onBody
          .switchIfEmpty(Maybe.just(Buffer.buffer()))
          .flatMap(body -> Maybe.fromCallable(() -> transformBody(ctx, body)))
      )
      .onErrorResumeNext(throwable -> {
        if (throwable instanceof TransformationFailureException e) {
          return ctx.interruptWith(
            new ExecutionFailure(HttpStatusCode.BAD_REQUEST_400)
              .key(FAILURE_KEY)
              .message(e.getMessage())
          );
        }

        return Completable.error(throwable);
      });
  }

  private Buffer transformBody(
    HttpPlainExecutionContext ctx,
    Buffer originalBody
  ) throws Exception {
    long startedAt = System.nanoTime();
    boolean transformed = false;

    try {
      int maxBodySize = configuration.getMaxRequestBodySize();
      if (maxBodySize > 0 && originalBody.length() > maxBodySize) {
        handleUntransformable(
          ctx,
          "Request body size exceeds configured maxRequestBodySize."
        );
        return originalBody;
      }

      ResolvedEndpoint endpoint = endpointResolver.resolve(ctx, configuration);
      if (endpoint == null) {
        handleUntransformable(ctx, "No LLM endpoint could be resolved.");
        return originalBody;
      }

      String prompt = renderTemplate(ctx, configuration.getPrompt());
      String transformedBody;
      try {
        transformedBody = llmClient.transform(
          endpoint,
          prompt,
          originalBody.toString(),
          configuration.getLlmTimeoutMs()
        );
      } catch (Exception e) {
        handleUntransformable(
          ctx,
          "LLM call failed: " +
            (e.getMessage() == null
                ? e.getClass().getSimpleName()
                : e.getMessage())
        );
        return originalBody;
      }

      if (transformedBody == null || transformedBody.isBlank()) {
        handleUntransformable(ctx, "LLM returned an empty transformation.");
        return originalBody;
      }

      byte[] transformedBytes = transformedBody.getBytes(
        StandardCharsets.UTF_8
      );
      int maxLlmResponseBodySize = configuration.getMaxLlmResponseBodySize();
      if (
        maxLlmResponseBodySize > 0 &&
        transformedBytes.length > maxLlmResponseBodySize
      ) {
        handleUntransformable(
          ctx,
          "LLM response exceeds configured maxLlmResponseBodySize."
        );
        return originalBody;
      }

      Buffer transformedBuffer = Buffer.buffer(transformedBody);
      ctx.request().contentLength(transformedBuffer.length());

      transformed = true;
      return transformedBuffer;
    } finally {
      recordMetrics(ctx, startedAt, transformed);
    }
  }

  private void recordMetrics(
    HttpPlainExecutionContext ctx,
    long startedAtNanos,
    boolean transformed
  ) {
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
      System.nanoTime() - startedAtNanos
    );
    ctx
      .metrics()
      .putAdditionalMetric(METRIC_TRANSFORMED_COUNT, transformed ? 1L : 0L);
    ctx.metrics().putAdditionalMetric(METRIC_TRANSFORM_TIME_MS, elapsedMs);
  }

  private String renderTemplate(
    HttpPlainExecutionContext ctx,
    String rawTemplate
  ) {
    if (rawTemplate == null || !hasTemplateMarker(rawTemplate)) {
      return rawTemplate;
    }

    TemplateEngine templateEngine = ctx.getTemplateEngine();
    if (templateEngine == null) {
      throw new IllegalStateException("Template engine is not available.");
    }

    return templateEngine.convert(rawTemplate);
  }

  private boolean hasTemplateMarker(String value) {
    return (
      value != null &&
      (value.contains(TEMPLATE_MARKER_OPEN) ||
        value.contains(TEMPLATE_MARKER_OPEN_ALT))
    );
  }

  private void handleUntransformable(
    HttpPlainExecutionContext ctx,
    String message
  ) {
    if (resolveErrorMode() == ErrorMode.FAIL_CLOSED) {
      throw new TransformationFailureException(message);
    }

    LOGGER.warn(
      "Request transformation skipped in FAIL_OPEN mode: {}",
      message
    );
    ctx.warnWith(new ExecutionWarn(WARN_KEY_FAIL_OPEN).message(message));
  }

  private ErrorMode resolveErrorMode() {
    return configuration.getErrorMode() == null
      ? ErrorMode.FAIL_OPEN
      : configuration.getErrorMode();
  }

  private static final class TransformationFailureException
    extends RuntimeException {

    private TransformationFailureException(String message) {
      super(message);
    }
  }
}
