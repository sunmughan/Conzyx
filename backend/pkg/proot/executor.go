package proot

import (
	"archive/tar"
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"pentagi/pkg/database"
	"pentagi/pkg/docker"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"
)

// DefaultImage is the sentinel returned by GetDefaultImage. It replaces the
// docker image name: the "image" is the proot rootfs provisioned by the installer.
const DefaultImage = "proot"

// maxListEntries mirrors the docker client's listing cap.
const maxListEntries = 10000

var _ docker.DockerClient = (*Executor)(nil)

// execRecord tracks one ContainerExecCreate/Attach lifecycle.
type execRecord struct {
	id       string
	argv     []string
	env      []string
	workDir  string // host dir
	started  bool
	attached bool
	cmd      *exec.Cmd
	proc     *os.Process
	done     chan struct{}
	mu       sync.Mutex
	running  bool
	exited   bool
	code     int
}

// session is the proot equivalent of a container: one per flow.
type session struct {
	containerName string
	localID       string
	flowID        int64
	dbID          int64
	workDir       string // host path of the flow work dir (canonicalized)
	gate          *gate
	status        database.ContainerStatus
	mu            sync.Mutex
	execs         map[string]*execRecord
}

// Executor implements docker.DockerClient using proot / direct process
// execution instead of a Docker daemon.
type Executor struct {
	db        database.Querier // may be nil: DB bookkeeping is then skipped
	dataDir   string
	prootBin  string // proot binary used to wrap every command
	prootRoot string // guest root for the per-exec proot invocation
	mu        sync.Mutex
	byName    map[string]*session
	byLocalID map[string]*session
	execSeq   atomic.Int64
	sessSeq   atomic.Int64
}

// New creates an Executor. dataDir is the PentAGI data dir (cfg.DataDir);
// per-flow work dirs are created under it as flow-<id>/, exactly like the
// docker client. db may be nil for standalone use (bookkeeping skipped).
//
// prootRoot is the guest root used for every command: each exec is wrapped
// as `proot -r <prootRoot> -b <workDir>:/work -w <guestWD> -0 <argv...>`,
// giving every flow its own /work bind. In production on the phone the
// backend itself already runs inside proot, so prootRoot is "/" (nested
// proot); for validation on a workstation it is the Ubuntu rootfs path.
// Direct (unwrapped) spawning is intentionally not offered: without the
// per-exec bind, container paths like /work/... would not resolve.
func New(dataDir string, db database.Querier, prootRoot string) (*Executor, error) {
	abs, err := filepath.Abs(dataDir)
	if err != nil {
		return nil, fmt.Errorf("resolving data dir: %w", err)
	}
	if err := os.MkdirAll(abs, 0o755); err != nil {
		return nil, fmt.Errorf("creating data dir: %w", err)
	}
	if prootRoot == "" {
		prootRoot = "/"
	}
	prootBin, err := exec.LookPath("proot")
	if err != nil {
		return nil, fmt.Errorf("proot not found in PATH: %w", err)
	}
	if prootRoot != "/" {
		if _, err := os.Stat(filepath.Join(prootRoot, "bin", "sh")); err != nil {
			return nil, fmt.Errorf("proot rootfs %q has no bin/sh: %w", prootRoot, err)
		}
	}
	return &Executor{
		db:        db,
		dataDir:   abs,
		prootBin:  prootBin,
		prootRoot: prootRoot,
		byName:    make(map[string]*session),
		byLocalID: make(map[string]*session),
	}, nil
}

// lookup finds a session by container name or by local ID, mirroring how
// callers mix PrimaryTerminalName and container.LocalID.
func (e *Executor) lookup(id string) (*session, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if s, ok := e.byName[id]; ok {
		return s, nil
	}
	if s, ok := e.byLocalID[id]; ok {
		return s, nil
	}
	return nil, fmt.Errorf("no such proot session: %q", id)
}

