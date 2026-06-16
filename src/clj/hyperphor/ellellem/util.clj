(ns hyperphor.ellellem.util
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]))

(defn read-json-safe
  [s]
  (try
    (json/read-str s :key-fn keyword)
    (catch Throwable _
      s)))

(defn extract-code
  "Extract a fenced code block from markdown text.
  Returns [type code rest-of-text] or nil if no block found."
  [s]
  (let [[m? type code] (re-find #"```(.*)\n([\s\S]*?)```" s)]
    (when m?
      [(keyword type) code (str/replace s m? "")])))

(defn extract-json
  "Extract and parse JSON from an LLM markdown response.
  Returns [parsed-json rest-of-text] or nil."
  [s]
  (let [[type code text] (extract-code s)]
    (if (= type :json)
      [(read-json-safe code) text]
      (let [xtract (or (re-find #"JSON[\s\S]*?(\[[\s\S]*\])" s)
                       (re-find #"(?s)[\s\S]*?(\[\s*\{[\s\S]*\}\s*\])" s))]
        (when xtract
          [(read-json-safe (second xtract)) s])))))

(defn extract-clojure
  "Extract and read a Clojure/EDN code block from an LLM markdown response.
  Returns [data rest-of-text] or nil."
  [s]
  (try
    (let [[type code text] (extract-code s)]
      (when (#{:clojure :edn} type)
        [(read-string code) text]))
    (catch Exception _
      nil)))

(defn parse-sse-stream
  "Parse a Server-Sent Events input stream into a lazy seq of parsed data maps.
  Skips [DONE] markers and non-data lines."
  [stream]
  (let [reader (BufferedReader. (InputStreamReader. ^java.io.InputStream stream "UTF-8"))]
    (->> (line-seq reader)
         (filter #(str/starts-with? % "data: "))
         (map #(subs % 6))
         (remove #(= % "[DONE]"))
         (map read-json-safe))))

(defn extract-and-parse
  "Try to extract JSON from LLM text output and return the parsed value.
  Returns parsed map/vector, or the raw string if extraction fails."
  [text]
  (or (when-let [[parsed _] (extract-json text)]
        parsed)
      (read-json-safe text)
      text))

(defn stream->channel
  "Read SSE stream into a core.async channel. Closes channel when stream ends."
  [stream ch]
  (require '[clojure.core.async :as async])
  (future
    (try
      (doseq [event (parse-sse-stream stream)]
        ((resolve 'clojure.core.async/put!) ch event))
      (finally
        ((resolve 'clojure.core.async/close!) ch)))))
