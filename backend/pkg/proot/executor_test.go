package proot

import (
	"archive/tar"
	"bytes"
	"context"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"pentagi/pkg/database"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"
)

// testExecutor builds an executor in proot validation mode against the
// rootfs prepared by the Phase 0 spike. Set PROOTEXEC_ROOTFS to override.
func testExecutor(t *testing.T) (*Executor, string) {
	t.Helper()
	rootfs := os.Getenv("PROOTEXEC_ROOTFS")
	if rootfs == "" {
		rootfs = "/home/hatch/workspace/pentagi-rnd/spike/rootfs/ubuntu"
	}
	if _, err := os.Stat(filepath.Join(rootfs, "bin", "sh")); err != nil {
		t.Skipf("rootfs not available at %s: %v", rootfs, err)
	}
	dataDir := t.TempDir()
	ex, err := New(dataDir, nil, rootfs)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return ex, rootfs
}

func runCmd(t *testing.T, ex *Executor, sessName string, cmd []string, workDir string) (string, int) {
	t.Helper()
	ctx := context.Background()
	cr, err := ex.ContainerExecCreate(ctx, sessName, client.ExecCreateOptions{
		Cmd:          cmd,
		AttachStdout: true,
		AttachStderr: true,
		WorkingDir:   workDir,
		TTY:          true,
	})
	if err != nil {
		t.Fatalf("ContainerExecCreate: %v", err)
	}
	resp, err := ex.ContainerExecAttach(ctx, cr.ID, client.ExecAttachOptions{TTY: true})
	if err != nil {
		t.Fatalf("ContainerExecAttach: %v", err)
	}
	out, err := io.ReadAll(resp.Reader)
	resp.Close()
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	insp, err := ex.ContainerExecInspect(ctx, cr.ID)
	if err != nil {
		t.Fatalf("ContainerExecInspect: %v", err)
	}
	return string(out), insp.ExitCode
}

func TestRunContainerAndExec(t *testing.T) {
	ex, _ := testExecutor(t)
	ctx := context.Background()
	c, err := ex.RunContainer(ctx, "pentagi-terminal-1", database.ContainerType("terminal"), 1,
		&container.Config{Image: "debian:latest"}, &container.HostConfig{})
	if err != nil {
		t.Fatalf("RunContainer: %v", err)
	}
	if c.Image != "proot" {
		t.Fatalf("expected image sentinel proot, got %q", c.Image)
	}
	running, err := ex.IsContainerRunning(ctx, "pentagi-terminal-1")
	if err != nil || !running {
		t.Fatalf("IsContainerRunning = %v, %v", running, err)
	}
	// lookup by local ID too
	running, err = ex.IsContainerRunning(ctx, c.LocalID.String)
	if err != nil || !running {
		t.Fatalf("IsContainerRunning(localID) = %v, %v", running, err)
	}

	out, code := runCmd(t, ex, "pentagi-terminal-1",
		[]string{"sh", "-c", `echo hello-proot && pwd`, ""}, "/work")
	if code != 0 {
		t.Fatalf("exit code = %d, want 0 (out=%q)", code, out)
	}
	if !strings.Contains(out, "hello-proot") {
		t.Fatalf("unexpected output: %q", out)
	}
	if !strings.Contains(out, "/work") {
		t.Fatalf("expected guest cwd /work, got: %q", out)
	}

	// filesystem write inside the container, then read back via CopyFromContainer
	out, code = runCmd(t, ex, "pentagi-terminal-1",
		[]string{"sh", "-c", `echo data123 > sub.txt && cat sub.txt`, ""}, "/work")
	if code != 0 || !strings.Contains(out, "data123") {
		t.Fatalf("write/read: code=%d out=%q", code, out)
	}

	// exit code propagation
	_, code = runCmd(t, ex, "pentagi-terminal-1",
		[]string{"sh", "-c", `exit 42`, ""}, "/work")
	if code != 42 {
		t.Fatalf("exit code = %d, want 42", code)
	}

	// stderr merged into the stream
	out, _ = runCmd(t, ex, "pentagi-terminal-1",
		[]string{"sh", "-c", `echo to-stderr >&2`, ""}, "/work")
	if !strings.Contains(out, "to-stderr") {
		t.Fatalf("stderr not merged: %q", out)
	}

	if err := ex.StopContainer(ctx, "pentagi-terminal-1", c.ID); err != nil {
		t.Fatalf("StopContainer: %v", err)
	}
	if running, _ := ex.IsContainerRunning(ctx, "pentagi-terminal-1"); running {
		t.Fatal("session still running after stop")
	}
	if err := ex.RemoveContainer(ctx, "pentagi-terminal-1", c.ID); err != nil {
		t.Fatalf("RemoveContainer: %v", err)
	}
	if running, _ := ex.IsContainerRunning(ctx, "pentagi-terminal-1"); running {
		t.Fatal("session still present after remove")
	}
}

