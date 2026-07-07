(ns hyperphor.ellellem.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [hyperphor.ellellem.core :as llm]
            [hyperphor.ellellem.tools :as tools]
            [hyperphor.ellellem.schema :as schema]
            [hyperphor.ellellem.util :as util]
            [hyperphor.ellellem.providers.openai :as openai]
            [hyperphor.ellellem.providers.anthropic :as anthropic]))

;;; Unit tests — no API calls required

(deftest test-extract-code
  (testing "extracts json block"
    (let [md "Here is the data:\n```json\n{\"key\": \"value\"}\n```\nDone."
          [type code _] (util/extract-code md)]
      (is (= :json type))
      (is (= "{\"key\": \"value\"}" (.trim code)))))

  (testing "returns nil for no block"
    (is (nil? (util/extract-code "no code here")))))

(deftest test-read-json-safe
  (testing "parses valid JSON"
    (is (= {:a 1} (util/read-json-safe "{\"a\": 1}"))))
  (testing "returns input on invalid JSON"
    (is (= "not json" (util/read-json-safe "not json")))))

(deftest test-schema-coerce
  (testing "string passthrough"
    (is (= "hello" (schema/coerce {:type "string"} "hello"))))
  (testing "number coercion"
    (is (= 42.0 (schema/coerce {:type "number"} "42"))))
  (testing "boolean coercion"
    (is (= true (schema/coerce {:type "boolean"} "true"))))
  (testing "array coercion"
    (is (= ["a" "b"] (schema/coerce {:type "array" :items {:type "string"}} ["a" "b"])))))

(deftest test-object-schema
  (let [s (schema/object-schema {:name (schema/string-schema "person name")
                                 :age (schema/number-schema "person age")}
                                :required ["name"])]
    (is (= "object" (:type s)))
    (is (= ["name"] (:required s)))
    (is (contains? (:properties s) :name))))

(deftest test-make-tool
  (let [t (tools/make-tool "add"
                           "Add two numbers"
                           {:type "object"
                            :properties {:a {:type "number"} :b {:type "number"}}
                            :required ["a" "b"]}
                           (fn [{:keys [a b]}] (+ a b)))]
    (is (= "add" (:name t)))
    (is (fn? (:fn t)))))

(deftest test-tool-dispatch
  (let [add-tool (tools/make-tool "add" "Add" {} (fn [{:keys [a b]}] (+ a b)))
        tool-call {:id "call_1" :name "add" :arguments {:a 3 :b 4}}]
    (is (= {:id "call_1" :name "add" :result 7}
           (tools/dispatch [add-tool] tool-call)))))

(deftest test-tool-not-found
  (is (thrown? Exception (tools/dispatch [] {:id "x" :name "missing" :arguments {}}))))

(deftest test-openai-normalize-response
  (let [raw {:choices [{:message {:role "assistant"
                                  :content "Hello!"
                                  :tool_calls nil}
                        :finish_reason "stop"}]
             :usage {:prompt_tokens 10 :completion_tokens 5}}
        normalized (openai/normalize-response raw)]
    (is (= "Hello!" (:content normalized)))
    (is (= :stop (:stop-reason normalized)))
    (is (= 10 (get-in normalized [:usage :input-tokens])))
    (is (= 5 (get-in normalized [:usage :output-tokens])))))

(deftest test-openai-normalize-tool-call-response
  (let [raw {:choices [{:message {:role "assistant"
                                  :content nil
                                  :tool_calls [{:id "call_1"
                                               :type "function"
                                               :function {:name "get_weather"
                                                          :arguments "{\"location\":\"NYC\"}"}}]}
                        :finish_reason "tool_calls"}]
             :usage {:prompt_tokens 20 :completion_tokens 15}}
        normalized (openai/normalize-response raw)]
    (is (= :tool-calls (:stop-reason normalized)))
    (is (= [{:id "call_1" :name "get_weather" :arguments {:location "NYC"}}]
           (:tool-calls normalized)))))

