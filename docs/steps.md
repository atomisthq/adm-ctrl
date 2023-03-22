## Install Steps

```bash
$ git clone git@github.com:atomisthq/adm-ctrl 
```

### Create a self-signed certificate and store it on the cluster (as two secrets in the atomist namespace)

Choose a cluster name and set an environment variable `${CLUSTER_NAME}`.

```bash
1. kubectl kustomize resources/k8s/certs | kubectl apply -f -
2. kubectl apply -f resources/k8s/jobs/create.yaml
3. mkdir "resources/k8s/overlays/${CLUSTER_NAME}" && cp resources/templates/kustomization.yaml "resources/k8s/overlays/${CLUSTER_NAME}/kustomization.yaml"
```

### Create endpoint secret

Use whatever secret management tools your team is already using. If you are creating the secret by hand, then you can create an `endpoint.env` file and install it using the command below.

Create the file `resources/k8s/overlays/${CLUSTER_NAME}/endpoint.env` and provide details from the [dso.docker.com](http://dso.atomist.com) integration page.

```bash
apiKey=xxxxx
url=xxxxx
team=xxxxx
```

```bash
4. kubectl create secret generic endpoint -n atomist --from-env-file "resources/k8s/overlays/${CLUSTER_NAME}/endpoint.env"
```

### Start up the controller (controller will not receive requests until the validation webhook is configured)

Edit `resources/k8s/overlays/${CLUSTER_NAME}/kustomization.yaml` and update the cluster name on [line 12](https://github.com/atomisthq/adm-ctrl/blob/main/resources/templates/kustomization.yaml#L12).  Also change the `newTag` image attribute to be `v0.0.2` ([line 15](https://github.com/atomisthq/adm-ctrl/blob/main/resources/templates/kustomization.yaml#L15))

```bash
5. kubectl create configmap nginxconfigmap -n atomist --from-file=resources/nginx/nginx.conf
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

## Certificate Management

A self-signed certificate is created in the first instruction above. This certificate will not expire for 100 years.  However, an operator can generate new certificates or plug in a different tool for certificate management.

To regenerate the certificates, use the following procedure.

```
kubectl delete secret policy-controller-admission-cert -n atomist
kubectl apply -f resources/k8s/jobs/create.yaml
kubectl apply -f resources/k8s/jobs/patch.yaml
```

## Images used

* [Certificate Creation/Patch jobs](https://hub.docker.com/layers/jettech/kube-webhook-certgen/v1.5.2/images/sha256-d22f8b5ed10fb78d76a21130605cb18b9fc08918d3b09a70b4bf312ba6c750de?context=explore)
    * most recent tag is already 2 years old
* [Controller](https://hub.docker.com/repository/docker/vonwig/adm-ctrl/general)

