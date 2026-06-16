(ns hyperphor.ellellem.core
  (:require [hyperphor.ellellem.providers.openai :as openai]
            [hyperphor.ellellem.providers.anthropic :as anthropic]
            [hyperphor.ellellem.chat :as chat]
            [hyperphor.ellellem.tools :as tools]
            [hyperphor.ellellem.schema :as schema]
            [hyperphor.ellellem.util :as util]))

;;; Provider dispatch

(defmulti -complete :provider)

(defmethod -complete :openai [req]
  (openai/complete req))

(defmethod -complete :anthropic [req]
  (anthropic/complete req))

(defmethod -complete :default [req]
  (throw (ex-info (str "Unknown provider: " (:provider req))
                  {:req req})))

(defmulti -complete-stream :provider)

(defmethod -complete-stream :openai [req]
  (openai/complete-stream req))

(defmethod -complete-stream :anthropic [req]
  (anthropic/complete-stream req))

;;; Public API

(defn complete
  "Call the LLM and return a normalized response map.

  Request keys:
    :provider   - :openai or :anthropic (required)
    :model      - model string (optional, defaults to provider's default)
    :messages   - vector of {:role :user/:assistant, :content \"...\"} (required)
    :system     - system prompt string (optional)
    :tools      - vector of tool defs {:name :description :parameters :fn} (optional)
    :max-tokens - max tokens in response (default 4096)

  Response keys:
    :content    - string text of response (nil if only tool calls)
    :tool-calls - vector of {:id :name :arguments} (nil if no tool calls)
    :stop-reason - :stop :tool-calls :length :content-filter
    :usage      - {:input-tokens N :output-tokens N}
    :raw        - original provider response"
  [req]
  (-complete req))

(defn complete-stream
  "Like complete but streams SSE events as a lazy seq."
  [req]
  (-complete-stream req))

(defn query
  "Convenience: single-turn user query, returns text content string.
  provider can be :openai or :anthropic."
  [provider text & {:keys [model system max-tokens tools]}]
  (-> (complete (cond-> {:provider provider
                         :messages [{:role :user :content text}]}
                  model (assoc :model model)
                  system (assoc :system system)
                  max-tokens (assoc :max-tokens max-tokens)
                  tools (assoc :tools tools)))
      :content))

;;; Agentic loop — run tool calls until :stop

(defn run-agent
  "Run a provider call with tools, automatically executing tool calls in a loop
  until the model reaches a :stop reason.

  req should include :provider :messages :tools (and optionally :system :model :max-tokens).
  max-iterations defaults to 10 to prevent infinite loops.

  Returns the final normalized response."
  [req & {:keys [max-iterations on-tool-call]
          :or {max-iterations 10}}]
  (loop [req req
         iterations 0]
    (when (>= iterations max-iterations)
      (throw (ex-info "Agent loop exceeded max iterations"
                      {:iterations iterations})))
    (let [response (complete req)
          provider (:provider req)
          tool-defs (:tools req)]
      (if (= :tool-calls (:stop-reason response))
        (let [_ (when on-tool-call (on-tool-call (:tool-calls response)))
              assistant-msg (chat/assistant-message-from-response response)
              result-msgs (chat/handle-tool-calls provider tool-defs response)
              updated-messages (concat (:messages req) [assistant-msg] result-msgs)]
          (recur (assoc req :messages (vec updated-messages))
                 (inc iterations)))
        response))))

;;; Chat session API

(defn chat
  "Create a stateful chat session. Returns a map with :send! and :history functions.

  Options: :provider :model :system :tools :max-tokens"
  [& [opts]]
  (let [state (chat/make-chat opts)]
    {:send! (fn [text]
              (chat/add-message! state {:role :user :content text})
              (let [req (merge (chat/chat-config state)
                               {:messages (chat/messages state)})
                    response (if (:tools (chat/chat-config state))
                               (run-agent req)
                               (complete req))
                    assistant-msg (chat/assistant-message-from-response response)]
                (chat/add-message! state assistant-msg)
                response))
     :history (fn [] (chat/messages state))
     :clear! (fn [] (chat/clear! state))
     :state state}))

;;; Re-export utilities

(def extract-code util/extract-code)
(def extract-json util/extract-json)
(def read-json-safe util/read-json-safe)

;;; Schema helpers

(def object-schema schema/object-schema)
(def array-schema schema/array-schema)
(def string-schema schema/string-schema)
(def number-schema schema/number-schema)

;;; Tool helpers

(def make-tool tools/make-tool)

;;; Structured output

(defn query-json
  "Run a query and attempt to parse the response as JSON.
  Returns [parsed-json remaining-text] or nil."
  [provider text & {:as opts}]
  (-> (apply query provider text (mapcat identity opts))
      util/extract-json))

(defn query-structured
  "Run a query with a JSON schema and return the parsed, coerced result.
  For OpenAI, uses native structured output (response_format).
  For Anthropic, instructs via system prompt and parses JSON from response."
  [provider text schema-name json-schema & {:keys [model system max-tokens]}]
  (case provider
    :openai
    (-> (complete (cond-> {:provider :openai
                           :messages [{:role :user :content text}]
                           :response-format (schema/openai-json-schema-format schema-name json-schema)}
                    model (assoc :model model)
                    system (assoc :system system)
                    max-tokens (assoc :max-tokens max-tokens)))
        :content
        util/read-json-safe)
    :anthropic
    (let [schema-instruction (str "\nRespond with a single JSON object matching this schema:\n"
                                  (pr-str json-schema)
                                  "\nDo not include explanation, only the JSON object.")
          sys (str (or system "") schema-instruction)]
      (-> (complete (cond-> {:provider :anthropic
                             :messages [{:role :user :content text}]
                             :system sys}
                      model (assoc :model model)
                      max-tokens (assoc :max-tokens max-tokens)))
          :content
          util/extract-and-parse))))
