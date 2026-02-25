## Overview

`ai-request-transformer` calls an LLM to transform the incoming request body, then replaces the request body with the LLM output.

## Choosing the LLM source mode

Set `llmSourceMode` explicitly depending on how you want to route LLM calls:

1. `LLM_PROXY_API` (reuse an existing Gravitee LLM Proxy API)
   - Use when your team already manages provider connectivity/models via Gravitee LLM Proxy.
   - Required: `llmProxyApiId`
   - Optional: `llmModel` (model override)

2. `INLINE` (call a provider endpoint directly from this policy)
   - Use for standalone configuration or quick isolated setup.
   - Required: `llm.endpoint`
   - Optional: `llm.model`, `authType`, `authHeader`, `authValue`

## Configuration

### prompt
System instruction sent to the LLM. Supports EL/template rendering.

Example:

```text
Rewrite the request body in Spanish for tenant {#request.headers['X-Tenant']}.
```

### maxRequestBodySize
Maximum original request body size eligible for transformation (`0` = unlimited).

### maxLlmResponseBodySize
Maximum transformed payload accepted from the LLM (`0` = unlimited).

### llmTimeoutMs
HTTP timeout used for the LLM call.

### errorMode
- `FAIL_OPEN`: pass through the original request when transformation cannot be applied.
- `FAIL_CLOSED`: interrupt request flow with HTTP `400`.

## Runtime behavior

- The **full request body** is sent to the LLM as the `user` message.
- The policy does **not** automatically forward all request headers/query params/metadata as `user` content.
- You can still include selected context values explicitly via EL in `prompt`.
- LLM output replaces the request payload.
