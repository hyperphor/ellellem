(ns hyperphor.ellellem.providers.openai
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [environ.core :as env]
            [clojure.string :as str]
            [hyperphor.ellellem.util :as util]))

(def default-model "gpt-4.1")
(def base-url "https://api.openai.com/v1")

(defn api-key []
  (or (env/env :openai-api-key)
      (throw (ex-info "OPENAI_API_KEY not set" {}))))

(defn- auth-headers []
  {"Authorization" (str "Bearer " (api-key))
   "OpenAI-Beta" "assistants=v2"})

(defn api-get
  [path params]
  (:body (client/get (str base-url path)
                     {:query-params params
                      :headers (auth-headers)
                      :as :json})))

(defn api-post
  [path body]
  (:body (client/post (str base-url path)
                      {:body (json/write-str body)
                       :content-type :application/json
                       :headers (auth-headers)
                       :as :json})))

(defn api-post-stream
  [path body]
  (:body (client/post (str base-url path)
                      {:body (json/write-str body)
                       :content-type :application/json
                       :headers (auth-headers)
                       :as :stream})))

(defn list-models []
  (api-get "/models" {}))

(defn normalize-models
  "Convert a raw OpenAI /models list response to a vector of normalized model maps."
  [response]
  (mapv (fn [m] {:id (:id m) :created (:created m) :raw m})
        (:data response)))

;;; Normalization

(defn- normalize-tool-call
  [{:keys [id type function]}]
  {:id id
   :name (:name function)
   :arguments (util/read-json-safe (:arguments function))})

(defn- normalize-stop-reason
  [finish-reason]
  (case finish-reason
    "stop" :stop
    "tool_calls" :tool-calls
    "length" :length
    "content_filter" :content-filter
    (keyword finish-reason)))

(defn normalize-response
  "Convert a raw OpenAI chat completions response to the ellellem normalized format.
  A model refusal (message.refusal set, e.g. under structured outputs) is surfaced
  as :refusal with :stop-reason :refusal, since finish_reason alone doesn't signal it."
  [response]
  (let [choice (get-in response [:choices 0])
        message (:message choice)
        refusal (:refusal message)
        finish-reason (normalize-stop-reason (:finish_reason choice))
        tool-calls (some->> (:tool_calls message)
                            (mapv normalize-tool-call)
                            not-empty)]
    {:content (:content message)
     :refusal refusal
     :tool-calls tool-calls
     :stop-reason (if refusal :refusal finish-reason)
     :usage {:input-tokens (get-in response [:usage :prompt_tokens])
             :output-tokens (get-in response [:usage :completion_tokens])}
     :raw response}))

;;; Serialization for requests

(defn- serialize-tool
  [{:keys [name description parameters]}]
  {:type "function"
   :function {:name name
              :description description
              :parameters parameters}})

(defn- messages->openai
  "Convert normalized ellellem messages to OpenAI format.
  ellellem messages can have :role :user/:assistant/:tool/:system and :content.
  Tool result messages have :tool-call-id."
  [messages]
  (mapv (fn [msg]
          (cond-> {:role (name (:role msg))}
            (:content msg) (assoc :content (:content msg))
            (:tool-calls msg) (assoc :tool_calls
                                     (mapv (fn [tc]
                                             {:id (:id tc)
                                              :type "function"
                                              :function {:name (:name tc)
                                                         :arguments (json/write-str (:arguments tc))}})
                                           (:tool-calls msg)))
            (:tool-call-id msg) (assoc :tool_call_id (:tool-call-id msg))))
        messages))

;;; Main complete function

(defn complete
  "Call OpenAI chat completions API.
  req keys: :model :messages :system :tools :max-tokens :response-format :stream"
  [{:keys [model messages system tools max-tokens response-format]
    :or {model default-model max-tokens 4096}}]
  (let [system-msg (when system [{:role "system" :content system}])
        all-messages (concat system-msg messages)
        body (cond-> {:model model
                      :messages (messages->openai all-messages)
                      :max_tokens max-tokens}
               tools (assoc :tools (mapv serialize-tool tools))
               response-format (assoc :response_format response-format))]
    (-> (api-post "/chat/completions" body)
        normalize-response)))

(defn complete-stream
  "Call OpenAI chat completions API with streaming. Returns lazy seq of parsed SSE events."
  [{:keys [model messages system tools max-tokens]
    :or {model default-model max-tokens 4096}}]
  (let [system-msg (when system [{:role "system" :content system}])
        all-messages (concat system-msg messages)
        body (cond-> {:model model
                      :messages (messages->openai all-messages)
                      :max_tokens max-tokens
                      :stream true}
               tools (assoc :tools (mapv serialize-tool tools)))]
    (util/parse-sse-stream (api-post-stream "/chat/completions" body))))

;;; Responses API (newer OpenAI API)

(defn run-response
  "Call the OpenAI responses API (newer stateful API).
  Returns full response body."
  [input & [{:as params}]]
  (api-post "/responses"
            (merge {:model default-model :input input}
                   params)))
