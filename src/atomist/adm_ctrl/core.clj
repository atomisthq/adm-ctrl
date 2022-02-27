(ns atomist.adm-ctrl.core
  (:require [clojure.core.async :as async]
            [cheshire.core :as json]
            [atomist.k8s :as k8s]
            [clj-http.client :as client]
            [atomist.logging]
            [clojure.java.io :as io]
            [taoensso.timbre :as timbre
             :refer [info  warn infof]]
            [clojure.string :as str]))

(def url (System/getenv "ATOMIST_URL"))
(def api-key (System/getenv "ATOMIST_APIKEY"))
(def cluster-name (System/getenv "CLUSTER_NAME"))
(def workspace-id (System/getenv "ATOMIST_WORKSPACE"))

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
  (let [{{obj-ns :namespace obj-n :name} :metadata spec :spec} pod-object
        {{on-node :nodeName} :spec {container-statuses :containerStatuses} :status} (k8s/get-pod (k8s-client) obj-ns obj-n)
        {{{:keys [operatingSystem architecture]} :nodeInfo} :status} (k8s/get-node (k8s-client) on-node)
        spec-containers (concat (:containers spec) (:initContainers spec))]

    ;; log any statuses for debugging
    (doseq [{:keys [ready started containerID imageID name image]} container-statuses]
      (infof "status: %-20s %s %s\t%-80s%-20s%-20s" name image imageID containerID ready started))

    ;; there should be a status for each spec-container before we can raise the event
    (if (not (= (count spec-containers) (count container-statuses)))
      (throw (ex-info "status not ready" {:status-count (count container-statuses)}))
      ;; TODO support ephemeral containers
      (doseq [{:keys [image]} spec-containers
              :let [container-id (->> container-statuses
                                      (filter #(= image (:image %)))
                                      first
                                      :containerID)]]
        (infof "log image %s with containerID %s in pod %s" image container-id obj-n)
        (atomist-call {:image {:url image
                               :containerID container-id
                               :pod obj-n}
                       :environment {:name (if (str/starts-with? cluster-name "atomist")
                                             obj-ns
                                             (str cluster-name "/" obj-ns))}
                       :platform {:os operatingSystem
                                  :architecture architecture}})))

    (infof "logged pod %s/%s" obj-ns obj-n)))

(defn atomist->tap 
  "wait for a pod to be visible - try every 5 seconds for up to a minute"
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
           (< counter 12))
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

(def namespaces-to-enforce #{"production"})

(defn atomist-admission-control 
  "channel should return true if image should be admitted"
  [image]
  (async/go
    (infof "check %s in %s using query %s" image workspace-id (apply str (take 40 (slurp (io/resource "admission_query.edn")))))
    true))

(defn decision
  "check atomist admission control for some namespaces"
  [{{o-ns :namespace} :metadata
    {:keys [containers]} :spec
    :as object}]
  (async/go
    (if (namespaces-to-enforce o-ns)
      (do
        ;; TODO support initContainers as well
        (infof "check controls on %s" (->> containers (map :image) (str/join ",")))
        {:allowed (async/<! (->> (for [{:keys [image]} containers]
                                   (atomist-admission-control image))
                                 (async/merge)
                                 (async/reduce (fn [d v] (and d v)) true)))})
      {:allowed true})))

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
    (infof "%-12s %-50s kind: %-50s resource: %-50s"
           operation
           (format "%s/%s" o-ns o-n)
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
        (let [resource-decision (async/<!! (decision object))]
          (infof "reviewing operation %s, of kind %s on %s/%s -> decision: %s" operation kind o-ns o-n resource-decision)
          (when (and (not dry-run) (= "Pod" kind))
            (atomist->tap object))
          (create-review uid resource-decision)))
      :else
      (create-review uid {:allowed true}))))

