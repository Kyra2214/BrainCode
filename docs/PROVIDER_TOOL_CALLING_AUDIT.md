# Auditoria de tool-calling por provider/modelo

**Fase:** 2.1 — auditoria de compatibilidade de providers/modelos  
**Data da auditoria:** 2026-10-04  
**Escopo:** entradas presentes em `app/src/main/assets/ai_api_catalog.json`

## Decisão operacional

A documentação oficial confirma o formato de `tools`, `tool_choice` e `tool_calls` em vários endpoints, mas os aliases do catálogo são dinâmicos (`*-bootstrap`, `openrouter-free`) e não identificam um modelo concreto verificável. Portanto:

- `request` e `response` registram a capacidade documentada do endpoint;
- `modelVerified=false` registra que o modelo concreto ainda não foi sondado com credencial autorizada;
- `ProviderDispatcher` **não envia `tools`** quando `modelVerified=false`;
- argumentos de tool-call são dados não confiáveis e precisam de validação local antes de qualquer executor;
- rejeição HTTP 4xx relacionada a tools permite no máximo uma nova tentativa textual sem `tools`, sem interpretar texto como ação;
- 401, 403, 429, timeout e 5xx não são mascarados pelo fallback textual.

## Matriz

| Provider | Entrada do catálogo | Endpoint documenta request | Endpoint documenta response | Modelo concreto verificado | Argumentos | Fallback |
|---|---|---:|---:|---:|---|---|
| Qwen | `qwen-bootstrap` | confirmado | confirmado | não | validar no cliente | texto seguro específico |
| Moonshot/Kimi | `moonshot-bootstrap` | confirmado | confirmado | não | validar no cliente | texto seguro específico |
| Volcengine/Doubao | `volcengine-bootstrap` | confirmado | confirmado | não | validar no cliente | texto seguro específico |
| SiliconFlow | `siliconflow-bootstrap` | confirmado | confirmado | não | validar no cliente | texto seguro específico |
| ModelScope | `modelscope-bootstrap` | parcial | parcial | não | validar no cliente | texto seguro específico |
| OpenRouter | `openrouter-free` | confirmado | confirmado | não | validar no cliente | texto seguro específico |
| Groq | `groq-bootstrap` | confirmado | confirmado | não | validar no cliente | texto seguro específico |
| Google Gemini | `gemini-bootstrap` | confirmado | parcial | não | validar no cliente | texto seguro específico |
| Mistral | `mistral-bootstrap` | confirmado | confirmado | não | validar no cliente | texto seguro específico |
| Z.ai/GLM | `glm-4.7-flash`, `glm-4.5-flash` | parcial | parcial | não | validar no cliente | texto seguro específico |

A matriz completa, com URLs por modelo e instruções de fallback, está no próprio catálogo JSON. As classificações “confirmado” acima são de endpoint/documentação; não significam que o alias dinâmico seja compatível.

## Evidência e limites

A auditoria usou documentação oficial atual de cada provider. Não foram feitas chamadas autenticadas porque não há credenciais de provider autorizadas no ambiente. Consequentemente, esta fase **não declara compatibilidade de modelo concreto**. A próxima ação específica, ainda dentro desta fase se houver credenciais autorizadas, é resolver cada alias para um `modelId` concreto e executar uma sonda não destrutiva com:

1. resposta textual normal;
2. uma tool-call;
3. múltiplas tool-calls;
4. argumentos inválidos;
5. HTTP 400/erro de provider;
6. rejeição explícita de `tools`.

Cada sonda deve registrar status HTTP, corpo sanitizado, formato de resposta, resultado da validação local e `modelId` concreto. Nenhum argumento da resposta deve ser executado durante a sonda.
