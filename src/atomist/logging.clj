(ns atomist.logging
  (:require [clojure.string :as str]
            [taoensso.encore :as enc]
            [taoensso.timbre :as timbre :refer [merge-config! stacktrace]]))

(defn output-fn
  ([data] ; For partials
   (let [{:keys [level ?err #_vargs msg_ ?ns-str ?file hostname_
                 timestamp_ ?line]} data]
     (str
      (when-let [ts (force timestamp_)] (str ts " "))
      (str/upper-case (name level))  " "
      (force msg_)
      (when-let [err ?err]
        (str enc/system-newline (stacktrace err)))))))

(merge-config!
  {:output-fn output-fn})

