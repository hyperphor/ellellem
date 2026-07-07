(ns hyperphor.ellellem.providers.anthropic
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [environ.core :as env]
            [hyperphor.ellellem.util :as util]))

(def default-model "claude-opus-4-8")
(def base-url "https://api.anthropic.com/v1")
(def api-version "2023-06-01")

(defn api-key []
  (or (env/env :anthropic-api-key)
      (throw (ex-info "ANTHROPIC_API_KEY not set" {}))))

(defn- base-headers []
  {"x-api-key" (api-key)
   "anthropic-version" api-version
   "content-type" "application/json"})

(defn api-get
  [path params]
  (:body (client/get (str base-url path)
                     {:query-params params
                      :headers (base-headers)
                      :as :json})))

(defn api-post
  [path body]
  (:body (client/post (str base-url path)
                      {:body (json/write-str body)
                       :content-type :application/json
                       :headers (base-headers)
                       :as :json})))

(defn api-post-stream
  [path body]
  (:body (client/post (str base-url path)
                      {:body (json/write-str body)
                       :content-type :application/json
                       :headers (base-headers)
                       :as :stream})))

(defn list-models []
  (api-get "/models" {}))

(defn normalize-models
  "Convert a raw Anthropic /models list response to a vector of normalized model maps."
  [response]
  (mapv (fn [m] {:id (:id m) :name (:display_name m) :created (:created_at m) :raw m})
        (:data response)))

;;; Normalization

(defn- content-block->text
  "Extract text from a content block, or nil if not a text block."
  [{:keys [type text]}]
  (when (= type "text") text))

(defn- content-block->tool-call
  "Convert an Anthropic tool_use block to ellellem normalized tool call."
  [{:keys [type id name input]}]
  (when (= type "tool_use")
    {:id id :name name :arguments input}))

(defn- normalize-stop-reason [stop-reason]
  (case stop-reason
    "end_turn" :stop
    "tool_use" :tool-calls
    "max_tokens" :length
    "stop_sequence" :stop
    (keyword stop-reason)))

(defn normalize-response
  "Convert a raw Anthropic Messages response to the ellellem normalized format."
  [response]
  (let [content-blocks (:content response)
        text (->> content-blocks
                  (keep content-block->text)
                  (clojure.string/join "\n")
                  not-empty)
        tool-calls (->> content-blocks
                        (keep content-block->tool-call)
                        not-empty)]
    {:content text
     :tool-calls tool-calls
     :stop-reason (normalize-stop-reason (:stop_reason response))
     :usage {:input-tokens (get-in response [:usage :input_tokens])
             :output-tokens (get-in response [:usage :output_tokens])}
     :raw response}))

;;; Serialization for requests

(defn- serialize-tool
  [{:keys [name description parameters]}]
  {:name name
   :description description
   :input_schema parameters})

(defn- message->anthropic
  "Convert an ellellem message to Anthropic Messages API format.
  Handles user/assistant/tool-result messages.
  Tool results are user messages with content of type 'tool_result'."
  [{:keys [role content tool-calls tool-result-id]}]
  (cond
    ;; Tool result (from tool execution, goes back as user message)
    tool-result-id
    {:role "user"
     :content [{:type "tool_result"
                :tool_use_id tool-result-id
                :content (if (string? content) content (json/write-str content))}]}
    ;; Assistant message with tool calls — reconstruct content blocks
    (and (= role :assistant) tool-calls)
    {:role "assistant"
     :content (vec (concat
                    (when content [{:type "text" :text content}])
                    (map (fn [{:keys [id name arguments]}]
                           {:type "tool_use"
                            :id id
                            :name name
                            :input arguments})
                         tool-calls)))}
    ;; Regular message
    :else
    {:role (name role) :content content}))

;;; Main complete function

(defn complete
  "Call Anthropic Messages API.
  req keys: :model :messages :system :tools :max-tokens :stream"
  [{:keys [model messages system tools max-tokens]
    :or {model default-model max-tokens 4096}}]
  (let [body (cond-> {:model model
                      :max_tokens max-tokens
                      :messages (mapv message->anthropic messages)}
               system (assoc :system system)
               tools (assoc :tools (mapv serialize-tool tools)))]
    (-> (api-post "/messages" body)
        normalize-response)))

(defn complete-stream
  "Call Anthropic Messages API with streaming.
  Returns lazy seq of parsed SSE events."
  [{:keys [model messages system tools max-tokens]
    :or {model default-model max-tokens 4096}}]
  (let [body (cond-> {:model model
                      :max_tokens max-tokens
                      :messages (mapv message->anthropic messages)
                      :stream true}
               system (assoc :system system)
               tools (assoc :tools (mapv serialize-tool tools)))]
    (util/parse-sse-stream (api-post-stream "/messages" body))))
