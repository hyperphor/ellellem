(ns hyperphor.ellellem.tools
  (:require [clojure.data.json :as json]))

;;; Tool definition format:
;;; {:name "function-name"
;;;  :description "what it does"
;;;  :parameters {:type "object"
;;;               :properties {:param {:type "string" :description "..."}}
;;;               :required ["param"]}
;;;  :fn (fn [args-map] result)}

(defn make-tool
  "Create a tool definition map."
  [name description parameters fn]
  {:name name
   :description description
   :parameters parameters
   :fn fn})

(defn find-tool
  "Look up a tool by name from a collection of tool definitions."
  [tools name]
  (some #(when (= (:name %) name) %) tools))

(defn dispatch
  "Execute a tool call against a tool collection.
  tool-call is {:name \"fn-name\" :arguments {map-of-args} :id \"call-id\"}
  Returns {:id :name :result} where result is the tool's return value."
  [tools tool-call]
  (let [{:keys [id name arguments]} tool-call
        tool (find-tool tools name)]
    (if tool
      {:id id
       :name name
       :result ((:fn tool) arguments)}
      (throw (ex-info (str "Unknown tool: " name)
                      {:tool-call tool-call
                       :available (mapv :name tools)})))))

(defn dispatch-all
  "Execute all tool calls in a response, returning a seq of result maps."
  [tools tool-calls]
  (mapv #(dispatch tools %) tool-calls))

(defn result->openai-message
  "Convert a tool result to an OpenAI tool message for use in conversation history."
  [{:keys [id result]}]
  {:role :tool
   :tool-call-id id
   :content (if (string? result) result (json/write-str result))})

(defn result->anthropic-message
  "Convert a tool result to an Anthropic tool_result message for use in conversation history."
  [{:keys [id result]}]
  {:role :user
   :tool-result-id id
   :content (if (string? result) result (json/write-str result))})

(defn results->messages
  "Convert tool results to messages suitable for the given provider."
  [provider results]
  (case provider
    :openai (mapv result->openai-message results)
    :anthropic [(result->anthropic-message (first results))]  ; Anthropic batches into one user turn
    (mapv result->openai-message results)))

;;; Anthropic groups all tool results into a single user message with multiple content blocks
(defn results->anthropic-user-message
  "Pack multiple tool results into a single Anthropic user message."
  [results]
  {:role :user
   :content (mapv (fn [{:keys [id result]}]
                    {:type "tool_result"
                     :tool_use_id id
                     :content (if (string? result) result (json/write-str result))})
                  results)})