func TestFlowCommandWrapper(t *testing.T) {
	// The exact wrapper PentAGI's terminal hot path sends:
	// ["sh","-c",<sandboxCommandSh>,<command>]
	ex, _ := testExecutor(t)
	ctx := context.Background()
	if _, err := ex.RunContainer(ctx, "pentagi-terminal-2", database.ContainerType("terminal"), 2,
		&container.Config{}, &container.HostConfig{}); err != nil {
		t.Fatal(err)
	}
	wrapper := "trap \"\" HUP; exec sh -c \"$0\""
	out, code := runCmd(t, ex, "pentagi-terminal-2",
		[]string{"sh", "-c", wrapper, "echo wrapped-ok"}, "/work")
	if code != 0 || !strings.Contains(out, "wrapped-ok") {
		t.Fatalf("wrapper passthrough failed: code=%d out=%q", code, out)
	}
}

func TestPathConfinement(t *testing.T) {
	ex, _ := testExecutor(t)
	ctx := context.Background()
	c, err := ex.RunContainer(ctx, "pentagi-terminal-3", database.ContainerType("terminal"), 3,
		&container.Config{}, &container.HostConfig{})
	if err != nil {
		t.Fatal(err)
	}
	_ = c

	// absolute escape attempt
	_, err = ex.ContainerStatPath(ctx, "pentagi-terminal-3", "/etc/passwd")
	if err == nil {
		t.Fatal("stat of /etc/passwd should be denied")
	}
	// dot-dot escape
	_, err = ex.ContainerStatPath(ctx, "pentagi-terminal-3", "/work/../../etc")
	if err == nil {
		t.Fatal("stat of /work/../../etc should be denied")
	}
	// symlink escape: create symlink inside work dir pointing outside
	out, code := runCmd(t, ex, "pentagi-terminal-3",
		[]string{"sh", "-c", `ln -s /etc evil && echo link-made`, ""}, "/work")
	if code != 0 {
		t.Fatalf("setup: %q", out)
	}
	_, err = ex.ContainerStatPath(ctx, "pentagi-terminal-3", "/work/evil/passwd")
	if err == nil {
		t.Fatal("stat through escaping symlink should be denied")
	}
	// zip-slip via CopyToContainer
	var buf bytes.Buffer
	tw := tar.NewWriter(&buf)
	_ = tw.WriteHeader(&tar.Header{Name: "../../evil.txt", Typeflag: tar.TypeReg, Mode: 0o644, Size: 3})
	_, _ = tw.Write([]byte("x\n"))
	_ = tw.Close()
	err = ex.CopyToContainer(ctx, "pentagi-terminal-3", "/work", &buf,
		client.CopyToContainerOptions{})
	if err == nil {
		t.Fatal("zip-slip tar entry should be denied")
	}
}

func TestFileOps(t *testing.T) {
	ex, _ := testExecutor(t)
	ctx := context.Background()
	if _, err := ex.RunContainer(ctx, "pentagi-terminal-4", database.ContainerType("terminal"), 4,
		&container.Config{}, &container.HostConfig{}); err != nil {
		t.Fatal(err)
	}

	// CopyToContainer with a tar archive
	var buf bytes.Buffer
	tw := tar.NewWriter(&buf)
	content := []byte("file-ops-content\n")
	_ = tw.WriteHeader(&tar.Header{Name: "nested/dir/note.txt", Typeflag: tar.TypeReg, Mode: 0o644, Size: int64(len(content))})
	_, _ = tw.Write(content)
	_ = tw.WriteHeader(&tar.Header{Name: "top.txt", Typeflag: tar.TypeReg, Mode: 0o600, Size: 5})
	_, _ = tw.Write([]byte("top!\n"))
	_ = tw.Close()
	if err := ex.CopyToContainer(ctx, "pentagi-terminal-4", "/work", &buf,
		client.CopyToContainerOptions{AllowOverwriteDirWithFile: true}); err != nil {
		t.Fatalf("CopyToContainer: %v", err)
	}

	// ContainerStatPath
	st, err := ex.ContainerStatPath(ctx, "pentagi-terminal-4", "/work/nested/dir/note.txt")
	if err != nil {
		t.Fatalf("ContainerStatPath: %v", err)
	}
	if st.Name != "note.txt" || st.Size != int64(len(content)) {
		t.Fatalf("stat mismatch: %+v", st)
	}

	// ListContainerDir
	listing, err := ex.ListContainerDir(ctx, "pentagi-terminal-4", "/work")
	if err != nil {
		t.Fatalf("ListContainerDir: %v", err)
	}
	names := map[string]bool{}
	for _, f := range listing.Files {
		names[f.Name] = true
	}
	if !names["top.txt"] || !names["nested"] {
		t.Fatalf("listing missing entries: %v", names)
	}

	// CopyFromContainer returns a tar stream the caller can untar
	rc, cstat, err := ex.CopyFromContainer(ctx, "pentagi-terminal-4", "/work/nested/dir/note.txt")
	if err != nil {
		t.Fatalf("CopyFromContainer: %v", err)
	}
	defer rc.Close()
	if !strings.Contains(cstat.Name, "note.txt") {
		t.Fatalf("copy stat name: %q", cstat.Name)
	}
	tr := tar.NewReader(rc)
	found := false
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatalf("tar read: %v", err)
		}
		if strings.HasSuffix(hdr.Name, "note.txt") {
			data, _ := io.ReadAll(tr)
			if string(data) != string(content) {
				t.Fatalf("tar content mismatch: %q", data)
			}
			found = true
		}
	}
	if !found {
		t.Fatal("note.txt not found in copy-out tar stream")
	}

	// agent-visible read of the copied file through exec
	out, code := runCmd(t, ex, "pentagi-terminal-4",
		[]string{"sh", "-c", `cat /work/nested/dir/note.txt`, ""}, "/work")
	if code != 0 || !strings.Contains(out, "file-ops-content") {
		t.Fatalf("exec cat: code=%d out=%q", code, out)
	}
}

