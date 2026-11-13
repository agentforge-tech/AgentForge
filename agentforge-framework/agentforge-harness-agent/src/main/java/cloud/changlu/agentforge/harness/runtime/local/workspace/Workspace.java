package cloud.changlu.agentforge.harness.runtime.local.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A local directory that bounds every file and command operation of a HarnessAgent. */
public final class Workspace {

    private final String id;
    private final Path root;
    private final Path workingDirectory;

    private Workspace(Builder builder) {
        if (builder.root == null) {
            throw new IllegalArgumentException("workspace root must not be null");
        }
        try {
            this.root = builder.root.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("workspace root does not exist: " + builder.root, e);
        }
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IllegalArgumentException(
                    "workspace root must be a readable directory: " + root);
        }
        this.id = isBlank(builder.id) ? root.getFileName().toString() : builder.id.trim();
        Path requestedDirectory =
                builder.workingDirectory == null ? root : builder.workingDirectory;
        this.workingDirectory = resolveExisting(requestedDirectory);
        if (!Files.isDirectory(workingDirectory)) {
            throw new IllegalArgumentException(
                    "working directory is not a directory: " + workingDirectory);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String id() {
        return id;
    }

    public Path root() {
        return root;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    /** Resolves an existing path and rejects traversal and symbolic-link escapes. */
    public Path resolveExisting(String path) {
        return resolveExisting(path == null ? null : java.nio.file.Paths.get(path));
    }

    /** Resolves a path that may not exist yet while validating its nearest existing parent. */
    public Path resolveForCreate(String path) {
        if (isBlank(path)) {
            throw new IllegalArgumentException("path must not be blank");
        }
        Path candidate = resolveLexically(java.nio.file.Paths.get(path));
        if (Files.exists(candidate)) {
            return resolveExisting(candidate);
        }
        Path parent = candidate.getParent();
        while (parent != null && !Files.exists(parent)) {
            parent = parent.getParent();
        }
        if (parent == null || !resolveExisting(parent).startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        return candidate;
    }

    private Path resolveExisting(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        Path candidate = resolveLexically(path);
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw new IllegalArgumentException("path escapes workspace: " + path);
            }
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException("path does not exist: " + path, e);
        }
    }

    private Path resolveLexically(Path path) {
        Path candidate = path.isAbsolute() ? path : root.resolve(path);
        candidate = candidate.toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("path escapes workspace: " + path);
        }
        return candidate;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class Builder {
        private String id;
        private Path root;
        private Path workingDirectory;

        private Builder() {}

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder root(Path root) {
            this.root = root;
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        public Workspace build() {
            return new Workspace(this);
        }
    }
}