func (e *Executor) findExec(execID string) (*session, *execRecord, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	for _, s := range e.byName {
		s.mu.Lock()
		rec, ok := s.execs[execID]
		s.mu.Unlock()
		if ok {
			return s, rec, nil
		}
	}
	return nil, nil, fmt.Errorf("no such exec: %q", execID)
}

// RunContainer creates the per-flow session: work dir on disk, DB bookkeeping
// mirroring the docker client, and a registry entry. There is no daemon call
// and no image pull; the rootfs is the image.
func (e *Executor) RunContainer(
	ctx context.Context,
	containerName string,
	containerType database.ContainerType,
	flowID int64,
	config *container.Config,
	hostConfig *container.HostConfig,
) (database.Container, error) {
	if config == nil {
		return database.Container{}, fmt.Errorf("no config found for container %s", containerName)
	}
	workDir := filepath.Join(e.dataDir, fmt.Sprintf("flow-%d", flowID))
	if err := os.MkdirAll(workDir, 0o755); err != nil {
		return database.Container{}, fmt.Errorf("failed to create work directory: %w", err)
	}
	gt, err := newGate(workDir)
	if err != nil {
		return database.Container{}, err
	}

	seq := e.sessSeq.Add(1)
	localID := fmt.Sprintf("proot-%d-%d", flowID, seq)

	var dbContainer database.Container
	if e.db != nil {
		dbContainer, err = e.db.CreateContainer(ctx, database.CreateContainerParams{
			Type:     containerType,
			Name:     containerName,
			Image:    DefaultImage,
			Status:   database.ContainerStatusStarting,
			FlowID:   flowID,
			LocalID:  database.StringToNullString(localID),
			LocalDir: database.StringToNullString(workDir),
		})
		if err != nil {
			return database.Container{}, fmt.Errorf("failed to create container in database: %w", err)
		}
		dbContainer, err = e.db.UpdateContainerStatusLocalID(ctx, database.UpdateContainerStatusLocalIDParams{
			Status:  database.ContainerStatusRunning,
			LocalID: database.StringToNullString(localID),
			ID:      dbContainer.ID,
		})
		if err != nil {
			return database.Container{}, fmt.Errorf("failed to mark container running in database: %w", err)
		}
	} else {
		dbContainer = database.Container{
			ID:       seq,
			Type:     containerType,
			Name:     containerName,
			Image:    DefaultImage,
			Status:   database.ContainerStatusRunning,
			FlowID:   flowID,
			LocalID:  database.StringToNullString(localID),
			LocalDir: database.StringToNullString(workDir),
		}
	}

	sess := &session{
		containerName: containerName,
		localID:       localID,
		flowID:        flowID,
		dbID:          dbContainer.ID,
		workDir:       gt.root,
		gate:          gt,
		status:        database.ContainerStatusRunning,
		execs:         make(map[string]*execRecord),
	}
	e.mu.Lock()
	e.byName[containerName] = sess
	e.byLocalID[localID] = sess
	e.mu.Unlock()
	return dbContainer, nil
}

// killSessionProcs terminates every tracked process of a session: SIGTERM,
// a short grace period, then SIGKILL. Only PIDs recorded in the session's
// exec table are signalled; there is deliberately no /proc sweep.
func killSessionProcs(sess *session) {
	sess.mu.Lock()
	procs := make([]*os.Process, 0, len(sess.execs))
	for _, rec := range sess.execs {
		rec.mu.Lock()
		if rec.proc != nil && rec.running {
			procs = append(procs, rec.proc)
		}
		rec.mu.Unlock()
	}
	sess.mu.Unlock()
	for _, p := range procs {
		_ = p.Signal(syscall.SIGTERM)
	}
	deadline := time.Now().Add(2 * time.Second)
	for _, p := range procs {
		for time.Now().Before(deadline) {
			if err := p.Signal(syscall.Signal(0)); err != nil {
				break
			}
			time.Sleep(100 * time.Millisecond)
		}
		_ = p.Kill()
	}
}

