package cloud.changlu.agentforge.harness.runtime.local.tool;

import cloud.changlu.agentforge.harness.runtime.local.command.CommandExecutor;
import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LocalRuntimeToolsTest {

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path root;
    private CommandExecutor commands;
    private LocalRuntimeTools tools;

    @Before
    public void setUp() throws Exception {
        root = temporaryFolder.newFolder("tools-workspace").toPath();
        Workspace workspace = Workspace.builder().root(root).build();
        SandboxLimits limits = SandboxLimits.builder().maxSearchResults(20).build();
        commands =
                new CommandExecutor(
                        workspace,
                        LocalRuntimeOptions.builder().commandTimeoutMillis(1000).build());
        tools = new LocalRuntimeTools(workspace, limits, commands);
    }

    @After
    public void tearDown() {
        commands.close();
    }

    @Test
    public void requiresReadBeforeOverwritingAndEditing() throws Exception {
        Files.write(root.resolve("note.txt"), "old value".getBytes(StandardCharsets.UTF_8));

        try {
            tools.write("note.txt", "overwritten");
            fail("existing file must be read first");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("FILE_NOT_READ"));
        }

        assertTrue(tools.read("note.txt").contains("old value"));
        assertTrue(tools.edit("note.txt", "old", "new").contains("Edited"));
        assertTrue(tools.read("note.txt").contains("new value"));
        assertTrue(tools.write("note.txt", "final value").contains("Wrote"));
    }

    @Test
    public void createsSearchesAndExecutesInsideWorkspace() throws Exception {
        tools.write("src/demo.txt", "alpha\nbeta\n");

        assertTrue(tools.glob("**/*.txt").contains("src/demo.txt"));
        assertTrue(tools.grep("bet.*", "src").contains("src/demo.txt:2:beta"));
        assertTrue(tools.bash("pwd").contains(root.toRealPath().toString()));
    }
}
