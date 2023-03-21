(ns atomist.json
  (:require [cheshire.core :as json]))

(defn json-response [{:keys [body] :as response}]
  (assoc response
         :body (cond
                 (string? body)
                 (json/parse-string body keyword)
                 :else
                 (json/parse-stream body keyword))))
