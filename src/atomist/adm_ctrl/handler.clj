(ns atomist.adm-ctrl.handler
  (:require [atomist.adm-ctrl.core :refer [handle-admission-control-request k8s-client]]
            [atomist.namespaces :as atm-namespaces]
            [compojure.core :refer [defroutes POST GET]]
            [compojure.route :as route]
            [org.httpkit.server :as server]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [taoensso.timbre :as timbre])
  (:gen-class))

(set! *warn-on-reflection* true)

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

;; certs/ca.crt, trust this certificiate on the client side
;; certs/server.crt generated by CA and contains public key
;; certs/server.key private server key
;; test with curl --cacert ca.crt https://policy-controller.atomist.svc:8443/health
;; after updating /etc/hosts to point policy-controller.atomist.svc to point at 127.0.0.1
(defn -main
  [& args]
  (try
    (atm-namespaces/start-watching (k8s-client))
    (server/run-server #'app {:port 3000})
    (catch Throwable t
      (timbre/errorf t "failed to start"))))

(comment
  (def server (-main))
  (server :timeout 100))

