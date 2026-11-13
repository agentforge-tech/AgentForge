package cloud.changlu.agentforge.harness.runtime.local.workspace;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WorkspaceTest {

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void resolvesOnlyPathsInsideWorkspace() throws Exception {
        Path root = temporaryFolder.newFolder("workspace").toPath();
        Path file = Files.write(root.resolve("hello.txt"), "hello".getBytes("UTF-8"));
        Workspace workspace = Workspace.builder().id("demo").root(root).build();

        assertEquals("demo", workspace.id());
        assertEquals(file.toRealPath(), workspace.resolveExisting("hello.txt"));
        assertTrue(workspace.resolveForCreate("nested/new.txt").startsWith(root.toRealPath()));

        try {
            workspace.resolveExisting("../outside.txt");
            fail("path traversal must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("escapes workspace"));
        }
    }

    @Test
    public void rejectsSymbolicLinkEscape() throws Exception {
        Path root = temporaryFolder.newFolder("workspace-link").toPath();
        Path outside = temporaryFolder.newFile("outside.txt").toPath();
        Path link = root.resolve("outside-link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException e) {
            return;
        }

        Workspace workspace = Workspace.builder().root(root).build();
        try {
            workspace.resolveExisting("outside-link.txt");
            fail("symbolic link escape must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("escapes workspace"));
        }
    }
}
