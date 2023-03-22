### Using Flux

To use [Flux](https://fluxcd.io) to install the controller we'll create a `GitRepository` source which points to the GitHub repo containing the various Kubernetes resources that the controller will need and put that somewhere Flux can find it.

```yaml
apiVersion: source.toolkit.fluxcd.io/v1beta1
kind: GitRepository
metadata:
  name: adm-ctrl
  namespace: flux-system
spec:
  interval: 30s
  ref:
    branch: main
  url: https://github.com/atomisthq/adm-ctrl
```

Using that source we can create a `Kustomization` which will allow us to pull in the resources (from the `resources/k8s/controller` directory of the repo) required by the controller. We'll want to customize the `CLUSTER_NAME` environment variable in the controller deployment so we can use kustomize to do that. This file will also be the place where we specify which controller image we are running.

```yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1beta2
kind: Kustomization
metadata:
  name: adm-ctrl
  namespace: flux-system
spec:
  targetNamespace: atomist
  interval: 10m0s
  decryption:
    provider: sops
    secretRef:
      name: sops-gpg
  sourceRef:
    kind: GitRepository
    name: adm-ctrl
  path: ./resources/k8s/controller
  prune: true
  patches:
    - patch: |-
        apiVersion: apps/v1
        kind: Deployment
        metadata:
          name: policy-controller
          namespace: atomist
        spec:
          template:
            spec:
              containers:
                - name: controller
                  env:
                    - name: CLUSTER_NAME
                      value: production
      target:
        kind: Deployment
        name: policy-controller
  images:
  - newTag: v4-5-ga51c3ee
    name: atomist/adm-ctrl
```

For this example we're going to encode the remaining three environment variables listed above (`ATOMIST_URL`, `ATOMIST_WORKSPACE`, `ATOMIST_APIKEY`) into a single secret using [sops](https://fluxcd.io/docs/guides/mozilla-sops/). Once that secret file has been created somewhere in the repo we'll need a `kustomization.yaml` alongside it to let Flux know about it.

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - secret.yaml
```

We can then create another `Kustomization` file which will pull in all resources in the directory we put the lat file. For this example that happens to be in `./adm-ctrl/production` but it can, of course, be anywhere relevant to the layout of your Flux repo.

```yaml
apiVersion: kustomize.toolkit.fluxcd.io/v1beta2
kind: Kustomization
metadata:
  name: adm-ctrl-resources
  namespace: flux-system
spec:
  interval: 10m0s
  decryption:
    provider: sops
    secretRef:
      name: sops-gpg
  sourceRef:
    kind: GitRepository
    name: flux-system
  path: ./adm-ctrl/production
  prune: true
```

Now you can commit these changes to your Flux repo and have the various controllers pick up the changes and create all the necessary resources.