func (e *Executor) updateDBStatus(ctx context.Context, sess *session, status database.ContainerStatus) error {
	if e.db == nil {
		return nil
	}
	_, err := e.db.UpdateContainerStatus(ctx, database.UpdateContainerStatusParams{
		Status: status,
		ID:     sess.dbID,
	})
	return err
}

// StopContainer kills the session's tracked processes and marks it stopped.
// The work dir is kept, mirroring docker stop semantics.
func (e *Executor) StopContainer(ctx context.Context, containerID string, dbID int64) error {
	sess, err := e.lookup(containerID)
	if err != nil {
		return nil // already gone: docker treats not-found stop as success
	}
	killSessionProcs(sess)
	sess.mu.Lock()
	sess.status = database.ContainerStatusStopped
	sess.mu.Unlock()
	if err := e.updateDBStatus(ctx, sess, database.ContainerStatusStopped); err != nil {
		return fmt.Errorf("database status update failed during container stop: %w", err)
	}
	return nil
}

// RemoveContainer stops the session, drops it from the registry and marks the
// DB row deleted. The work dir is intentionally kept for forensics.
func (e *Executor) RemoveContainer(ctx context.Context, containerID string, dbID int64) error {
	sess, err := e.lookup(containerID)
	if err != nil {
		// already gone; still mark the DB row deleted so it does not go stale
		if e.db != nil {
			_, dbErr := e.db.UpdateContainerStatus(ctx, database.UpdateContainerStatusParams{
				Status: database.ContainerStatusDeleted,
				ID:     dbID,
			})
			return dbErr
		}
		return nil
	}
	killSessionProcs(sess)
	e.mu.Lock()
	delete(e.byName, sess.containerName)
	delete(e.byLocalID, sess.localID)
	e.mu.Unlock()
	if err := e.updateDBStatus(ctx, sess, database.ContainerStatusDeleted); err != nil {
		return fmt.Errorf("failed to update container status to deleted: %w", err)
	}
	return nil
}

