(ns hyperphor.ellellem.chat
  (:require [hyperphor.ellellem.tools :as tools]))

;;; Conversation state management

(defn make-chat
  "Create a new chat conversation context.
  Options:
    :provider  - :openai or :anthropic (default :openai)
    :model     - model string override
    :system    - system prompt string
    :tools     - vector of tool definitions
    :max-tokens - max tokens per completion"
  [& [{:keys [provider model system tools max-tokens]
       :or {provider :openai}}]]
  (atom {:provider provider
         :model model
         :system system
         :tools tools
         :max-tokens max-tokens
         :messages []}))

(defn add-message!
  "Append a message to the conversation history."
  [chat msg]
  (swap! chat update :messages conj msg)
  chat)

(defn messages
  "Return the current message history."
  [chat]
  (:messages @chat))

(defn clear!
  "Clear the conversation history, keeping configuration."
  [chat]
  (swap! chat assoc :messages [])
  chat)

(defn chat-config
  "Return the non-history config for use in a complete call."
  [chat]
  (select-keys @chat [:provider :model :system :tools :max-tokens]))

;;; Agentic tool loop helpers

(defn handle-tool-calls
  "Given a response with tool calls and a tools collection,
  execute all tool calls and return the result messages to add to history.
  For Anthropic, packs results into a single user message."
  [provider tool-defs response]
  (let [results (tools/dispatch-all tool-defs (:tool-calls response))]
    (case provider
      :anthropic [(tools/results->anthropic-user-message results)]
      (mapv tools/result->openai-message results))))

(defn assistant-message-from-response
  "Build an assistant history message from a normalized response.
  Preserves tool_calls in the message so the history is valid for re-submission."
  [response]
  (cond-> {:role :assistant}
    (:content response) (assoc :content (:content response))
    (:tool-calls response) (assoc :tool-calls (:tool-calls response))))
