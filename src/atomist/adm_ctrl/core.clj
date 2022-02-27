(ns atomist.adm-ctrl.core
  (:require [clojure.core.async :as async]
            [cheshire.core :as json]
            [atomist.k8s :as k8s]
            [clj-http.client :as client]
            [atomist.logging]
            [taoensso.timbre :as timbre
             :refer [info  warn infof]]
            [clojure.string :as str]))

(def url (System/getenv "ATOMIST_URL"))
(def api-key (System/getenv "ATOMIST_APIKEY"))
(def cluster-name (System/getenv "CLUSTER_NAME"))

(defn atomist-call [req]
  (let [response (client/post url {:headers {"Authorization" (format "Bearer %s" api-key)}
                                   :content-type :json
                                   :throw-exceptions false
                                   :body (json/generate-string req)})]
    (info (format "status %s, %s" (:status response) (-> response :body)))
    response))

(def k8s (atom nil))

(defn k8s-client []
  (when (nil? @k8s)
    (swap! k8s (fn [& args]
                 (info "... initializing k8s")
                 (if (System/getenv "LOCAL")
                   (k8s/build-http-kubectl-client)
                   (k8s/build-http-cluster-client)))))
  @k8s)

(defn log-pod-images 
  [pod-object]
  (let [{{obj-ns :namespace obj-n :name} :metadata spec :spec {container-statuses :containerStatuses} :status kind :kind} pod-object
        {{on-node :nodeName} :spec} (k8s/get-pod (k8s-client) obj-ns obj-n)
        {{{:keys [operatingSystem architecture]} :nodeInfo} :status} (k8s/get-node (k8s-client) on-node)]

    ;; at this point, do we have container ids?  If so, we can probably just add the container-id to the outgoing
    ;; message
    (doseq [{:keys [ready started containerID imageID name]} container-statuses]
      (infof "status: %-20s%s\t%-80s%-20s%-20s" name imageID containerID ready started))
    
    ;; TODO support ephemeral containers
    (doseq [container (concat (:containers spec) (:initContainers spec))]
      (atomist-call {:image {:url (:image container)}
                     :environment {:name (if (str/starts-with? cluster-name "atomist") 
                                           obj-ns
                                           (str cluster-name "/" obj-ns))}
                     :platform {:os operatingSystem
                                :architecture architecture}}))
    (infof "logged pod %s/%s" obj-ns obj-n)))

(defn atomist->tap 
  "wait for a pod to be visible - try every 5 seconds for up to 30 seconds"
  [{{obj-ns :namespace obj-n :name} :metadata :as object}]
  (infof "Pod %s/%s needs to be discovered and logged" obj-ns obj-n)
  (async/go-loop
   [counter 0]
    (when (and
           (not
            (try
              (log-pod-images object)
              true
              (catch Throwable t
                (let [{{obj-ns :namespace obj-n :name} :metadata} object]
                  (warn (format "trial: %d: unable to log pod %s/%s - %s" counter obj-ns obj-n (str t)))
                  false))))
           (< counter 6))
      (async/<! (async/timeout 5000))
      (recur (inc counter)))))

(defn admission-review
  [uid response]
  {:apiVersion "admission.k8s.io/v1"
   :kind "AdmissionReview"
   :response (merge
              response
              {:uid uid})})

(def create-review (comp (fn [review] (infof "review: %s" review) review) admission-review))

;; TODO - Objects in our production namespaces must be marked as being ready or they'll be rejected
(defn decision 
  ""
  [_]
  ;; only passing in the resource that will be changed
  {:allowed true})

(defn handle-admission-control-request
  ""
  [{:keys [body] :as req}]
  ;; request object could be nil if something is being deleted
  (let [{{:keys [kind] {o-ns :namespace o-n :name} :metadata :as object} :object 
         request-kind :kind
         request-resource :resource
         dry-run :dryRun 
         uid :uid 
         operation :operation} (-> body :request)]
    (infof "kind: %-50s resource: %-50s" 
           (format "%s/%s@%s" (:group request-kind) (:kind request-kind) (:version request-kind))
           (format "%s/%s@%s" (:group request-resource) (:resource request-resource) (:version request-resource)))
    (cond
      (and 
        (#{"Deployment" "Job" "DaemonSet" "ReplicaSet" "StatefulSet"} kind)
        (= "production" o-n))
      (do
        (if dry-run
          (infof "%s dry run for uid %s - %s/%s" kind uid o-ns o-n)
          (infof "%s admission request for uid %s - %s/%s" kind uid o-ns o-n))
        (create-review uid {:allowed true}))
      (#{"Pod"} kind)
      (do
        (if dry-run
          (infof "%s dry run for uid %s - %s/%s" kind uid o-ns o-n)
          (infof "%s admission request for uid %s - %s/%s" kind uid o-ns o-n))
        (let [resource-decision (decision object)]
          (infof "reviewing operation %s, of kind %s on %s/%s -> decision: %s" operation kind o-ns o-n resource-decision)
          (when (and (not dry-run) (= "Pod" kind)) 
            (atomist->tap object))
          (create-review uid resource-decision)))
      :else
        (create-review uid {:allowed true}))))