func TestKillFlowCommands(t *testing.T) {
	ex, _ := testExecutor(t)
	ctx := context.Background()
	if _, err := ex.RunContainer(ctx, "pentagi-terminal-5", database.ContainerType("terminal"), 5,
		&container.Config{}, &container.HostConfig{}); err != nil {
		t.Fatal(err)
	}
	// start a long sleeper in the background of the test (attach returns immediately with stream)
	cr, err := ex.ContainerExecCreate(ctx, "pentagi-terminal-5", client.ExecCreateOptions{
		Cmd: []string{"sh", "-c", `sleep 120`, ""}, TTY: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	resp, err := ex.ContainerExecAttach(ctx, cr.ID, client.ExecAttachOptions{TTY: true})
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Close()
	insp, _ := ex.ContainerExecInspect(ctx, cr.ID)
	if !insp.Running {
		t.Fatal("sleeper should be running")
	}
	if err := ex.KillFlowCommands(ctx, "pentagi-terminal-5"); err != nil {
		t.Fatalf("KillFlowCommands: %v", err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for {
		insp, _ := ex.ContainerExecInspect(ctx, cr.ID)
		if !insp.Running {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("sleeper still running after KillFlowCommands")
		}
		time.Sleep(200 * time.Millisecond)
	}
}

func TestNetworkFromContainer(t *testing.T) {
	ex, _ := testExecutor(t)
	ctx := context.Background()
	if _, err := ex.RunContainer(ctx, "pentagi-terminal-6", database.ContainerType("terminal"), 6,
		&container.Config{}, &container.HostConfig{}); err != nil {
		t.Fatal(err)
	}
	// Validation-only helper: a static busybox placed in the scratch rootfs
	// (not part of the deliverable) for guest-side HTTP.
	if _, err := os.Stat("/home/hatch/workspace/pentagi-rnd/spike/rootfs/ubuntu/usr/local/bin/busybox"); err != nil {
		t.Skip("busybox not installed in scratch rootfs")
	}
	// Serve on host localhost; the guest shares the network namespace, so
	// 127.0.0.1 must be reachable from inside proot. The sandbox's egress
	// proxy vars are stripped for the localhost fetch.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("guest-net-ok"))
	}))
	addr := ln.Addr().String()
	cmd := `unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY ALL_PROXY all_proxy; /usr/local/bin/busybox wget -q -O- --timeout=15 http://` + addr + `/`
	out, code := runCmd(t, ex, "pentagi-terminal-6", []string{"sh", "-c", cmd, ""}, "/work")
	if code != 0 || !strings.Contains(out, "guest-net-ok") {
		t.Fatalf("network from container failed: code=%d out=%q", code, out)
	}
	// DNS resolution from inside the guest (external reachability of the
	// shared stack; proot does not virtualize networking).
	out, code = runCmd(t, ex, "pentagi-terminal-6",
		[]string{"sh", "-c", `getent hosts example.com`, ""}, "/work")
	if code != 0 || !strings.Contains(out, "example.com") {
		t.Fatalf("guest DNS failed: code=%d out=%q", code, out)
	}
}

func TestCleanup(t *testing.T) {
	ex, _ := testExecutor(t)
	ctx := context.Background()
	for _, n := range []string{"pentagi-terminal-7", "pentagi-terminal-8"} {
		if _, err := ex.RunContainer(ctx, n, database.ContainerType("terminal"), 7,
			&container.Config{}, &container.HostConfig{}); err != nil {
			t.Fatal(err)
		}
	}
	if err := ex.Cleanup(ctx); err != nil {
		t.Fatalf("Cleanup: %v", err)
	}
	for _, n := range []string{"pentagi-terminal-7", "pentagi-terminal-8"} {
		if running, _ := ex.IsContainerRunning(ctx, n); running {
			t.Fatalf("session %s alive after cleanup", n)
		}
	}
	if ex.GetDefaultImage() != "proot" {
		t.Fatal("GetDefaultImage should return proot")
	}
}
