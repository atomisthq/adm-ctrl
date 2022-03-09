(ns atomist.adm-ctrl.core
  (:require [clojure.core.async :as async]
            [cheshire.core :as json]
            [atomist.k8s :as k8s]
            [clj-http.client :as client]
            [atomist.logging]
            [atomist.namespaces :refer [namespaces-to-enforce]]
            [clojure.java.io :as io]
            [clojure.edn]
            [taoensso.timbre :as timbre
             :refer [info warnf warn infof]]
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


;; has to be :check.conclusion/ready
;; empty result set means that the image is not even checked yet - must reject
(def admission-query
  '[:find
    ;?status is :image-not-found or :image-not-linked or :no-policies-configured or :success or :failure
    ;?stream is the string
    ;?failed-checks ?successful-checks are vectors of entity ids
    ;?missing-checks is a vector of strings
    ?status-keyword ?stream-name ?failed-checks ?successful-checks ?missing-check-names

    :in $ $before-db % ?ctx [?host ?repository ?tag ?stream-name]
    :where
    [?image-repository :docker.repository/repository ?repository]
    [?image-repository :docker.repository/host ?host]
    [?image-tag :docker.tag/repository ?image-repository]
    [?image-tag :docker.tag/name ?tag]
    [?image-tag :docker.tag/image ?image]
    [?image :docker.image/digest ?digest]
    (image-checks ?digest ?stream-name ?status-keyword ?successful-checks ?failed-checks ?missing-check-names)])

(defn admit? [results]
  (info "admit? results " results)
  (let [[[status stream failed success missing]] results]
    (infof "admission conclusion %s on stream %s %d/%d (%d missing)"
           status
           stream
           (count success)
           (+ (count success) (count failed) (count missing))
           (count missing))
    (= :success status)))

(defn parse-image [image]
  (let [[_ host repository tag] (re-find #"(.*?)/(.*):(.*)" image)]
    [host repository tag]))

;; https://dso.atomist.com/AZQP0824Q/overview/images/gcr.io/personalsdm-216019/altjserver/digests/sha256:a9af38ee827fd4b7350316135200c44c16b5506de374e615645550accb8e2325
;; https://github.com/vonwig/altjservice/commit/2a13fdf2c43de059f173d12523e26f9d0a298165
;; (28 minutes ago)
;; gcr.io/personalsdm-216019/altjserver:v148
;; https://dso.atomist.com/AZQP0824Q/overview/images/gcr.io/personalsdm-216019/altjserver/digests/sha256%3Aa9cad9faaec202f722a2b841ab8ed5a5c46157f4c993a7afec137c68e87f5ea7
;; https://github.com/vonwig/altjservice/commit/2a13fdf2c43de059f173d12523e26f9d0a298165
;; https://dso.atomist.com/AZQP0824Q/overview/images/gcr.io/personalsdm-216019/altjserver?platform=linux%2Famd64

(comment
  (parse-image "gcr.io/personalsdm-216019/altjserver:v115"))

(defn atomist-admission-control
  "query atomist to check whether image has passed policy checks

    returns channel - channel will provide one value and then close (value true if image should be admitted)"
  [image o-ns]
  (async/go
    (infof "check %s in %s using query %s" image workspace-id (apply str (take 40 (str admission-query))))
    (let [{:keys [status body headers]}
          (client/post (format "https://%s/datalog/team/%s"
                               "api.atomist.com" workspace-id)
                       {:headers {"Authorization" (format "Bearer %s" api-key)
                                  "Accept-Encoding" "gzip"
                                  "Content-Type" "application/edn"}
                        :throw false
                        :body (pr-str {:query (pr-str admission-query)
                                       :args (conj (parse-image image) (format "%s/%s" cluster-name o-ns))})})
          admitted? (and
                     (= 200 status) 
                     (-> body
                         (clojure.edn/read-string)
                         (admit?)))
          message (format "ERROR - %s %s, %s\n" status body headers)]
      (when (not admitted?)
        (warn message))
      [admitted? (format "%s - %s" image message)])))

(defn decision
  "check atomist admission control for selected namespaces only"
  [{{o-ns :namespace} :metadata
    {:keys [containers]} :spec
    :as object}]
  (async/go
    (if (@namespaces-to-enforce o-ns)
      (do
        ;; TODO support initContainers as well
        (infof "check controls on %s" (->> containers (map :image) (str/join ",")))
        (async/<! (->> (for [{:keys [image]} containers]
                         (atomist-admission-control image o-ns))
                       (async/merge)
                       (async/reduce
                        (fn [{:keys [allowed status]
                              :as response} [b s]]
                          (-> response
                              (update :allowed (fn [agg] (and agg b)))
                              ((fn [{allowed :allowed :as response}] (assoc-in response [:status :code] (if allowed 200 400))))
                              (update-in [:status :message] (fn [agg] (format "%s\n%s" agg s)))))
                        {:allowed true
                         :status {:code 200
                                  :message ""}}))))
      {:allowed true
       :status {:code 200
                :message (format "namespace %s is not protected" o-ns)}})))

(defn handle-admission-control-request
  "Check Pods - everything else is permitted by default
   log non-Pod admission requests"
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

