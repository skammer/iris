# LLM Integration for Clojure Agents

## Overview
LLM integration is crucial for AI agents. Options include using existing libraries like LiteLLM-clj or creating custom solutions.

## LiteLLM-clj Analysis

### Features
- **Unified interface**: Single API for multiple LLM providers
- **Streaming support**: With core.async channels
- **Router API**: Switch between models at runtime
- **Function calling**: Alpha support for tool use
- **Observability**: Comprehensive monitoring
- **Thread pool management**: Efficient resource usage

### Supported Providers (Inferred)
- OpenAI (GPT-3.5, GPT-4, etc.)
- Anthropic (Claude)
- Google Gemini
- Local models (Ollama, vLLM)
- Azure OpenAI
- AWS Bedrock
- Hugging Face

### Architecture Insights
- Uses core.async for streaming
- Idiomatic Clojure API design
- Built on HTTP clients (clj-http, http-kit)
- Extensible provider system

## Custom Library Approaches

### Advantages of Custom Solution
1. **Tailored to agent needs**: Specific features for agent workflows
2. **Reduced dependencies**: Minimal external libraries
3. **Full control**: Custom error handling, retry logic, caching
4. **Integration optimization**: Tight coupling with agent architecture

### Key Components for Custom Library

#### 1. Provider Abstraction
```clojure
(defprotocol LLMProvider
  (complete [this prompt opts])
  (stream [this prompt opts])
  (embed [this text opts]))

(defrecord OpenAIProvider [api-key base-url]
  LLMProvider
  (complete [this prompt opts]
    (http/post (str base-url "/completions")
               {:headers {"Authorization" (str "Bearer " api-key)}
                :body (json/write-str {:prompt prompt :opts opts})}))
  
  (stream [this prompt opts]
    (let [ch (async/chan)]
      ; Implement streaming with SSE or similar
      ch))
  
  (embed [this text opts]
    ; Implementation for embeddings
    ))
```

#### 2. Unified Interface
```clojure
(defn complete
  "Unified completion interface"
  [provider prompt & {:as opts}]
  (let [defaults {:temperature 0.7
                  :max-tokens 1000
                  :stream? false}]
    (if (:stream? (merge defaults opts))
      (stream provider prompt opts)
      (complete provider prompt opts))))

(defn with-retry
  "Retry logic for LLM calls"
  [f & {:keys [retries delay-ms]
        :or {retries 3 delay-ms 1000}}]
  (loop [attempt 1]
    (let [result (try (f) (catch Exception e e))]
      (cond
        (not (instance? Exception result)) result
        (>= attempt retries) (throw result)
        :else (do
                (Thread/sleep (* attempt delay-ms))
                (recur (inc attempt)))))))
```

#### 3. Streaming with core.async
```clojure
(defn stream-completions
  "Stream LLM responses via core.async"
  [provider prompt opts]
  (let [ch (async/chan)]
    (async/go
      (try
        (let [stream (stream provider prompt opts)]
          (loop []
            (when-let [chunk (async/<! stream)]
              (async/>! ch chunk)
              (recur)))
          (async/close! ch))
        (catch Exception e
          (async/>! ch {:error e})
          (async/close! ch))))
    ch))
```

## Integration Patterns for Agents

### 1. Step Function Integration
```clojure
(defn llm-reasoning-step
  "Step function for LLM-based reasoning"
  ([]
   {:params {:model "LLM model configuration"}
    :ins {:context "Agent context"
          :question "Question to answer"}
    :outs {:reasoning "LLM reasoning"
           :answer "Final answer"}})
  
  ([{:keys [model]}]
   {:provider (create-provider model)})
  
  ([state {:keys [context question]}]
   (let [prompt (format-reasoning-prompt context question)
         response (complete (:provider state) prompt)
         {:keys [reasoning answer]} (parse-response response)]
     [state {:reasoning [reasoning]
             :answer [answer]}])))
```

### 2. Tool Use Integration
```clojure
(defn function-calling-step
  "Step function for LLM function calling"
  ([]
   {:params {:tools "Available tools"}
    :ins {:query "User query"}
    :outs {:tool-calls "Tool calls to execute"
           :response "Natural language response"}})
  
  ([{:keys [tools]}]
   {:tools tools})
  
  ([state {:keys [query]}]
   (let [tool-descriptions (describe-tools (:tools state))
         prompt (format-tool-prompt query tool-descriptions)
         response (complete (:provider state) prompt {:function-calling? true})
         {:keys [tool-calls response-text]} (parse-function-call response)]
     [state {:tool-calls [tool-calls]
             :response [response-text]}])))
```

### 3. Multi-Model Routing
```clojure
(defn router-step
  "Step function for model routing"
  ([]
   {:params {:routing-rules "Rules for model selection"}
    :ins {:task "Task description"
          :constraints "Resource constraints"}
    :outs {:selected-model "Chosen model"
           :rationale "Selection rationale"}})
  
  ([{:keys [routing-rules]}]
   {:rules routing-rules
    :providers {:fast (create-provider "gpt-3.5-turbo")
                :powerful (create-provider "gpt-4")
                :local (create-provider "ollama/llama2")}})
  
  ([state {:keys [task constraints]}]
   (let [{:keys [model rationale} (select-model (:rules state) task constraints)]
     [state {:selected-model [model]
             :rationale [rationale]}])))
```

## Best Practices

### 1. Error Handling
- **Retry logic**: Exponential backoff for rate limits
- **Fallback models**: Switch to alternative providers on failure
- **Circuit breakers**: Prevent cascading failures
- **Graceful degradation**: Continue with reduced functionality

### 2. Performance Optimization
- **Caching**: Cache frequent queries and responses
- **Batching**: Batch multiple requests when possible
- **Connection pooling**: Reuse HTTP connections
- **Async processing**: Non-blocking API calls

### 3. Monitoring and Observability
- **Latency tracking**: Monitor response times
- **Token usage**: Track input/output tokens
- **Error rates**: Monitor failure rates per provider
- **Cost tracking**: Estimate API costs

### 4. Security
- **API key management**: Secure storage and rotation
- **Input sanitization**: Prevent prompt injection
- **Output validation**: Verify LLM responses
- **Access control**: Restrict model access based on context

## Implementation Recommendations

### Phase 1: Basic Integration
1. Implement simple HTTP client for one provider
2. Add basic completion and streaming
3. Create error handling and retry logic

### Phase 2: Advanced Features
1. Add multiple provider support
2. Implement function calling
3. Create model routing system

### Phase 3: Production Ready
1. Add comprehensive monitoring
2. Implement caching and optimization
3. Create security features

### Phase 4: Agent Integration
1. Integrate with agent step functions
2. Add tool use capabilities
3. Create multi-model coordination

## Decision Points

### Use LiteLLM-clj vs Custom
- **LiteLLM-clj**: Faster start, community support, but less control
- **Custom**: More control, tailored to agent needs, but more maintenance

### Streaming Strategy
- **core.async channels**: Native Clojure approach
- **Server-Sent Events**: Standard web streaming
- **WebSockets**: Bidirectional streaming

### Model Management
- **Static configuration**: Pre-defined models
- **Dynamic discovery**: Runtime model detection
- **Hybrid approach**: Static base with dynamic extensions

## References
- [LiteLLM-clj GitHub](https://github.com/unravel-team/litellm-clj)
- [Original LiteLLM (Python)](https://github.com/BerriAI/litellm)
- LLM API documentation (OpenAI, Anthropic, etc.)
- Clojure HTTP client libraries