// IsContainerRunning reports whether the session exists and is running.
// A removed session is missing, not an error (mirrors the docker client).
func (e *Executor) IsContainerRunning(ctx context.Context, containerID string) (bool, error) {
	sess, err := e.lookup(containerID)
	if err != nil {
		return false, nil
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	return sess.status == database.ContainerStatusRunning, nil
}

// KillFlowCommands kills the session's tracked command processes directly.
// The oom_score_adj sweep of the docker client is not used: under proot the
// host /proc is visible, so a sweep would signal host processes.
func (e *Executor) KillFlowCommands(ctx context.Context, containerID string) error {
	sess, err := e.lookup(containerID)
	if err != nil {
		return nil
	}
	sess.mu.Lock()
	running := sess.status == database.ContainerStatusRunning
	sess.mu.Unlock()
	if !running {
		return nil
	}
	killSessionProcs(sess)
	return nil
}

// ContainerExecCreate allocates an exec record without starting it, mirroring
// docker exec create. Cmd arrives as e.g. ["sh","-c",wrapper,command] and is
// passed through unchanged. WorkingDir is mapped through the path gate,
// defaulting to /work.
func (e *Executor) ContainerExecCreate(
	ctx context.Context,
	container string,
	config client.ExecCreateOptions,
) (client.ExecCreateResult, error) {
	sess, err := e.lookup(container)
	if err != nil {
		return client.ExecCreateResult{}, err
	}
	sess.mu.Lock()
	running := sess.status == database.ContainerStatusRunning
	sess.mu.Unlock()
	if !running {
		return client.ExecCreateResult{}, fmt.Errorf("cannot exec in non-running session %q", container)
	}
	if len(config.Cmd) == 0 {
		return client.ExecCreateResult{}, fmt.Errorf("exec requires a command")
	}
	workDir := config.WorkingDir
	if strings.TrimSpace(workDir) == "" {
		workDir = containerWorkDir
	}
	hostDir, err := sess.gate.mapContainerPath(workDir)
	if err != nil {
		return client.ExecCreateResult{}, fmt.Errorf("invalid exec working dir: %w", err)
	}
	if fi, err := os.Stat(hostDir); err != nil || !fi.IsDir() {
		hostDir = sess.workDir // fall back to the work dir root
	}
	id := fmt.Sprintf("pxexec-%d", e.execSeq.Add(1))
	rec := &execRecord{
		id:      id,
		argv:    append([]string(nil), config.Cmd...),
		env:     append([]string(nil), config.Env...),
		workDir: hostDir,
		done:    make(chan struct{}),
	}
	sess.mu.Lock()
	sess.execs[id] = rec
	if len(sess.execs) > 512 {
		for oldID, old := range sess.execs {
			old.mu.Lock()
			done := old.exited
			old.mu.Unlock()
			if done && oldID != id {
				delete(sess.execs, oldID)
				break
			}
		}
	}
	sess.mu.Unlock()
	return client.ExecCreateResult{ID: id}, nil
}

// pipeConn adapts an io.Pipe to net.Conn so it can back a HijackedResponse.
type pipeConn struct {
	r *io.PipeReader
	w *io.PipeWriter
}

func (c *pipeConn) Read(b []byte) (int, error)         { return c.r.Read(b) }
func (c *pipeConn) Write(b []byte) (int, error)        { return c.w.Write(b) }
func (c *pipeConn) Close() error                       { _ = c.r.Close(); return c.w.Close() }
func (c *pipeConn) LocalAddr() net.Addr                { return nil }
func (c *pipeConn) RemoteAddr() net.Addr               { return nil }
func (c *pipeConn) SetDeadline(t time.Time) error      { return nil }
func (c *pipeConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *pipeConn) SetWriteDeadline(t time.Time) error { return nil }

// ContainerExecAttach starts the exec process and returns a hijacked-style
// response whose Reader yields the merged stdout+stderr stream. Unlike docker,
// the stream is never stdcopy-multiplexed: TTY and non-TTY callers alike read
// it raw (this matches how the current callers consume it). Closing the
// response stops the stream but does NOT kill the process; it stays tracked
// for KillFlowCommands/StopContainer/Cleanup, mirroring docker exec detach
// semantics.
func (e *Executor) ContainerExecAttach(
	ctx context.Context,
	execID string,
	config client.ExecAttachOptions,
) (client.HijackedResponse, error) {
	sess, rec, err := e.findExec(execID)
	if err != nil {
		return client.HijackedResponse{}, err
	}
	rec.mu.Lock()
	if rec.attached {
		rec.mu.Unlock()
		return client.HijackedResponse{}, fmt.Errorf("exec %q already attached", execID)
	}
	rec.attached = true
	rec.mu.Unlock()

	pr, pw := io.Pipe()
	conn := &pipeConn{r: pr, w: pw}

	argv := rec.argv
	// The process outlives the caller's context on purpose: a timed-out caller
	// stops reading, the command keeps running like a docker exec.
	procCtx := context.WithoutCancel(ctx)
	// Every command runs inside its own proot invocation so the flow's work
	// dir is visible at /work, exactly like the docker bind mount. -0 fakes
	// uid 0 inside, mirroring the docker default of running as root.
	guestWD := containerWorkDir
	if rel, err := filepath.Rel(sess.workDir, rec.workDir); err == nil && rel != "." {
		guestWD = filepath.ToSlash(filepath.Join(containerWorkDir, rel))
	}
	args := append([]string{
		"-r", e.prootRoot,
		"-b", sess.workDir + ":/work",
		"-w", guestWD,
		"-0",
	}, argv...)
	cmd := exec.CommandContext(procCtx, e.prootBin, args...)
	cmd.Env = mergeEnv(os.Environ(), rec.env)
	// PROOT_NO_SECCOMP=1 forces classic ptrace mode. proot's seccomp
	// acceleration breaks path translation for -b binds in sandboxed
	// environments (observed on the validation VM); the variable is a no-op
	// fallback everywhere else, where seccomp mode is merely faster.
	cmd.Env = append(cmd.Env, "PROOT_NO_SECCOMP=1")
	cmd.Stdout = pw
	cmd.Stderr = pw
	// Detach from our stdio; keep the process in our process group so the
	// caller can still signal it via the tracked handle.
	cmd.Stdin = nil

	if err := cmd.Start(); err != nil {
		_ = pw.Close()
		_ = pr.Close()
		return client.HijackedResponse{}, fmt.Errorf("starting exec %q: %w", execID, err)
	}
	rec.mu.Lock()
	rec.started = true
	rec.running = true
	rec.proc = cmd.Process
	rec.mu.Unlock()

	go func() {
		err := cmd.Wait()
		code := 0
		if err != nil {
			if ee, ok := err.(*exec.ExitError); ok {
				code = ee.ExitCode()
			} else {
				code = 1
			}
		}
		rec.mu.Lock()
		rec.running = false
		rec.exited = true
		rec.code = code
		rec.mu.Unlock()
		_ = pw.Close()
		close(rec.done)
	}()

	return client.HijackedResponse{
		Conn:   conn,
		Reader: bufio.NewReader(conn),
	}, nil
}

// ContainerExecInspect reports the exec's running state and exit code from
// the process table.
func (e *Executor) ContainerExecInspect(
	ctx context.Context,
	execID string,
) (client.ExecInspectResult, error) {
	_, rec, err := e.findExec(execID)
	if err != nil {
		return client.ExecInspectResult{}, err
	}
	rec.mu.Lock()
	defer rec.mu.Unlock()
	return client.ExecInspectResult{
		ID:       rec.id,
		Running:  rec.running,
		ExitCode: rec.code,
	}, nil
}

func mergeEnv(base, overlay []string) []string {
	merged := make(map[string]string, len(base)+len(overlay))
	order := make([]string, 0, len(base)+len(overlay))
	set := func(kv string) {
		k, v, _ := strings.Cut(kv, "=")
		if _, ok := merged[k]; !ok {
			order = append(order, k)
		}
		merged[k] = v
	}
	for _, kv := range base {
		set(kv)
	}
	for _, kv := range overlay {
		set(kv)
	}
	out := make([]string, 0, len(order))
	for _, k := range order {
		out = append(out, k+"="+merged[k])
	}
	return out
}

// ContainerStatPath stats a container path, mirroring docker's PathStat.
func (e *Executor) ContainerStatPath(
	ctx context.Context,
	containerID string,
	path string,
) (container.PathStat, error) {
	sess, err := e.lookup(containerID)
	if err != nil {
		return container.PathStat{}, err
	}
	host, err := sess.gate.mapContainerPath(path)
	if err != nil {
		return container.PathStat{}, err
	}
	fi, err := os.Lstat(host)
	if err != nil {
		return container.PathStat{}, fmt.Errorf("stat %q: %w", path, err)
	}
	stat := container.PathStat{
		Name:  filepath.Base(filepath.ToSlash(filepath.Clean("/" + strings.TrimPrefix(path, "/")))),
		Size:  fi.Size(),
		Mode:  fi.Mode(),
		Mtime: fi.ModTime(),
	}
	if fi.Mode()&os.ModeSymlink != 0 {
		if target, err := os.Readlink(host); err == nil {
			stat.LinkTarget = target
		}
	}
	if stat.Name == "" || stat.Name == "/" {
		stat.Name = "work"
	}
	return stat, nil
}

// ListContainerDir lists a container directory, mirroring the docker
// client's ContainerDirListing semantics (per-entry failures carried in
// Failures; directory-level faults returned as errors).
func (e *Executor) ListContainerDir(
	ctx context.Context,
	containerID string,
	dirPath string,
) (docker.ContainerDirListing, error) {
	sess, err := e.lookup(containerID)
	if err != nil {
		return docker.ContainerDirListing{}, err
	}
	if strings.TrimSpace(dirPath) == "" {
		dirPath = containerWorkDir
	}
	dirStat, err := e.ContainerStatPath(ctx, containerID, dirPath)
	if err != nil {
		return docker.ContainerDirListing{}, fmt.Errorf("failed to stat container path '%s': %w", dirPath, err)
	}
	if !dirStat.Mode.IsDir() {
		return docker.ContainerDirListing{}, fmt.Errorf("container path '%s' is not a directory", dirPath)
	}
	host, err := sess.gate.mapContainerPath(dirPath)
	if err != nil {
		return docker.ContainerDirListing{}, err
	}
	entries, err := os.ReadDir(host)
	if err != nil {
		return docker.ContainerDirListing{}, fmt.Errorf("failed to read container dir '%s': %w", dirPath, err)
	}
	if err := ctx.Err(); err != nil {
		return docker.ContainerDirListing{}, fmt.Errorf("listing container directory '%s': %w", dirPath, err)
	}
	listing := docker.ContainerDirListing{}
	truncated := len(entries) > maxListEntries
	if truncated {
		entries = entries[:maxListEntries]
	}
	for _, de := range entries {
		childContainerPath := strings.TrimSuffix(dirPath, "/") + "/" + de.Name()
		st, err := e.ContainerStatPath(ctx, containerID, childContainerPath)
		if err != nil {
			listing.Failures = append(listing.Failures, docker.ContainerEntryError{
				Name: de.Name(),
				Path: childContainerPath,
				Err:  err,
			})
			continue
		}
		listing.Files = append(listing.Files, st)
	}
	listing.Truncated = truncated
	return listing, nil
}

// CopyToContainer extracts a tar stream into the destination container
// directory. Every entry passes the zip-slip gate; absolute paths and
// ".." escapes are denied.
func (e *Executor) CopyToContainer(
	ctx context.Context,
	containerID string,
	dstPath string,
	content io.Reader,
	options client.CopyToContainerOptions,
) error {
	sess, err := e.lookup(containerID)
	if err != nil {
		return err
	}
	if strings.TrimSpace(dstPath) == "" {
		dstPath = containerWorkDir
	}
	dstHost, err := sess.gate.mapContainerPath(dstPath)
	if err != nil {
		return fmt.Errorf("invalid copy destination: %w", err)
	}
	if err := os.MkdirAll(dstHost, 0o755); err != nil {
		return fmt.Errorf("creating copy destination: %w", err)
	}
	tr := tar.NewReader(content)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("reading tar stream: %w", err)
		}
		target, err := sess.gate.checkTarEntry(hdr.Name, dstHost)
		if err != nil {
			return err
		}
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, os.FileMode(hdr.Mode).Perm()); err != nil {
				return fmt.Errorf("creating dir %q: %w", hdr.Name, err)
			}
		case tar.TypeReg, tar.TypeRegA:
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return fmt.Errorf("creating parent dir: %w", err)
			}
			f, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(hdr.Mode).Perm())
			if err != nil {
				return fmt.Errorf("creating file %q: %w", hdr.Name, err)
			}
			if _, err := io.Copy(f, tr); err != nil {
				_ = f.Close()
				return fmt.Errorf("writing file %q: %w", hdr.Name, err)
			}
			_ = f.Close()
		case tar.TypeSymlink:
			linkTarget := hdr.Linkname
			if filepath.IsAbs(linkTarget) || linkTarget == ".." ||
				strings.HasPrefix(filepath.ToSlash(linkTarget), "../") {
				return fmt.Errorf("tar entry %q: symlink escape denied", hdr.Name)
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return fmt.Errorf("creating parent dir: %w", err)
			}
			_ = os.Remove(target)
			if err := os.Symlink(linkTarget, target); err != nil {
				return fmt.Errorf("creating symlink %q: %w", hdr.Name, err)
			}
		default:
			// Devices, FIFOs and other specials have no meaning for agent
			// file sync; skip rather than fail the whole copy.
			continue
		}
	}
	return nil
}

