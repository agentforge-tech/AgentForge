package cloud.changlu.agentforge.harness.runtime.local;

import cloud.changlu.agentforge.harness.runtime.RuntimeType;
import cloud.changlu.agentforge.harness.runtime.local.config.LocalRuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.local.tool.LocalRuntimeTools;
import cloud.changlu.agentforge.harness.runtime.local.workspace.Workspace;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LocalRuntimeTest {

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void assemblesRuntimeOwnedContextWithDefaults() throws Exception {
        Workspace workspace =
                Workspace.builder()
                        .id("runtime-workspace")
                        .root(temporaryFolder.newFolder("runtime-workspace").toPath())
                        .build();

        LocalRuntime runtime = LocalRuntime.builder().workspace(workspace).build();
        try {
            assertEquals(RuntimeType.LOCAL, runtime.type());
            assertSame(workspace, runtime.workspace());
            assertTrue(runtime.options() instanceof LocalRuntimeOptions);
            assertTrue(runtime.tools() instanceof LocalRuntimeTools);
            assertTrue(runtime.defaultSystemPrompt().contains("# Harness Agent"));
            assertTrue(runtime.defaultSystemPrompt().contains("# Runtime"));
            assertTrue(runtime.defaultSystemPrompt().contains("runtime-workspace"));
            assertTrue(runtime.defaultSystemPrompt().contains(workspace.root().toString()));
        } finally {
            runtime.close();
        }
    }

    @Test
    public void requiresWorkspace() {
        try {
            LocalRuntime.builder().build();
            fail("workspace must be required");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("workspace"));
        }
    }
}
