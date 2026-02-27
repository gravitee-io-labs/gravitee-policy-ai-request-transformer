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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.buffer.Buffer;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainRequest;
import io.gravitee.policy.ai.requesttransformer.configuration.AiRequestTransformerPolicyConfiguration;
import io.gravitee.policy.ai.requesttransformer.configuration.ErrorMode;
import io.gravitee.policy.ai.requesttransformer.llm.EndpointGroupResolver;
import io.gravitee.policy.ai.requesttransformer.llm.ResolvedEndpoint;
import io.gravitee.policy.ai.requesttransformer.llm.TransformerLlmClient;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.MaybeTransformer;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiRequestTransformerPolicyTest {

  @Mock
  private HttpPlainExecutionContext ctx;

  @Mock
  private HttpPlainRequest request;

  @Mock
  private Metrics metrics;

  @Mock
  private TemplateEngine templateEngine;

  @Mock
  private EndpointGroupResolver endpointResolver;

  @Mock
  private TransformerLlmClient llmClient;

  @BeforeEach
  void setUp() {
    lenient().when(ctx.request()).thenReturn(request);
    lenient().when(ctx.metrics()).thenReturn(metrics);
    lenient().when(ctx.getTemplateEngine()).thenReturn(templateEngine);
    lenient()
      .when(ctx.interruptWith(any(ExecutionFailure.class)))
      .thenReturn(Completable.complete());
  }

  @Test
  void shouldTransformRequestBody() throws Exception {
    AiRequestTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    configuration.setPrompt("rewrite this");

    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint(
        "https://llm.example.com",
        "Authorization",
        "Bearer x",
        "gpt-4o-mini"
      )
    );
    when(
      llmClient.transform(any(), eq("rewrite this"), eq("hello"), eq(30000))
    ).thenReturn("transformed");

    AiRequestTransformerPolicy policy = new AiRequestTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo("transformed");
    verify(request).contentLength("transformed".length());
    verify(metrics).putAdditionalMetric(
      AiRequestTransformerPolicy.METRIC_TRANSFORMED_COUNT,
      1L
    );
    verify(metrics).putAdditionalMetric(
      eq(AiRequestTransformerPolicy.METRIC_TRANSFORM_TIME_MS),
      anyLong()
    );
  }

  @Test
  void shouldRenderPromptTemplateBeforeCallingLlm() throws Exception {
    AiRequestTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    configuration.setPrompt("Hello {#request.id}");

    when(templateEngine.convert("Hello {#request.id}")).thenReturn(
      "Hello req-1"
    );
    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(
      llmClient.transform(any(), eq("Hello req-1"), eq("hello"), eq(30000))
    ).thenReturn("ok");

    AiRequestTransformerPolicy policy = new AiRequestTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo("ok");
    verify(templateEngine).convert("Hello {#request.id}");
  }

  @Test
  void shouldFallbackToOriginalBodyInFailOpenWhenTargetedOutputIsInvalidJson()
    throws Exception {
    AiRequestTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    configuration.setJsonTargetingEnabled(true);
    configuration.setTargetPath("$.message");

    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(
      llmClient.transform(any(), eq("rewrite this"), eq("hello"), eq(30000))
    ).thenReturn("plain text");

    AiRequestTransformerPolicy policy = new AiRequestTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "{\"message\":\"hello\"}");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo(
      "{\"message\":\"hello\"}"
    );
  }

  @Test
  void shouldFallbackToOriginalBodyInFailOpenWhenTargetPathIsInvalid()
    throws Exception {
    AiRequestTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    configuration.setJsonTargetingEnabled(true);
    configuration.setTargetPath("message");

    AiRequestTransformerPolicy policy = new AiRequestTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "{\"message\":\"hello\"}");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo(
      "{\"message\":\"hello\"}"
    );
    verify(llmClient, never()).transform(any(), any(), any(), anyInt());
  }

  @Test
  void shouldFallbackToOriginalBodyInFailOpenWhenLlmCallFails()
    throws Exception {
    AiRequestTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_OPEN
    );
    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(llmClient.transform(any(), any(), any(), anyInt())).thenThrow(
      new IllegalStateException("status 401")
    );

    AiRequestTransformerPolicy policy = new AiRequestTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    assertThat(result.transformedBody.toString()).isEqualTo("hello");
    verify(ctx, never()).interruptWith(any(ExecutionFailure.class));
    verify(request, never()).contentLength(anyLong());
    verify(metrics).putAdditionalMetric(
      AiRequestTransformerPolicy.METRIC_TRANSFORMED_COUNT,
      0L
    );
  }

  @Test
  void shouldInterruptInFailClosedWhenLlmCallFails() throws Exception {
    AiRequestTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_CLOSED
    );
    when(endpointResolver.resolve(any(), any())).thenReturn(
      new ResolvedEndpoint("https://llm.example.com", null, null, "gpt")
    );
    when(llmClient.transform(any(), any(), any(), anyInt())).thenThrow(
      new IllegalStateException("status 401")
    );

    AiRequestTransformerPolicy policy = new AiRequestTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    ArgumentCaptor<ExecutionFailure> captor = ArgumentCaptor.forClass(
      ExecutionFailure.class
    );
    verify(ctx).interruptWith(captor.capture());
    assertThat(captor.getValue().statusCode()).isEqualTo(
      HttpStatusCode.BAD_REQUEST_400
    );
    assertThat(captor.getValue().message()).contains("LLM call failed");
    verify(request, never()).contentLength(anyLong());
    verify(metrics).putAdditionalMetric(
      AiRequestTransformerPolicy.METRIC_TRANSFORMED_COUNT,
      0L
    );
  }

  @Test
  void shouldInterruptInFailClosedWhenEndpointCannotBeResolved() {
    AiRequestTransformerPolicyConfiguration configuration = baseConfiguration(
      ErrorMode.FAIL_CLOSED
    );
    when(endpointResolver.resolve(any(), any())).thenReturn(null);

    AiRequestTransformerPolicy policy = new AiRequestTransformerPolicy(
      configuration,
      endpointResolver,
      llmClient
    );

    PolicyResult result = execute(policy, "hello");

    result.observer.assertComplete().assertNoErrors();
    ArgumentCaptor<ExecutionFailure> captor = ArgumentCaptor.forClass(
      ExecutionFailure.class
    );
    verify(ctx).interruptWith(captor.capture());
    assertThat(captor.getValue().statusCode()).isEqualTo(
      HttpStatusCode.BAD_REQUEST_400
    );
    assertThat(captor.getValue().message()).contains("No LLM endpoint");
    verify(request, never()).contentLength(anyLong());
    verify(metrics).putAdditionalMetric(
      AiRequestTransformerPolicy.METRIC_TRANSFORMED_COUNT,
      0L
    );
  }

  private PolicyResult execute(AiRequestTransformerPolicy policy, String body) {
    AtomicReference<Buffer> transformedBodyRef = new AtomicReference<>();
    lenient()
      .when(request.onBody(any()))
      .thenAnswer(invocation -> {
        @SuppressWarnings("unchecked")
        MaybeTransformer<Buffer, Buffer> transformer = invocation.getArgument(
          0
        );

        return Maybe.wrap(transformer.apply(Maybe.just(Buffer.buffer(body))))
          .doOnSuccess(transformedBodyRef::set)
          .ignoreElement();
      });

    TestObserver<Void> observer = policy.onRequest(ctx).test();
    observer.awaitDone(5, TimeUnit.SECONDS);

    if (transformedBodyRef.get() == null) {
      transformedBodyRef.set(Buffer.buffer(body));
    }

    return new PolicyResult(observer, transformedBodyRef.get());
  }

  private AiRequestTransformerPolicyConfiguration baseConfiguration(
    ErrorMode errorMode
  ) {
    AiRequestTransformerPolicyConfiguration configuration =
      new AiRequestTransformerPolicyConfiguration();
    configuration.setErrorMode(errorMode);
    configuration.setPrompt("rewrite this");
    configuration.setLlmTimeoutMs(30000);
    configuration.setMaxRequestBodySize(1024 * 1024);
    configuration.setMaxLlmResponseBodySize(1024 * 1024);
    return configuration;
  }

  private record PolicyResult(
    TestObserver<Void> observer,
    Buffer transformedBody
  ) {}
}