// CopyFromContainer returns a tar stream of the source container path plus
// its stat, mirroring the docker client (callers untar the stream).
func (e *Executor) CopyFromContainer(
	ctx context.Context,
	containerID string,
	srcPath string,
) (io.ReadCloser, container.PathStat, error) {
	sess, err := e.lookup(containerID)
	if err != nil {
		return nil, container.PathStat{}, err
	}
	stat, err := e.ContainerStatPath(ctx, containerID, srcPath)
	if err != nil {
		return nil, container.PathStat{}, err
	}
	host, err := sess.gate.mapContainerPath(srcPath)
	if err != nil {
		return nil, container.PathStat{}, err
	}
	pr, pw := io.Pipe()
	go func() {
		tw := tar.NewWriter(pw)
		err := filepath.Walk(host, func(p string, fi os.FileInfo, err error) error {
			if err != nil {
				return err
			}
			rel, err := filepath.Rel(host, p)
			if err != nil {
				return err
			}
			name := filepath.ToSlash(rel)
			if name == "." {
				if !fi.IsDir() {
					name = stat.Name
				} else {
					return nil
				}
			}
			hdr, err := tar.FileInfoHeader(fi, "")
			if err != nil {
				return err
			}
			hdr.Name = name
			if err := tw.WriteHeader(hdr); err != nil {
				return err
			}
			if fi.Mode().IsRegular() {
				f, err := os.Open(p)
				if err != nil {
					return err
				}
				_, err = io.Copy(tw, f)
				_ = f.Close()
				if err != nil {
					return err
				}
			}
			return nil
		})
		_ = tw.Close()
		_ = pw.CloseWithError(err)
	}()
	return pr, stat, nil
}

