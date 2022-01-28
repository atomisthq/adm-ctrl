(ns atomist.adm-ctrl.handler
  (:require [compojure.core :refer [defroutes POST GET]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [compojure.route :as route]
            [atomist.adm-ctrl.core :refer [handle-admission-control-request]]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre])
  (:import [java.security KeyStore]
           [java.io ByteArrayOutputStream FileInputStream]
           [java.util Base64])
  (:gen-class))

(defroutes app
  (GET "/health" _ (constantly {:status 200 :body "ok"}))
  (->
   (POST "/" _ (fn [req] (try 
                           {:status 200 :body (handle-admission-control-request req)}
                           (catch Throwable t
                             {:status 500 :body ""}))))
   (wrap-json-body {:keywords? true :bigdecimals? true})  
   (wrap-json-response))
  (route/not-found "<h1>not found</h1>"))

(defn ->keystore [f password]
  (-> f
      (FileInputStream.)
      (as-> in (let [keystore (KeyStore/getInstance "JKS")] 
                 (.load keystore in (.toCharArray password))
                 keystore))))

;; certs/ca.crt, trust this certificiate on the client side
;; certs/server.crt generated by CA and contains public key
;; certs/server.key private server key
;; test with curl --cacert ca.crt https://policy-controller.atomist.svc:8443/health
;; after updating /etc/hosts to point policy-controller.atomist.svc to point at 127.0.0.1
(defn -main
  [& {:keys [keystore key-password join?]}]
  (try
    (let [key-password (or key-password "atomist")
          keystore (->keystore (io/file (or keystore "/jks/server.pkcs12")) key-password)]
      (run-jetty app {:ssl-port 8443
                      :ssl? true
                      :http? false
                      :keystore keystore
                      :key-password key-password
                      :join? (if (false? join?) false true)}))
    (catch Throwable t
      (timbre/errorf t "failed to start %s - %s" 
                     keystore (->> key-password (map (constantly "x")) (apply str))))))

;; k get secret policy-controller-admission-cert -n atomist -o json | jq -r .data.key | base64 -d > admission.key
;; k get secret policy-controller-admission-cert -n atomist -o json | jq -r .data.cert | base64 -d > admission.crt
;; k get secret policy-controller-admission-cert -n atomist -o json | jq -r .data.ca | base64 -d > ca.crt
;; cat admission.crt ca.crt >> all.crt
;; openssl pkcs12 -export -in all.crt -inkey admission.key -out admission.p12 -password pass:atomist
(comment
  (def keystore-bytes (with-open [in (-> (io/file "admission.p12")
                                         (io/input-stream))
                                  out (ByteArrayOutputStream.)]
                        (io/copy in out)
                        (.toByteArray out)))
  (def keystore-encoded (String. (.encode (Base64/getEncoder) keystore-bytes)))

  (def ks (->keystore (io/file "admission.p12") "atomist"))
  (enumeration-seq (.aliases ks))
  (.getCertificate ks "1")
  
  (def server (-main :key-password "atomist" :keystore "admission.p12" :join? false))
  (.stop server))

