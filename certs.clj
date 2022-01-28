(require '[babashka.process :as p])
(require '[babashka.curl :as curl])
(require '[cheshire.core :as cheshire])
(require '[babashka.fs :as fs])
(import '[java.nio.file Files])

(def secret-name "policy-controller-admission-cert")
(def k-ns "atomist")
(def target-secret-name "keystore")
(def k8-client {:url "https://kubernetes.docker.internal:6443" #_"https://kubernetes.default.svc"
                :token (slurp "/var/run/secrets/kubernetes.io/serviceaccount/token")
                :ca-cert "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
                :insecure? true})

(defn ->obj 
  [s & {:keys [keywordize-keys]}]
  (cheshire/parse-string s keywordize-keys))

(defn ->str 
  [obj & {:keys [keyword-fn] :or {keyword-fn name}}]
  (cheshire/generate-string obj {:key-fn keyword-fn}))

(defn decode-string [b64]
  (let [decoder (java.util.Base64/getDecoder)
        resultBytes (.decode decoder b64)]
    (String. resultBytes)))

(defn encode-bytes [bytes]
  (let [encoder (java.util.Base64/getEncoder)
        resultBytes (.encode encoder bytes)]
    (String. resultBytes)))

(defn get-secret [k-ns secret-name]
  (let [response (curl/get (format "%s/api/v1/namespaces/%s/secrets/%s" (:url k8-client) k-ns secret-name)
                  {:headers {"Authorization" (format "Bearer %s" (-> k8-client :token))}
                   :debug true
                   :raw-args ["--insecure"]})]
    (if (= 200 (:status response))
      (-> response :body (->obj :keywordize-keys true))
      (println "failed to get secret " (:status response)))))

(defn delete-secret [k-ns secret-name]
  (curl/delete (format "%s/api/v1/namespaces/%s/secrets/%s" (:url k8-client) k-ns secret-name)
               {:headers {"Authorization" (format "Bearer %s" (-> k8-client :token))}
                :raw-args ["--insecure"]
                :throw false}))

(defn create-secret [k-ns secret-name file]
  (let [response (curl/post (format "%s/api/v1/namespaces/%s/secrets" (:url k8-client) k-ns)
                           {:headers {"Authorization" (format "Bearer %s" (-> k8-client :token))
                                      "Content-Type" "application/json"}
                            :body (->str {:apiVersion "v1"
                                          :kind "Secret"
                                          :metadata {:name secret-name
                                                     :namespace k-ns
                                                     :labels {"app.kubernetes.io/name" "atomist"}}
                                          :data {"server.pkcs12" (encode-bytes (Files/readAllBytes (.toPath file)))}
                                          :type "Opaque"})
                            :raw-args ["--insecure"]
                            :throw false})]
    (if (= 201 (:status response))
      (println (format "successfully created pkcs keystore at %s/%s" k-ns secret-name))
      (println (format "failed to create secret %s/%s - status %s" k-ns secret-name (:status response))))))

(defn run []
  (when-let [{{:keys [key cert ca]} :data} (get-secret k-ns secret-name)]
    (spit "all.crt" (str (decode-string cert) (decode-string ca)))
    (spit "admission.key" (decode-string key))
    (-> (p/process "openssl pkcs12 -export -in all.crt -inkey admission.key -out admission.p12 -password pass:atomist")
        (deref))
    (delete-secret k-ns target-secret-name)
    (create-secret k-ns target-secret-name (fs/file "admission.p12"))))

(run)
