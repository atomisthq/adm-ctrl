## Installation

### Prerequisites

1.  [openssl v3][openssl] (tested with `3.0.1`)
2.  [kustomize][kustomize] (tested with `v4.4.1`)
3.  [kubectl][kubectl] (tested with `v1.21.1`)
4.  kubectl must be authenticated and the current context should be set to the cluster that you will be updating

[openssl]: https://www.openssl.org/docs/man3.0/man7/migration_guide.html
[kustomize]: https://kubectl.docs.kubernetes.io/installation/kustomize/
[kubectl]: https://kubectl.docs.kubernetes.io/installation/kubectl/

### 1. Fork this repo

[Fork this repo](https://github.com/atomisthq/adm-ctrl/fork).

This repo contains a set of base kubernetes that you'll adapt using `kustomize`.

### 2. Api Key and Api Endpoint URL

Create an overlay for your cluster.  Choose a cluster name and then create a new overlay directory.

```
CLUSTER_NAME=replacethis
mkdir -p resources/k8s/overlays/${CLUSTER_NAME}
```

Create a file named `resources/k8s/overlays/${CLUSTER_NAME}/endpoint.env`.

The file will start out like this.

```properties
apiKey=<replace this>
url=<replace this>
```

The `apiKey` and `url` should be filled in with your workspace's values.  Find these in the [atomist app](https://dso.atomist.com/r/auth/integrations) and replace them in the file.

### 3. Create ssl certs for your Admission Controller

The communication between the api-server and the admission controller will be over HTTPS.  This will be configured by running 3 kubernetes jobs in the cluster.

1.  policy-controller-cert-create job - this job will create SSL certs and store them in a secret named `policy-controller-admission-cert` in the atomist namespace
2.  policy-controller-cert-path job - this will patch the admission controller webhook with the ca cert (so that the api-server will trust the new policy-controller)
3.  keystore-create job - this will read the SSL certs created by the policy-controller-cert-create job and create a keystore for the policy-controller HTTP server.  The keystore is also stored in a secret named `keystore` in the atomist namespace.

You can do steps 1 and 3 now.

```
# creates roles and service account for running jobs
kustomize build resources/k8s/certs | kubectl apply -f -
kubectl apply -f resources/k8s/jobs/create.yaml
kubectl apply -f resources/k8s/jobs/keystore_secret.yaml
```

### 4. Update Kubernetes cluster

This procedure will create a service account, a cluster role binding, two secrets, a service, and a deployment.  All of these will be created in a new namespaced called `atomist`.

![controller diagram](./docs/controller.png)

Use the same overlay that you created above (`resources/k8s/overlays/${CLUSTER_NAME}`).  Copy in a template kustomization.yaml file.

```
cp resources/templates/default_controller.yaml resources/k8s/overlays/${CLUSTER_NAME}/kustomization.yaml
```

This kustomization file will permit you to change the `CLUSTER_NAME` environment variable.  
In the initial copy of the file, the value will be `"default"`, but it should be changed to the name of your cluster.  This change is made to the final line in your new kustomization file.

```yaml
resources:
  - ../../controller
secretGenerator:
- envs:
  - endpoint.env
  name: endpoint
patchesJson6902:
- target:
    group: apps
    version: v1
    kind: Deployment
    name: policy-controller
  patch: |-
    - op: replace
      path: /spec/template/spec/containers/0/env/3/value
      value: "default"
```

Deploy the admission controller into the the current kubernetes context using the command shown below.

```bash
kustomize build resources/k8s/overlays/sandbox | kubectl apply -f -
```

At this point, the admission controller will be running but the cluster will not be routing any admission control requests to it.  Create a configuration to start sending admission control requests to the controller using the following script.

```bash
kustomize build resources/k8s/admission | kubectl apply -f -
# finally, patch the admission webhook with the ca certificate from earlier
kubectl apply -f resources/k8s/jobs/patch.yaml
```

[dynamic-admission-control]: https://kubernetes.io/docs/reference/access-authn-authz/extensible-admission-controllers/

