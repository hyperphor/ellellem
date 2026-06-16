(ns hyperphor.ellellem.schema
  (:require [clojure.data.json :as json]
            [hyperphor.ellellem.util :as util]))

;;; JSON Schema helpers for structured output

(defn object-schema
  "Build a JSON Schema object definition."
  [properties & {:keys [required] :or {required []}}]
  {:type "object"
   :properties properties
   :required required
   :additionalProperties false})

(defn array-schema
  "Build a JSON Schema array definition."
  [item-schema]
  {:type "array" :items item-schema})

(defn string-schema
  ([] {:type "string"})
  ([description] {:type "string" :description description})
  ([description enum-vals] {:type "string" :description description :enum enum-vals}))

(defn number-schema
  ([] {:type "number"})
  ([description] {:type "number" :description description}))

(defn boolean-schema
  ([] {:type "boolean"})
  ([description] {:type "boolean" :description description}))

;;; OpenAI structured output (response_format)

(defn openai-json-schema-format
  "Build an OpenAI response_format for structured JSON output."
  [name schema & {:keys [strict] :or {strict true}}]
  {:type "json_schema"
   :json_schema {:name name
                 :schema schema
                 :strict strict}})

;;; Parsing / coercion

(defn coerce
  "Coerce a value to match a JSON schema spec.
  Currently validates types and does basic coercions (string->number, etc.).
  Returns the coerced value or throws on invalid input."
  [schema value]
  (let [schema-type (:type schema)]
    (case schema-type
      "string" (if (string? value) value (str value))
      "number" (if (number? value)
                 value
                 (try (Double/parseDouble (str value))
                      (catch NumberFormatException _
                        (throw (ex-info "Cannot coerce to number" {:value value})))))
      "integer" (if (integer? value)
                  value
                  (try (Long/parseLong (str value))
                       (catch NumberFormatException _
                         (throw (ex-info "Cannot coerce to integer" {:value value})))))
      "boolean" (cond
                  (boolean? value) value
                  (= "true" (str value)) true
                  (= "false" (str value)) false
                  :else (throw (ex-info "Cannot coerce to boolean" {:value value})))
      "array" (if (sequential? value)
                (mapv #(coerce (:items schema) %) value)
                (throw (ex-info "Expected array" {:value value})))
      "object" (if (map? value)
                 (reduce-kv (fn [m k v]
                               (let [prop-schema (get (:properties schema) k)]
                                 (if prop-schema
                                   (assoc m k (coerce prop-schema v))
                                   (assoc m k v))))
                             {}
                             value)
                 (throw (ex-info "Expected object" {:value value})))
      value)))

(defn validate-required
  "Check that all required fields are present in a map. Returns nil or throws."
  [schema value]
  (when-let [required (:required schema)]
    (doseq [field (map keyword required)]
      (when-not (contains? value field)
        (throw (ex-info (str "Missing required field: " field)
                        {:field field :value value}))))))
