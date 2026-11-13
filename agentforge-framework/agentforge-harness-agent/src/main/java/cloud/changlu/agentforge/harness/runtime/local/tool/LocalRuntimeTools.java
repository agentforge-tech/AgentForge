package cloud.changlu.agentforge.harness.runtime.local.tool;

import cloud.changlu.agentforge.harness.runtime.RuntimeTools;
import cloud.changlu.agentforge.harness.runtime.RuntimeType;
import cloud.changlu.agentforge.harness.runtime.local.command.CommandExecutor;
import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Built-in local file, search and command tools bounded by a {@link Workspace}. */
public final class LocalRuntimeTools implements RuntimeTools {

    private final Workspace workspace;
    private final SandboxLimits limits;
    private final CommandExecutor commands;
    private final Set<Path> readFiles =
            Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>());

    public LocalRuntimeTools(Workspace workspace, LocalRuntimeOptions options) {
        this(workspace, SandboxLimits.defaults(), new CommandExecutor(workspace, options));
    }

    public LocalRuntimeTools(Workspace workspace, SandboxLimits limits, CommandExecutor commands) {
        if (workspace == null || limits == null || commands == null) {
            throw new IllegalArgumentException("workspace, limits and commands must not be null");
        }
        this.workspace = workspace;
        this.limits = limits;
        this.commands = commands;
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.LOCAL;
    }

    @Override
    public List<Object> toolObjects() {
        return Collections.<Object>singletonList(this);
    }

    @Override
    public void cancelAll() {
        commands.cancelAll();
    }

    @Override
    public void close() {
        commands.close();
    }

    @Tool(name = "read", value = "Read a UTF-8 text file inside the workspace with line numbers.")
    public String read(@P(name = "path", value = "Workspace-relative file path") String path) {
        Path file = requireRegularFile(path);
        ensureSize(file);
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int count = Math.min(lines.size(), limits.maxReadLines());
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < count; i++) {
                String line = lines.get(i);
                if (line.length() > limits.maxLineChars()) {
                    line = line.substring(0, limits.maxLineChars()) + "...[line truncated]";
                }
                result.append(i + 1).append(": ").append(line).append('\n');
            }
            if (lines.size() > count) result.append("...[file truncated]");
            readFiles.add(file);
            return result.toString();
        } catch (IOException e) {
            throw failure("failed to read " + path, e);
        }
    }

    @Tool(
            name = "write",
            value =
                    "Create or overwrite a UTF-8 file inside the workspace. Existing files must be read first.")
    public String write(
            @P(name = "path", value = "Workspace-relative file path") String path,
            @P(name = "content", value = "Complete UTF-8 file content") String content) {
        Path file = workspace.resolveForCreate(path);
        if (Files.exists(file) && !readFiles.contains(workspace.resolveExisting(path))) {
            throw new IllegalStateException(
                    "FILE_NOT_READ: read the existing file before writing it");
        }
        byte[] bytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limits.maxFileBytes()) {
            throw new IllegalArgumentException("FILE_TOO_LARGE: content exceeds configured limit");
        }
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(
                    file,
                    bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            readFiles.add(file.toRealPath());
            return "Wrote " + bytes.length + " bytes to " + workspace.root().relativize(file);
        } catch (IOException e) {
            throw failure("failed to write " + path, e);
        }
    }

    @Tool(
            name = "edit",
            value = "Replace one unique text occurrence in a previously read workspace file.")
    public String edit(
            @P(name = "path", value = "Workspace-relative file path") String path,
            @P(name = "oldText", value = "Exact text to replace") String oldText,
            @P(name = "newText", value = "Replacement text") String newText) {
        Path file = requireRegularFile(path);
        if (!readFiles.contains(file)) {
            throw new IllegalStateException("FILE_NOT_READ: read the file before editing it");
        }
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("oldText must not be empty");
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            int first = content.indexOf(oldText);
            if (first < 0) throw new IllegalArgumentException("oldText was not found");
            if (content.indexOf(oldText, first + oldText.length()) >= 0) {
                throw new IllegalArgumentException("oldText must match exactly once");
            }
            String updated =
                    content.substring(0, first)
                            + (newText == null ? "" : newText)
                            + content.substring(first + oldText.length());
            byte[] bytes = updated.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > limits.maxFileBytes()) {
                throw new IllegalArgumentException(
                        "FILE_TOO_LARGE: edited content exceeds configured limit");
            }
            Files.write(file, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            return "Edited " + workspace.root().relativize(file);
        } catch (IOException e) {
            throw failure("failed to edit " + path, e);
        }
    }

    @Tool(name = "glob", value = "Find workspace files using a glob pattern such as **/*.java.")
    public String glob(
            @P(name = "pattern", value = "Glob pattern relative to the workspace") String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> matches = new ArrayList<String>();
        try (Stream<Path> paths = Files.walk(workspace.root())) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext() && matches.size() < limits.maxSearchResults()) {
                Path file = iterator.next();
                Path relative = workspace.root().relativize(file);
                if (Files.isRegularFile(file) && matcher.matches(relative)) {
                    matches.add(relative.toString());
                }
            }
        } catch (IOException e) {
            throw failure("glob failed", e);
        }
        Collections.sort(matches);
        return join(matches);
    }

    @Tool(name = "grep", value = "Search UTF-8 workspace files with a Java regular expression.")
    public String grep(
            @P(name = "regex", value = "Java regular expression") String regex,
            @P(name = "path", value = "File or directory relative to workspace") String path) {
        final Pattern compiled = Pattern.compile(regex);
        final Path target =
                workspace.resolveExisting(path == null || path.trim().isEmpty() ? "." : path);
        List<String> results = new ArrayList<String>();
        try (Stream<Path> paths =
                Files.isDirectory(target) ? Files.walk(target) : Stream.of(target)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext() && results.size() < limits.maxSearchResults()) {
                Path file = iterator.next();
                if (!Files.isRegularFile(file) || Files.size(file) > limits.maxFileBytes())
                    continue;
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                    continue;
                }
                for (int i = 0;
                        i < lines.size() && results.size() < limits.maxSearchResults();
                        i++) {
                    Matcher matcher = compiled.matcher(lines.get(i));
                    if (matcher.find()) {
                        results.add(
                                workspace.root().relativize(file)
                                        + ":"
                                        + (i + 1)
                                        + ":"
                                        + lines.get(i));
                    }
                }
            }
        } catch (IOException e) {
            throw failure("grep failed", e);
        }
        return join(results);
    }

    @Tool(name = "rg", value = "Run ripgrep inside the workspace and return matching lines.")
    public String rg(
            @P(name = "pattern", value = "ripgrep search pattern") String pattern,
            @P(name = "path", value = "Workspace-relative search path") String path) {
        Path target = workspace.resolveExisting(path == null || path.trim().isEmpty() ? "." : path);
        String relative = workspace.root().relativize(target).toString();
        if (relative.isEmpty()) relative = ".";
        return commands.execute(
                        "rg --line-number --no-heading -- "
                                + quote(pattern)
                                + " "
                                + quote(relative))
                .toString();
    }

    @Tool(
            name = "bash",
            value =
                    "Run a foreground shell command in the workspace. Background jobs are not supported.")
    public String bash(@P(name = "command", value = "Shell command to execute") String command) {
        return commands.execute(command).toString();
    }

    private Path requireRegularFile(String path) {
        Path file = workspace.resolveExisting(path);
        if (!Files.isRegularFile(file))
            throw new IllegalArgumentException("not a regular file: " + path);
        return file;
    }

    private void ensureSize(Path file) {
        try {
            if (Files.size(file) > limits.maxFileBytes()) {
                throw new IllegalArgumentException("FILE_TOO_LARGE: " + file);
            }
        } catch (IOException e) {
            throw failure("failed to inspect file", e);
        }
    }

    private static String quote(String value) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String join(List<String> values) {
        if (values.isEmpty()) return "No matches";
        StringBuilder result = new StringBuilder();
        for (String value : values) result.append(value).append('\n');
        return result.toString();
    }

    private static IllegalStateException failure(String message, Exception cause) {
        return new IllegalStateException(message + ": " + cause.getMessage(), cause);
    }
}
