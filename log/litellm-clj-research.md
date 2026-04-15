# LiteLLM-clj Research
Date: 2026-04-15

## Overview
LiteLLM-clj is a Clojure port of the popular LiteLLM library, providing a unified interface for multiple LLM providers with comprehensive observability and thread pool management.

## Key Features

### Unified Interface
- Single API for multiple LLM providers
- Switch between providers without code changes
- Consistent patterns across different models

### Supported Providers
Based on original LiteLLM (Python), likely supports:
- OpenAI (GPT-3.5, GPT-4, etc.)
- Anthropic (Claude)
- Google Gemini
- Local models (Ollama, vLLM)
- Azure OpenAI
- AWS Bedrock
- Hugging Face

### Core Capabilities
1. **Streaming support**: With core.async channels
2. **Router API**: Switch between models at runtime
3. **Function calling**: Alpha support
4. **Observability**: Comprehensive monitoring
5. **Thread pool management**: Efficient resource usage

## Architecture Insights

### Integration with Clojure Ecosystem
- Uses core.async for streaming
- Idiomatic Clojure API design
- Likely built on HTTP clients (clj-http, http-kit)

### Potential for Agent Integration
1. **Model abstraction**: Easy to switch between local/cloud models
2. **Streaming**: Real-time agent responses
3. **Function calling**: Could support tool use in agents
4. **Router API**: Dynamic model selection based on task

## Custom Library Approaches

### Considerations for Agent Project
1. **Dependency vs custom**: LiteLLM-clj provides mature foundation
2. **Extensibility**: Need to support custom local models
3. **Integration patterns**: How to combine with core.async.flow
4. **Error handling**: Built-in vs custom retry logic

### Alternative Approaches
1. **Direct HTTP calls**: Simple but lacks abstraction
2. **Provider-specific libraries**: More control but less portable
3. **Custom wrapper**: Tailored to agent needs but more maintenance

## Recommendations
1. **Use LiteLLM-clj as base**: Leverage existing work
2. **Extend for local models**: Ensure Ollama/vLLM support
3. **Integrate with flow**: Create step-fns for LLM calls
4. **Add agent-specific features**: Context management, memory integration

## Next Steps
- Examine actual API usage examples
- Check for Ollama/vLLM integration
- Study error handling patterns
- Explore how to integrate with knowledge graphs

## References
- https://github.com/unravel-team/litellm-clj
- https://github.com/BerriAI/litellm (original Python library)