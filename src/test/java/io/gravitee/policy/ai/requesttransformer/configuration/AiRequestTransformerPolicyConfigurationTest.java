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
package io.gravitee.policy.ai.requesttransformer.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiRequestTransformerPolicyConfigurationTest {

  @Test
  void shouldExposeExpectedDefaults() {
    AiRequestTransformerPolicyConfiguration configuration =
      new AiRequestTransformerPolicyConfiguration();

    assertThat(configuration.getErrorMode()).isEqualTo(ErrorMode.FAIL_OPEN);
    assertThat(configuration.getMaxRequestBodySize()).isEqualTo(1024 * 1024);
    assertThat(configuration.getMaxLlmResponseBodySize()).isEqualTo(
      1024 * 1024
    );
    assertThat(configuration.getLlmTimeoutMs()).isEqualTo(30000);
    assertThat(configuration.getLlmSourceMode()).isNull();
    assertThat(configuration.getLlm()).isNotNull();
    assertThat(configuration.getLlm().getAuthType()).isEqualTo(
      AiRequestTransformerPolicyConfiguration.AuthType.NONE
    );
    assertThat(configuration.getLlm().getAuthHeader()).isEqualTo(
      "Authorization"
    );
  }

  @Test
  void shouldStoreDirectLlmConfiguration() {
    AiRequestTransformerPolicyConfiguration configuration =
      new AiRequestTransformerPolicyConfiguration();

    configuration.setPrompt("rewrite");
    configuration.setLlmProxyApiId("api-1");
    configuration.setLlmModel("gpt-4o-mini");

    AiRequestTransformerPolicyConfiguration.Llm llm =
      new AiRequestTransformerPolicyConfiguration.Llm();
    llm.setEndpoint("https://llm.example.com/v1");
    llm.setModel("fallback-model");
    llm.setAuthType(AiRequestTransformerPolicyConfiguration.AuthType.BEARER);
    llm.setAuthValue("token");

    configuration.setLlm(llm);

    assertThat(configuration.getPrompt()).isEqualTo("rewrite");
    assertThat(configuration.getLlmProxyApiId()).isEqualTo("api-1");
    assertThat(configuration.getLlmModel()).isEqualTo("gpt-4o-mini");
    assertThat(configuration.getLlm().getEndpoint()).isEqualTo(
      "https://llm.example.com/v1"
    );
    assertThat(configuration.getLlm().getModel()).isEqualTo("fallback-model");
    assertThat(configuration.getLlm().getAuthType()).isEqualTo(
      AiRequestTransformerPolicyConfiguration.AuthType.BEARER
    );
    assertThat(configuration.getLlm().getAuthValue()).isEqualTo("token");
  }
}
