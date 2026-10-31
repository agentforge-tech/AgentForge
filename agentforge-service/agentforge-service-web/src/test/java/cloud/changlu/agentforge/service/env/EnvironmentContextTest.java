package cloud.changlu.agentforge.service.env;

import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.Assert.assertEquals;

public class EnvironmentContextTest {

    @Test
    public void shouldProvideUploadDefaults() {
        EnvironmentContext context = new EnvironmentContext(new MockEnvironment());

        assertEquals("./upload", context.getUserDirUpload());
        assertEquals("./upload", context.getFileLocalStoragePath());
        assertEquals("/upload", context.getFileLocalVisitPath());
        assertEquals("http", context.getFileLocalVisitProtocol());
        assertEquals("127.0.0.1", context.getFileLocalVisitHost());
        assertEquals(10L * 1024 * 1024, context.getFileUploadMaxSize());
    }

    @Test
    public void shouldAllowUploadConfigurationOverrides() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("user.dir.upload", "/service/upload")
                        .withProperty("agentforge.file.local.storage-path", "/data/upload")
                        .withProperty("agentforge.file.local.visit-path", "/assets")
                        .withProperty("agentforge.file.local.visit-protocol", "https")
                        .withProperty("agentforge.file.local.visit-host", "files.example.com")
                        .withProperty("server.port", "8443")
                        .withProperty("agentforge.file.max-size", "20MB");
        EnvironmentContext context = new EnvironmentContext(environment);

        assertEquals("/service/upload", context.getUserDirUpload());
        assertEquals("/data/upload", context.getFileLocalStoragePath());
        assertEquals("/assets", context.getFileLocalVisitPath());
        assertEquals("https", context.getFileLocalVisitProtocol());
        assertEquals("files.example.com", context.getFileLocalVisitHost());
        assertEquals("8443", context.getServerPort());
        assertEquals(20L * 1024 * 1024, context.getFileUploadMaxSize());
    }
}
