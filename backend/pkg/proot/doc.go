// Package proot implements PentAGI's docker.DockerClient interface
// (backend/pkg/docker/client.go) on top of proot instead of a Docker daemon.
//
// It is the phone-first execution backend for PentAGI: where the Docker
// implementation talks to a daemon over the moby API, this package runs every
// agent command inside its own proot invocation and tracks processes in a
// per-flow session registry.
//
// Concept mapping:
//
//	Docker concept        Proot-executor equivalent
//	--------------        ------------------------
//	image                 the proot rootfs (GetDefaultImage returns "proot");
//	                      no pull, the toolset is provisioned by the installer
//	container             a session record: {flowID, work dir, tracked PIDs};
//	                      there is no daemon-side object
//	exec                  one proot invocation per command:
//	                      `proot -r <root> -b <workDir>:/work -w <cwd> -0 <argv>`;
//	                      stdout+stderr merged into one stream (TTY and
//	                      non-TTY callers both read it raw). On the phone the
//	                      backend already runs inside proot, so <root> is "/"
//	                      (nested proot); the per-exec bind gives each flow
//	                      its own /work, like the docker bind mount.
//	volumes / binds       the flow work dir, mapped from the container path /work
//	ports                 none: tools bind to localhost inside the shared
//	                      network namespace (proot default); DOCKER_PORTS_BASE
//	                      published ports have no equivalent
//	kill tracking         direct PID tracking; the oom_score_adj sweep used by
//	                      the Docker client is deliberately NOT used because
//	                      proot passes through the host /proc
//
// Path confinement: every path the agent can touch is gated to the flow's
// work dir ($DATA_DIR/flow-<id>/). See gate.go.
//
// Security caveat (stated plainly): proot is NOT a Docker-grade sandbox.
// There are no namespaces, no cgroups, no capability drops here; commands run
// as the backend's OS user with the host /proc visible. The gate stops
// accidental and naive escapes, not a determined adversary with arbitrary
// shell. Agent output must be treated accordingly.
package proot
