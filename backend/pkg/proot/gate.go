package proot

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// containerWorkDir is the path agents see inside the "container".
// It mirrors docker.WorkFolderPathInContainer ("/work").
const containerWorkDir = "/work"

// gate confines all agent-visible paths to a single host directory tree
// (the flow work dir). Every executor method that takes a path runs through
// it before touching the filesystem.
type gate struct {
	root string // canonicalized, absolute host path of the flow work dir
}

func newGate(workDir string) (*gate, error) {
	abs, err := filepath.Abs(workDir)
	if err != nil {
		return nil, fmt.Errorf("resolving work dir: %w", err)
	}
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return nil, fmt.Errorf("canonicalizing work dir: %w", err)
	}
	return &gate{root: real}, nil
}

// mapContainerPath translates an agent-visible container path to a host path
// and enforces confinement. Absolute paths must live under /work; relative
// paths resolve against /work. Anything else is rejected.
func (g *gate) mapContainerPath(cpath string) (string, error) {
	cleaned := filepath.Clean("/" + strings.TrimPrefix(filepath.ToSlash(cpath), "/"))
	var rel string
	if cleaned == containerWorkDir {
		rel = "."
	} else if strings.HasPrefix(cleaned, containerWorkDir+"/") {
		rel = strings.TrimPrefix(cleaned, containerWorkDir+"/")
	} else {
		return "", fmt.Errorf("path %q is outside the allowed %s tree", cpath, containerWorkDir)
	}
	host := filepath.Join(g.root, filepath.FromSlash(rel))
	return g.confine(host)
}

// mapGuestPath translates a guest-visible path to a host path when the
// executor wraps commands in `proot -r <root> -b <workDir>:/work`. The guest
// sees the work dir at /work, so guest /work/x == host $root/x.
func (g *gate) mapGuestPath(gpath string) (string, error) {
	return g.mapContainerPath(gpath)
}

// confine canonicalizes host (resolving symlinks at access time, TOCTOU-aware)
// and requires the result to stay inside the gate root.
func (g *gate) confine(host string) (string, error) {
	abs, err := filepath.Abs(host)
	if err != nil {
		return "", fmt.Errorf("resolving path: %w", err)
	}
	// Resolve the longest existing ancestor, then re-append the remainder so
	// not-yet-existing paths (copy targets) are still gated.
	ancestor := abs
	var tail []string
	for {
		if _, err := os.Lstat(ancestor); err == nil {
			break
		} else if !os.IsNotExist(err) {
			return "", fmt.Errorf("statting path: %w", err)
		}
		parent := filepath.Dir(ancestor)
		if parent == ancestor {
			return "", fmt.Errorf("path %q escapes the work dir", host)
		}
		tail = append([]string{filepath.Base(ancestor)}, tail...)
		ancestor = parent
	}
	real, err := filepath.EvalSymlinks(ancestor)
	if err != nil {
		return "", fmt.Errorf("canonicalizing path: %w", err)
	}
	for _, t := range tail {
		if t == ".." {
			return "", fmt.Errorf("path %q escapes the work dir", host)
		}
		real = filepath.Join(real, t)
	}
	if real != g.root && !strings.HasPrefix(real, g.root+string(os.PathSeparator)) {
		return "", fmt.Errorf("path %q escapes the work dir", host)
	}
	return real, nil
}

// checkTarEntry validates a tar entry name (zip-slip rule): relative,
// no absolute paths, no ".." escapes. Returns the confined host path.
func (g *gate) checkTarEntry(name, dstDir string) (string, error) {
	slash := filepath.ToSlash(name)
	if slash == "" || strings.HasPrefix(slash, "/") {
		return "", fmt.Errorf("tar entry %q: absolute paths denied", name)
	}
	cleaned := filepath.Clean(slash)
	if cleaned == "." || cleaned == ".." || strings.HasPrefix(cleaned, "../") || strings.Contains(cleaned, "/../") {
		return "", fmt.Errorf("tar entry %q: path escape denied", name)
	}
	host := filepath.Join(dstDir, filepath.FromSlash(cleaned))
	return g.confine(host)
}
