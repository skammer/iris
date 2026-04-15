# Additional LLM Providers Research
**Date:** 2026-04-15  
**Task:** Task 27 - Support more LLM providers (Phase 6)

## Overview
Current implementation only supports OpenAI. Need to add support for additional LLM providers to make the agent system more flexible and production-ready.

## Target LLM Providers

### 1. **Anthropic Claude**
- **API**: https://api.anthropic.com
- **Models**: Claude-3 series (Opus, Sonnet, Haiku)
- **Features**: Strong reasoning, large context windows
- **Pricing**: Token-based, competitive with OpenAI

### 2. **Google Gemini**
- **API**: https://generativelanguage.googleapis.com
- **Models**: Gemini Pro, Gemini Ultra
- **Features**: Multimodal, strong code generation
- **Pricing**: Free tier available, then token-based

### 3. **Local Models (Ollama)**
- **API**: http://localhost:11434
- **Models**: Llama 3, Mistral, Gemma, etc.
- **Features**: Privacy, no internet required, customizable
- **Pricing**: Free (self-hosted)

### 4. **OpenRouter**
- **API**: https://openrouter.ai/api/v1
- **Models**: Unified interface to 100+ models
- **Features**: Model routing, fallback, cost optimization
- **Pricing**: Unified billing across providers

### 5. **Azure OpenAI**
- **API**: Azure-specific endpoints
- **Models**: Same as OpenAI but on Azure infrastructure
- **Features**: Enterprise features, compliance, private networking
- **Pricing**: Azure billing

### 6. **Cohere**
- **API**: https://api.cohere.ai
- **Models**: Command, Embed
- **Features**: Strong RAG capabilities, multilingual
- **Pricing**: Token-based

## Implementation Strategy

### 1. **Protocol-Based Architecture**
Extend the existing `ILLMProvider` protocol to support:
- Provider-specific configuration
- Model listing and capabilities
- Cost tracking
- Fallback mechanisms

### 2. **Provider Registry**
Create a registry system for:
- Dynamic provider registration
- Configuration management
- Health checking
- Load balancing between providers

### 3. **Unified Interface**
Maintain consistent interface across providers:
- Message format standardization
- Error handling
- Streaming support
- Embedding generation

## Technical Requirements

### API Differences

#### OpenAI/Anthropic Style
```json
{
  "model": "gpt-4",
  "messages": [{"role": "user", "content": "Hello"}],
  "temperature": 0.7
}
```

#### Google Gemini Style
```json
{
  "contents": [{"parts": [{"text": "Hello"}]}],
  "generationConfig": {"temperature": 0.7}
}
```

#### Ollama Style
```json
{
  "model": "llama3",
  "prompt": "Hello",
  "options": {"temperature": 0.7}
}
```

## Implementation Plan

### Phase 1: Core Provider Interface
1. Extend `ILLMProvider` protocol with additional methods
2. Create provider configuration system
3. Implement provider factory pattern

### Phase 2: Individual Providers
1. Anthropic Claude provider
2. Google Gemini provider  
3. Ollama local provider
4. OpenRouter provider

### Phase 3: Advanced Features
1. Provider fallback and load balancing
2. Cost tracking and budgeting
3. Model capability discovery
4. Caching layer for embeddings

### Phase 4: Integration
1. Update agent core to use provider registry
2. Add configuration for provider selection
3. Create provider health monitoring
4. Add provider-specific testing

## Code Structure

```
src/agent/llm/
├── core.clj           # Base protocols and interfaces
├── providers/
│   ├── openai.clj     # Existing OpenAI provider
│   ├── anthropic.clj  # Anthropic provider
│   ├── gemini.clj     # Google Gemini provider
│   ├── ollama.clj     # Ollama provider
│   ├── openrouter.clj # OpenRouter provider
│   └── azure.clj      # Azure OpenAI provider
├── registry.clj       # Provider registry
├── config.clj         # Provider configuration
└── utils.clj          # Common utilities
```

## Configuration Example

```clojure
{:llm-providers
 {:openai {:api-key "sk-..."
           :base-url "https://api.openai.com/v1"
           :default-model "gpt-4"}
  :anthropic {:api-key "sk-ant-..."
              :base-url "https://api.anthropic.com"
              :default-model "claude-3-opus-20240229"}
  :gemini {:api-key "AIza..."
           :base-url "https://generativelanguage.googleapis.com/v1beta"
           :default-model "gemini-pro"}
  :ollama {:base-url "http://localhost:11434"
           :default-model "llama3"}}}
```

## Challenges and Solutions

### 1. **API Inconsistency**
- **Solution**: Create adapter layer that normalizes API calls
- **Approach**: Provider-specific request/response transformers

### 2. **Error Handling**
- **Solution**: Unified error types and retry logic
- **Approach**: Circuit breaker pattern for provider failures

### 3. **Cost Management**
- **Solution**: Token counting and cost tracking
- **Approach**: Provider-specific tokenizers and cost calculators

### 4. **Performance**
- **Solution**: Connection pooling and caching
- **Approach**: Async requests with timeouts

## Next Steps
1. Analyze existing `ILLMProvider` protocol
2. Design extended protocol with provider capabilities
3. Implement Anthropic provider as first additional provider
4. Create provider registry system
5. Update agent configuration to support multiple providers