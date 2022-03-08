  ;; watch crds come and go in one namespace
(ns atomist.k8s
  (:require [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [taoensso.timbre :as timbre
             :refer [info  warn infof]]
            [clojure.string :as str]
            [clojure.core.async :refer [go <! >!] :as async])
  (:import [java.util Base64]))

(defn user-type 
  [user]
  (cond 
    (:exec user) :exec
    (:auth-provider user) :auth-provider
    (:client-key-data user) :client-key-data
    :else :default))

;; get a token from a local kube context
(defmulti user->token user-type)

;; get a token by executing a command
(defmethod user->token :exec
  [{{:keys [command args env]} :exec}]
  (fn [m]
    (let [token (let [args (concat [command] args [:env (->> env
                                                             (map (fn [{:keys [name value]}]
                                                                    [name value]))
                                                             (into {})
                                                             ((fn [m] (assoc m "PATH" (System/getenv "PATH")))))])
                      {:keys [out] :as p} (apply sh/sh args)]
                  (str/trim out))]
      (-> m
          (assoc-in [:headers :authorization]
                    (format "Bearer %s" token))
          (assoc :token token)))))

(defmethod user->token :auth-provider
  [{{:keys [config name]} :auth-provider}]
  (fn [m]
    (-> m
        (assoc-in [:headers :authorization] (format "Bearer %s" (:access-token config)))
        (assoc :token (:access-token config)))))

(defmethod user->token :client-key-data [user]
  (fn [m]
    (let [f "dd.jks"]
      (spit "dd.crt" (str
                      (String. (.decode (Base64/getDecoder) (:client-certificate-data user)))
                      #_(String. (.decode (Base64/getDecoder) (-> m :cluster :certificate-authority-data)))
                      ))
      (spit "ca.crt" (String. (.decode (Base64/getDecoder) (-> m :cluster :certificate-authority-data))))

      (spit "dd.key" (String. (.decode (Base64/getDecoder) (:client-key-data user))))
      (println (sh/sh "openssl" "pkcs12" "-export" "-inkey" "dd.key" "-in" "dd.crt" "-out" f "-password" "pass:atomist"))
      (-> m
          (assoc :keystore f)
          (assoc :keystore-pass "atomist")))))

(defmethod user->token :default 
  [user] 
  (throw (ex-info "no strategy for user" user)))

(defn local-kubectl-config 
  "relies on local kubectl install (kubectl must be in the PATH)"
  []
  (let [{:keys [out]} (sh/sh "kubectl" "config" "view" "--raw" "-o" "json")
        config (json/parse-string out keyword)
        context (->> (:contexts config)
                     (filter #(= (:current-context config) (:name %)))
                     first)]
    (merge
      (select-keys context [:name])
      {:cluster (->> (:clusters config) 
                     (filter #(= (-> context :context :cluster) (:name %)))
                     first
                     :cluster)
       :user (->> (:users config)
                  (filter #(= (-> context :context :user) (:name %)))
                  first
                  :user)})))

(defn local-http-client [{{:keys [server certificate-authority-data]} :cluster
                          user :user
                          :as config}]
  (let [ca-file (io/file "/tmp/ca-docker.crt")]
    #_(spit ca-file (String. (.decode (Base64/getDecoder) certificate-authority-data)))
    ((user->token user)
     (merge config {:server server
                    :type :pure-http
                    :ca-cert (.getPath ca-file)
                    :insecure? true}))))

(def build-http-kubectl-client (comp local-http-client local-kubectl-config))

(defn build-http-cluster-client []
  {:type :pure-http
   :server "https://kubernetes.default.svc"
   :token (slurp "/var/run/secrets/kubernetes.io/serviceaccount/token")
   :ca-cert "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
   :insecure? true})

(defn http-get-pod [{:keys [server token]} ns n]
  (let [response (client/get (format "%s/api/v1/namespaces/%s/pods/%s" server ns n)
                             {:headers {"Authorization" (format "Bearer %s" token)}
                              :as :json
                              :insecure? true
                              :throws false})]
    (case (:status response)
      404 (throw (ex-info (format "Pod not found: %s/%s" ns n) (select-keys response [:status])))
      401 (throw (ex-info "Pod get unauthorized" (select-keys response [:status])))
      200 (:body response)
      (throw (ex-info "Pod get unexpected status" (:status response))))))

(defn http-get-node [{:keys [server token]} n]
  (let [response (client/get (format "%s/api/v1/nodes/%s" server n)
                             {:headers {"Authorization" (format "Bearer %s" token)}
                              :insecure? true
                              :as :json
                              :throws false})]
    (case (:status response)
      404 (throw (ex-info (format "Node not found: %s" n) (select-keys response [:status])))
      401 (throw (ex-info "Node get unauthorized" (select-keys response [:status]))) 
      200 (:body response)
      (throw (ex-info "Node get unexpected status" (:status response))))))

(defn process-watcher-stream
  ""
  [{in :body status :status :as response} cb-ch]
  (try
    (if (= 200 status)
      (let [lines (-> in (io/reader) line-seq)]
        (async/go-loop [events lines]
          (let [lines (-> in (io/reader) line-seq)]
            (if (first events)
              (let [payload (try (json/parse-string (first events) keyword)
                                 (catch Throwable _
                                   {:type :error :object (first events)}))]
                (async/>! cb-ch payload)
                (recur (rest events)))
              (do
                (.close in)
                [:closed])))))
      (throw (ex-info (format "status %s in watcher response" status) response)))
    (catch Throwable ex
      (let [p (async/promise-chan)]
        (println "process-watcher-stream failed to open stream")
        (async/>!! p [:error ex])
        p))))

(defn- get-namespaces [server token opts]
  (let [url (format "%s/api/v1/namespaces" server) ]
    (client/get url (merge {:insecure? true
                            :throw-exceptions false
                            :headers {"Authorization" (format "Bearer %s" token)}} opts))))

(defn start-watching-namespaces
  [server token cb-ch]
  (process-watcher-stream
   (get-namespaces server token {:as :stream
                                 :query-params {:watch 1}})
   cb-ch))

(defn list-namespaces
  [server token cb-ch]
  (go
    (let [{:keys [status body] :as response} (get-namespaces server token {:as :json})]
      (if (= 200 status)
        (do
          (doseq [object (-> body :items)]
            (>! cb-ch {:type "ADDED" :object object}))
          :done)
        response))))

(defn http-watch-namespaces
  "continue watching namespaces - rebuild connection when it's closed
    - put :stop on control-ch when done
    - returns channel which will emit :stopped when stopping normally or :error when the connection can't be established"
  [{:keys [server token]} cb-ch control-ch]
  (go
    (let [r (<! (list-namespaces server token cb-ch))]
      (if (= :done r)
        (do
          (<! (async/go-loop [watch-ch (start-watching-namespaces server token cb-ch)]
                (let [[val port] (async/alts! [control-ch watch-ch])]
                  (condp = port
                    control-ch (if (= :stop val)
                                 :stopped
                                 :non-stop-on-control-channel)
                    watch-ch
                    (let [[t v] val]
                      (println "case " t)
                      (case t
                        :error [t v]
                        :closed (do
                                  (println "restart watch after stream closed")
                                  (recur (start-watching-namespaces server token cb-ch))))))))))
        [:error r]))))

(defn get-pod [k8s ns n]
  (case (:type k8s)
    :pure-http (http-get-pod k8s ns n)))

(defn get-node [k8s n]
  (case (:type k8s)
    :pure-http (http-get-node k8s n)))

(defn watch-namespaces [k8s cb control-ch]
  (case (:type k8s)
    :pure-http (http-watch-namespaces k8s cb control-ch)))

(defn namespace-callback [d]
  (let [{:keys [type object]} d]
    (println "------ " type)
    (pprint object)
    (cond
      (and
       (= "ADDED" type)
       (= "enabled" (-> object :metadata :annotations :policy-controller.atomist.com/policy)))
      (println "-> enable policy on namespace " (-> object :metadata :name)))))

(comment

  (def c (build-http-kubectl-client))
  (pprint c)
  (sh/sh "curl"
         "-k"
         "--cert" "dd.crt"
         "--key" "dd.key"
         "https://kubernetes.docker.internal:6443/api/v1/namespaces")
  ;; k get secret policy-controller-token-5g542 -n atomist -o json | jq -r .data.token | base64 -d > token.txt
  (def token (slurp "token.txt"))
  ;; TODO 
  ;; curl -k -H "Authorization: Bearer $(< token.txt)" \
  ;;      https://kubernetes.docker.internal:6443/apis/policy-controller.atomist.com/v1/namespaces/production/rules?watch=1
  ;; test with
  ;; k apply -f resources/k8s/crds/rule1.yaml
  ;; k delete rule rule1 -n production
  ;;

  ;; watch individual namespaces on their own - namespaces/*/ does not work
  (def all-customresource-definitions "https://kubernetes.docker.internal:6443/apis/apiextensions.k8s.io/v1/customresourcedefinitions")
  (def all-rules-url "https://kubernetes.docker.internal:6443/apis/policy-controller.atomist.com/v1/namespaces/production/rules")
  ;; the * does seem to work for listing
  (def listing-rules "https://kubernetes.docker.internal:6443/apis/policy-controller.atomist.com/v1/namespaces/*/rules")

  (def control-channel (async/chan))
  (def callback-channel (async/chan))
  (def watch-channel (http-watch-namespaces
                      c
                      callback-channel
                      control-channel))

  (async/go-loop [] (let [v (<! callback-channel)] (namespace-callback v)) (recur))
  (go (>! control-channel :stop))
  (go (println "watch channel: " (<! watch-channel))))

