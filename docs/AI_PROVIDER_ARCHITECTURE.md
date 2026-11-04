# COBUG AI Provider Architecture

COBUG now uses a provider abstraction so the application does not depend on one AI vendor.

## Development

Default provider is local Ollama:

```properties
cobug.ai.provider=ollama
cobug.ollama.url=http://localhost:11434
cobug.ollama.model=qwen2.5-coder:3b
```

Install Ollama separately on the development machine and pull the model:

```powershell
ollama pull qwen2.5-coder:3b
ollama run qwen2.5-coder:3b
```

COBUG talks to Ollama over its local HTTP API. The model files are not stored inside the COBUG repository.

## Production

Use Gemini or another provider without changing chat/business logic:

```properties
cobug.ai.provider=gemini
cobug.gemini.api-key=${GEMINI_API_KEY}
cobug.gemini.model=${GEMINI_MODEL:gemini-3.8-flash}
```

The provider router selects the active implementation. `ProjectChatService` and the REST AI controller depend only on `AiProvider`.

## Classes

- `AiProvider` — provider contract.
- `OllamaAiService` — local provider.
- `GeminiAiService` — cloud provider.
- `AiProviderRouter` — selects provider from `COBUG_AI_PROVIDER`.

## Important

The local model is intentionally outside the project folder. This keeps COBUG source/ZIPs small and lets production choose a hosted provider.
