Transforms incoming request payloads by calling an LLM and replacing the request body with the transformed result.

Choose LLM endpoint routing with `llmSourceMode`:
- `LLM_PROXY_API`: reuse an existing Gravitee LLM Proxy API (`llmProxyApiId` required, optional `llmModel` override).
- `INLINE`: call a direct endpoint from policy config (`llm.endpoint` required, optional `llm.model` + auth settings).

Request sent to LLM:
- `system`: configured `prompt` (EL/template rendering supported)
- `user`: full original request body

If transformation fails, behavior follows `errorMode`:
- **FAIL_OPEN**: pass-through original request
- **FAIL_CLOSED**: interrupt with HTTP `400`

Reported metrics:
- `long_ai-request-transformer_transformed-count`
- `long_ai-request-transformer_processing-time-ms`
