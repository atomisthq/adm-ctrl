(ns atomist.namespaces
  (:require [atomist.k8s :refer [build-http-kubectl-client]]
            [cheshire.core :as json]
            [clj-http.lite.client :as client]
            [clojure.core.async :as async :refer [>! <! go]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [taoensso.timbre :as timbre :refer [info  warn infof]]
            [atomist.json :refer [json-response]]))

(def namespaces-to-enforce (atom #{}))

(defn remove-namespace [s]
  (swap! namespaces-to-enforce (fn [coll] (into #{} (remove #(= % s) coll)))))

(defn add-namespace [s]
  (swap! namespaces-to-enforce (fn [coll] (into #{} (conj coll s)))))

(defn namespace-callback
  "process kubernetes watch events for namespaces"
  [d]
  (let [{:keys [type object]} d]
    (cond
      (and
       (#{"ADDED" "MODIFIED"} type)
       (= "enabled" (-> object :metadata :annotations :policy-controller.atomist.com/policy)))
      (do
        (info "-> enable policy on namespace " (-> object :metadata :name))
        (add-namespace (-> object :metadata :name)))
      (and
       (#{"ADDED" "MODIFIED"} type)
       (not (= "enabled" (-> object :metadata :annotations :policy-controller.atomist.com/policy))))
      (do
        (info "-> disable policy for namespace " (-> object :metadata :name))
        (remove-namespace (-> object :metadata :name)))
      (#{"DELETED"} type)
      (do
        (info "-> disable policy for deleted namespace" (-> object :metadata :name))
        (remove-namespace (-> object :metadata :name))))))

(defn process-watcher-stream
  "response should have an open stream in the body
   assumes stream is a sequence of of lines where each each line has a kubernetes watch event
     returns a [:error ...]  or a [:closed ...]  value"
  [{in :body status :status :as response}]
  (try
    (if (= 200 status)
      (let [lines (-> in (io/reader) line-seq)]
        (loop [events lines]
          (let [lines (-> in (io/reader) line-seq)]
            (if (first events)
              (let [payload (try (json/parse-string (first events) keyword)
                                 (catch Throwable _
                                   {:type :error :object (first events)}))]
                (namespace-callback payload)
                (recur (rest events)))
              (do
                (.close in)
                [:closed])))))
      (throw (ex-info (format "status %s in watcher response" status) response)))
    (catch Throwable ex
      (warn "process-watcher-stream failed to open stream")
      [:error ex])))

(defn- get-namespaces
  "request list of namespaces or possibly a watch stream (depends on the opts)"
  [server token opts]
  (let [url (format "%s/api/v1/namespaces" server)]
    ((if (= :json (:as opts))
       (comp json-response client/get)
       client/get)
     url
     (merge {:insecure? true
             :throw-exceptions false
             :headers {"Authorization" (format "Bearer %s" token)}} opts))))

(defn start-watching-namespaces
  "watch this open stream in a thread
    return channel which will emit one value and then close when stream closes - value is either [:closed ...] or [:error ...]"
  [server token]
  (async/thread (-> (get-namespaces server token {:as :stream
                                                  :query-params {:watch 1}})
                    (process-watcher-stream))))

(defn list-namespaces
  "returns a channel that will close once the initial namespaces have been put on a callback channel
    returns a channel that will emit one value - either :done or a failed http response"
  [server token]
  (let [{:keys [status body] :as response} (get-namespaces server token {:as :json})]
    (if (= 200 status)
      (do
        (doseq [object (-> body :items)]
          (namespace-callback {:type "ADDED" :object object}))
        :done)
      response)))

(defn http-watch-namespaces
  "continue watching namespaces - rebuild connection when it's closed
    - put :stop on control-ch when done
    - returns channel which will emit [:stopped ...] when stopping normally or [:error ...] when the connection can't be established"
  [{:keys [server token]} control-ch]
  (go
    (let [r (list-namespaces server token)]
      (if (= :done r)
        (<! (async/go-loop [watch-ch (start-watching-namespaces server token)]
              (let [[val port] (async/alts! [control-ch watch-ch])]
                (condp = port
                  control-ch (if (= :stop val)
                               :stopped
                               :non-stop-on-control-channel)
                  watch-ch
                  (let [[t v] val]
                    (case t
                      :error [t v]
                      :closed (do
                                (println "restart watch after stream closed")
                                (recur (start-watching-namespaces server token)))))))))
        [:error r]))))

(def watcher-state (atom {}))

(defn start-watching
  "will not restart after http failures but will restart if stream closes"
  [k8s]
  (swap! watcher-state (constantly
                        (let [control-ch (async/chan)]
                          {:watching-namespaces (http-watch-namespaces k8s control-ch)
                           :stop (fn [] (go (>! control-ch :stop)))})))
  (go (let [r (<! (:watching-namespaces @watcher-state))]
        (infof "watching namespaces process ended - %s" r))))

(comment
  (def c (build-http-kubectl-client))
  (pprint @namespaces-to-enforce)
  (start-watching c)
  (pprint @watcher-state)
  ;; stop the watcher - TODO this should close the stream
  ((:stop @watcher-state)))
