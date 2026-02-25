# Changelog

## 1.0.0-SNAPSHOT

- Initial implementation of `ai-request-transformer` for APIM 4.10.x.
- Request-phase payload transformation through OpenAI-compatible `/chat/completions`.
- Explicit LLM source mode support:
  - `LLM_PROXY_API` (selected `llmProxyApiId`)
  - `INLINE` (direct `llm.*` endpoint configuration)
- Resolver support for real-world LLM proxy auth shape variants:
  - `header` / `headerName`
  - `value` / `apiKey`
  - bearer aliases (`token`, `bearer`, `value`)
- Configurable safety controls:
  - `maxRequestBodySize`
  - `maxLlmResponseBodySize`
  - `llmTimeoutMs`
  - `errorMode` (`FAIL_OPEN` / `FAIL_CLOSED`)
- EL/template rendering support for `prompt`.
- Runtime metrics:
  - `long_ai-request-transformer_transformed-count`
  - `long_ai-request-transformer_processing-time-ms`
