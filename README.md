# adm-ctrl

A [ValidatingWebhookConfiguration](https://kubernetes.io/docs/reference/access-authn-authz/admission-controllers/#validatingadmissionwebhook) which allows integration with Atomist for two purposes:

1) To protect clusters from running vulnerable images. The service will validate images deployed to [enabled namespaces (via annotation)](#enable-image-check-policy) by checking whether Atomist has recorded any failed checks for the image being deployed.
1) To track which images are deployed to different clusters.

The controller listens on `/` for `POST`s from the cluster. For pods being created in namespaces where enforcement has been enabled are checked against a signed policy. For all pods appearing in the cluster (against any namespace, not just those with enforcement enabled) the service will call back to Atomist and record the instance of the workload. This allows tracking of images as they move through the deployment process (from staging to production etc.) and allows the building of an inventory of deployed images in each cluster.

## Installation

### Manual Install

[Use these instructions](./docs/steps.md) to manually install the controller in a cluster.

### Using FluxCD

[Use these instructions](./docs/flux_install.md) to add the controller to an existing flux repository.

