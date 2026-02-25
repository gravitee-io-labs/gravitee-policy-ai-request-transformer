## Configure request transformation

The schema in `src/main/resources/schemas/schema-form.json` defines all constraints and defaults.

Required:
- `prompt`
- `llmSourceMode`

LLM source modes:
- `LLM_PROXY_API`:
  - requires `llmProxyApiId`
  - optional `llmModel` override
- `INLINE`:
  - requires `llm.endpoint`
  - optional `llm.model` and auth settings

Safety controls:
- `maxRequestBodySize` (`0` = unlimited)
- `maxLlmResponseBodySize` (`0` = unlimited)
- `llmTimeoutMs`
- `errorMode` (`FAIL_OPEN` or `FAIL_CLOSED`)

## Template rendering

`prompt` supports Gravitee EL/template expressions.

Examples:
- `{#request.headers['X-Tenant']}`
- `${request.headers['X-Tenant']}`

Example prompt:

```text
Rewrite in Spanish for tenant {#request.headers['X-Tenant']}.
```

## Data sent to the LLM

By default, the policy sends:
- `system`: rendered `prompt`
- `user`: full request body

Request headers/query params are not automatically copied into the `user` message; include only what you need through `prompt` templates.
