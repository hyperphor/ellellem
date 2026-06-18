(ns hyperphor.ellellem.tools.weather
  (:require [hato.client :as client]
            [clojure.data.json :as json]
            [environ.core :as env]
            [clojure.string :as str]
            [hyperphor.ellellem.util :as util]
            [hyperphor.ellellem.core :as core]))

(defn weather-api-key []
  (or (env/env :weather-api-key)
      (throw (ex-info "WEATHER_API_KEY not set" {}))))

(defn get-weather
  [loc]
  (let [full (client/get "http://api.weatherapi.com/v1/current.json"
              {:query-params {:key weather-api-key
                              :q loc}
               :as :json})
        current   (-> full
                      :body
                      :current)]
    (str (get-in current [:condition :text]) ", " (:temp_f current))))


