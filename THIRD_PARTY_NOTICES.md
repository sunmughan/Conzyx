# Third-Party Notices

This repository is a fork of [vxcontrol/pentagi](https://github.com/vxcontrol/pentagi),
which is licensed under the MIT License (see LICENSE in this repository).

- Upstream project: vxcontrol/pentagi
- Upstream license: MIT
- Fork base commit: 843dfeb4 ("docs: the README says what PentAGI is, who it is for, how it differs and how to install it")

Changes made in this fork:

- Added `backend/pkg/proot/`, a proot-native implementation of the
  `docker.DockerClient` interface (`backend/pkg/docker/client.go`), so the
  backend can execute agent commands without a Docker daemon. Zero CGO.
- `backend/cmd/pentagi/main.go`: executor selection via the `PENTAGI_EXECUTOR`
  environment variable (`PENTAGI_EXECUTOR=proot` wires the proot executor;
  `PROOT_ROOTFS` optionally overrides the guest root). Default behavior
  (Docker daemon) is unchanged.