// Cleanup kills every tracked process in every session and mirrors the
// docker client's shutdown bookkeeping: running containers are removed and
// their DB rows marked deleted; flows left in created state are marked failed.
func (e *Executor) Cleanup(ctx context.Context) error {
	e.mu.Lock()
	sessions := make([]*session, 0, len(e.byName))
	for _, s := range e.byName {
		sessions = append(sessions, s)
	}
	e.mu.Unlock()
	for _, s := range sessions {
		killSessionProcs(s)
	}
	if e.db == nil {
		e.mu.Lock()
		e.byName = make(map[string]*session)
		e.byLocalID = make(map[string]*session)
		e.mu.Unlock()
		return nil
	}
	containers, err := e.db.GetContainers(ctx)
	if err != nil {
		return fmt.Errorf("failed to get all containers: %w", err)
	}
	for _, c := range containers {
		switch c.Status {
		case database.ContainerStatusStarting, database.ContainerStatusRunning:
			localID := ""
			if c.LocalID.Valid {
				localID = c.LocalID.String
			}
			_ = e.RemoveContainer(ctx, localID, c.ID)
		}
	}
	flows, err := e.db.GetFlows(ctx)
	if err != nil {
		return fmt.Errorf("failed to get all flows: %w", err)
	}
	for _, f := range flows {
		if f.Status == database.FlowStatusCreated {
			_, _ = e.db.UpdateFlowStatus(ctx, database.UpdateFlowStatusParams{
				Status: database.FlowStatusFailed,
				ID:     f.ID,
			})
		}
	}
	return nil
}

// GetDefaultImage returns the sentinel identifying the proot rootfs as the
// execution image.
func (e *Executor) GetDefaultImage() string {
	return DefaultImage
}
