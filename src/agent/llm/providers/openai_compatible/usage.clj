(ns agent.llm.providers.openai-compatible.usage
  "Usage normalization for OpenAI-compatible providers.")

(defn cached-tokens [usage]
  (or (get-in usage [:prompt_tokens_details :cached_tokens])
      (get-in usage [:input_tokens_details :cached_tokens])
      (get-in usage [:cache_tokens_details :cached_tokens])
      (:cached_tokens usage)
      (:cache_read_input_tokens usage)
      (:prompt_cache_read_tokens usage)
      (:prompt_cache_hit_tokens usage)
      0))

(defn chat->estimate [response]
  (let [usage (:usage response)]
    {:tokens (or (:total_tokens usage) 0)
     :prompt-tokens (or (:prompt_tokens usage) 0)
     :completion-tokens (or (:completion_tokens usage) 0)
     :cached-tokens (cached-tokens usage)
     :cost-usd nil}))

(defn responses->estimate [response]
  (let [usage (:usage response)]
    {:tokens (or (:total_tokens usage) 0)
     :prompt-tokens (or (:input_tokens usage) 0)
     :completion-tokens (or (:output_tokens usage) 0)
     :cached-tokens (cached-tokens usage)
     :cost-usd nil}))
