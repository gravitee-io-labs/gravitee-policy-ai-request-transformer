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
package io.gravitee.policy.ai.requesttransformer.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.policy.ai.requesttransformer.configuration.AiRequestTransformerPolicyConfiguration;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EndpointGroupResolverTest {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  void shouldResolveInlineHeaderAuthConfiguration() {
    EndpointGroupResolver resolver = new EndpointGroupResolver();
    AiRequestTransformerPolicyConfiguration configuration =
      new AiRequestTransformerPolicyConfiguration();
    configuration.setLlmSourceMode(
      AiRequestTransformerPolicyConfiguration.LlmSourceMode.INLINE
    );
    configuration.setLlmModel("model-override");

    AiRequestTransformerPolicyConfiguration.Llm llm =
      new AiRequestTransformerPolicyConfiguration.Llm();
    llm.setEndpoint("https://llm.example.com/v1");
    llm.setAuthType(AiRequestTransformerPolicyConfiguration.AuthType.HEADER);
    llm.setAuthHeader("X-API-Key");
    llm.setAuthValue("secret");
    llm.setModel("fallback-model");
    configuration.setLlm(llm);

    ResolvedEndpoint endpoint = resolver.resolve(
      Mockito.mock(HttpPlainExecutionContext.class),
      configuration
    );

    assertThat(endpoint).isNotNull();
    assertThat(endpoint.target()).isEqualTo("https://llm.example.com/v1");
    assertThat(endpoint.authHeader()).isEqualTo("X-API-Key");
    assertThat(endpoint.authValue()).isEqualTo("secret");
    assertThat(endpoint.model()).isEqualTo("model-override");
  }

  @Test
  void shouldNotFallbackToInlineWhenModeIsExplicitProxyAndProxyIdMissing() {
    EndpointGroupResolver resolver = new EndpointGroupResolver();
    AiRequestTransformerPolicyConfiguration configuration =
      new AiRequestTransformerPolicyConfiguration();
    configuration.setLlmSourceMode(
      AiRequestTransformerPolicyConfiguration.LlmSourceMode.LLM_PROXY_API
    );

    AiRequestTransformerPolicyConfiguration.Llm llm =
      new AiRequestTransformerPolicyConfiguration.Llm();
    llm.setEndpoint("https://llm.example.com/v1");
    configuration.setLlm(llm);

    ResolvedEndpoint endpoint = resolver.resolve(
      Mockito.mock(HttpPlainExecutionContext.class),
      configuration
    );

    assertThat(endpoint).isNull();
  }

  @Test
  void shouldResolveApiKeyAliasesFromConfigurationNode() throws Exception {
    EndpointGroupResolver resolver = new EndpointGroupResolver();
    Method method = EndpointGroupResolver.class.getDeclaredMethod(
      "resolveFromConfigurationNode",
      com.fasterxml.jackson.databind.JsonNode.class,
      String.class
    );
    method.setAccessible(true);

    var configNode = OBJECT_MAPPER.readTree(
      """
      {
        "target": "https://proxy.example.com/openai/v1",
        "authentication": {
          "type": "API_KEY",
          "headerName": "X-Api-Key",
          "apiKey": "abc123"
        },
        "model": "proxy-model"
      }
      """
    );

    ResolvedEndpoint endpoint = (ResolvedEndpoint) method.invoke(
      resolver,
      configNode,
      null
    );

    assertThat(endpoint).isNotNull();
    assertThat(endpoint.target()).isEqualTo(
      "https://proxy.example.com/openai/v1"
    );
    assertThat(endpoint.authHeader()).isEqualTo("X-Api-Key");
    assertThat(endpoint.authValue()).isEqualTo("abc123");
    assertThat(endpoint.model()).isEqualTo("proxy-model");
  }
}
