## Steps
```bash $ git clone -b custzero git@github.com:atomisthq/adm-ctrl ```

### Create a self-signed certificate and store it on the cluster (as two secrets in the atomist namespace)

Choose a cluster name and set an environment variable `${CLUSTER_NAME}`.

```bash
1. kubectl kustomize resources/k8s/certs | kubectl apply -f -
2. kubectl apply -f resources/k8s/jobs/create.yaml
3. mkdir "resources/k8s/overlays/${CLUSTER_NAME}" && cp resources/templates/kustomization.yaml "resources/k8s/overlays/${CLUSTER_NAME}/kustomization.yaml"
```

### Create endpoint secret

Create the file `resources/k8s/overlays/${CLUSTER_NAME}/endpoint.env` and provide details from the [dso.docker.com](http://dso.atomist.com) integration page.

```bash
apiKey=xxxxx
url=xxxxx
team=xxxxx
```

```bash
4. kubectl create secret generic endpoint -n atomist --from-env-file "resources/k8s/overlays/${CLUSTER_NAME}/endpoint.env"
```

### Start up the controller (will not be used yet as there’s no validation webhook configuration)

Edit `resources/k8s/overlays/${CLUSTER_NAME}/kustomization.yaml` and update the cluster name on [line 12](https://github.com/atomisthq/adm-ctrl/blob/custzero/resources/templates/kustomization.yaml#L12).  Also change the `newTag` image attribute to be `latest`([line 15](https://github.com/atomisthq/adm-ctrl/blob/custzero/resources/templates/kustomization.yaml#L15))

```bash
5. kubectl create configmap nginxconfigmap -n atomist --from-file=nginx/nginx.conf
6. kubectl kustomize "resources/k8s/overlays/${CLUSTER_NAME}" | kubectl apply -f -
```

### Add the validating webhook.

This patch job (step 9) updates the validating webhook configuration to trust the self-signed certificate created in step 2.

```bash
7. kubectl label namespace kube-system policy-controller.atomist.com/webhook=ignore
8. kubectl apply -f resources/k8s/admission/admission.yaml
9. kubectl apply -f resources/k8s/jobs/patch.yaml
```

Until a namespace is annotated, no policy will be enforced.  However, images used in a namespace’s pods will be tracked on [dso.docker.com](http://dso.docker.com) .

```bash
# enable policy on a namespace (eg production)
kubectl annotate namespace production policy-controller.atomist.com/policy=enabled
# disable policy on a namespace (eg production)
kubectl annotate namespace production policy-controller.atomist.com/policy-
```