(deftest test-anthropic-normalize-response
  (let [raw {:content [{:type "text" :text "Hello from Claude!"}]
             :stop_reason "end_turn"
             :usage {:input_tokens 8 :output_tokens 4}}
        normalized (anthropic/normalize-response raw)]
    (is (= "Hello from Claude!" (:content normalized)))
    (is (= :stop (:stop-reason normalized)))
    (is (= 8 (get-in normalized [:usage :input-tokens])))))

(deftest test-anthropic-normalize-tool-call-response
  (let [raw {:content [{:type "tool_use"
                        :id "toolu_01"
                        :name "calculator"
                        :input {:expression "2+2"}}]
             :stop_reason "tool_use"
             :usage {:input_tokens 30 :output_tokens 20}}
        normalized (anthropic/normalize-response raw)]
    (is (= :tool-calls (:stop-reason normalized)))
    (is (= [{:id "toolu_01" :name "calculator" :arguments {:expression "2+2"}}]
           (:tool-calls normalized)))))

(deftest test-openai-normalize-models
  (let [raw {:data [{:id "gpt-4.1" :object "model" :created 1234 :owned_by "openai"}]}
        normalized (openai/normalize-models raw)]
    (is (= [{:id "gpt-4.1" :created 1234 :raw (first (:data raw))}]
           normalized))))

(deftest test-anthropic-normalize-models
  (let [raw {:data [{:type "model" :id "claude-opus-4-8"
                     :display_name "Claude Opus 4.8" :created_at "2026-01-01T00:00:00Z"}]}
        normalized (anthropic/normalize-models raw)]
    (is (= [{:id "claude-opus-4-8" :name "Claude Opus 4.8"
             :created "2026-01-01T00:00:00Z" :raw (first (:data raw))}]
           normalized))))

(deftest test-tool-result-messages
  (let [results [{:id "call_1" :name "add" :result 7}
                 {:id "call_2" :name "mul" :result 12}]]
    (testing "openai format"
      (let [msgs (mapv tools/result->openai-message results)]
        (is (= :tool (:role (first msgs))))
        (is (= "call_1" (:tool-call-id (first msgs))))))
    (testing "anthropic batched format"
      (let [msg (tools/results->anthropic-user-message results)]
        (is (= :user (:role msg)))
        (is (= 2 (count (:content msg))))
        (is (= "tool_result" (:type (first (:content msg)))))))))

(deftest test-chat-session
  (testing "chat state management"
    (let [session (llm/chat {:provider :openai})]
      (is (fn? (:send! session)))
      (is (fn? (:history session)))
      (is (fn? (:clear! session)))
      (is (= [] ((:history session)))))))

(comment
  ;;; Integration tests — require API keys

  (llm/query :openai "Say hello in one word")

  (llm/query :anthropic "Say hello in one word")

  (llm/list-models :openai)

  (llm/list-models :anthropic)

  (let [weather-tool (llm/make-tool
                      "get_weather"
                      "Get current weather for a location"
                      {:type "object"
                       :properties {:location {:type "string" :description "City name"}}
                       :required ["location"]}
                      (fn [{:keys [location]}]
                        (str "Sunny, 72°F in " location)))
        response (llm/run-agent
                  {:provider :openai
                   :messages [{:role :user :content "What's the weather in NYC and LA?"}]
                   :tools [weather-tool]})]
    (:content response))

  (let [s (llm/chat {:provider :anthropic :system "You are a helpful assistant"})]
    (println (:content ((:send! s) "What is 2+2?")))
    (println (:content ((:send! s) "Multiply that by 3")))
    ((:history s)))

  (llm/query-structured
   :openai
   "Extract: John Smith is 35 years old"
   "person"
   (llm/object-schema {:name (llm/string-schema "full name")
                        :age (llm/number-schema "age in years")}
                       :required ["name" "age"])))
