package cloud.changlu.agentforge.harness.runtime.local.command;

import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CommandExecutorTest {

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void returnsExitCodeAndEnforcesTimeout() throws Exception {
        Workspace workspace =
                Workspace.builder().root(temporaryFolder.newFolder("commands").toPath()).build();
        LocalRuntimeOptions options =
                LocalRuntimeOptions.builder().commandTimeoutMillis(100).build();
        CommandExecutor executor = new CommandExecutor(workspace, options);
        try {
            CommandResult success = executor.execute("printf success");
            assertEquals(0, success.exitCode());
            assertEquals("success", success.output());

            CommandResult timeout = executor.execute("sleep 2");
            assertTrue(timeout.timedOut());
            assertEquals(-1, timeout.exitCode());
        } finally {
            executor.close();
        }
    }
}
