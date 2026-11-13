package cloud.changlu.agentforge.harness.runtime.local.command;

import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Executes foreground shell commands inside one workspace. */
public final class CommandExecutor implements AutoCloseable {

    private final Workspace workspace;
    private final LocalRuntimeOptions options;
    private final Set<Process> active =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Process, Boolean>());
    private volatile boolean closed;

    public CommandExecutor(Workspace workspace, LocalRuntimeOptions options) {
        this.workspace = workspace;
        this.options = options;
    }

    public CommandResult execute(String command) {
        if (closed) throw new IllegalStateException("command executor is closed");
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        Process process = null;
        try {
            process =
                    new ProcessBuilder(options.shell(), "-lc", command)
                            .directory(workspace.workingDirectory().toFile())
                            .redirectErrorStream(true)
                            .start();
            active.add(process);
            final Process running = process;
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader =
                    new Thread(
                            new Runnable() {
                                @Override
                                public void run() {
                                    copy(running.getInputStream(), output);
                                }
                            },
                            "agentforge-command-output");
            reader.setDaemon(true);
            reader.start();
            boolean finished =
                    process.waitFor(options.commandTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!finished) process.destroyForcibly();
            reader.join(1000L);
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (text.length() > options.maxCommandOutputChars()) {
                text =
                        text.substring(0, options.maxCommandOutputChars())
                                + "\n...[output truncated]";
            }
            return new CommandResult(finished ? process.exitValue() : -1, !finished, text);
        } catch (IOException e) {
            throw new IllegalStateException("failed to start command: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            throw new IllegalStateException("command interrupted", e);
        } finally {
            if (process != null) active.remove(process);
        }
    }

    private static void copy(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[4096];
        int read;
        try {
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        } catch (IOException ignored) {
            // Process termination commonly closes the stream.
        }
    }

    public void cancelAll() {
        for (Process process : active) process.destroyForcibly();
    }

    @Override
    public void close() {
        closed = true;
        cancelAll();
    }
}
