package cloud.changlu.agentforge.service.modules.upload.service;

import cloud.changlu.agentforge.service.common.exception.AgentForgeException;
import cloud.changlu.agentforge.service.env.EnvironmentContext;
import cloud.changlu.agentforge.service.modules.upload.config.LocalUploadProperties;
import cloud.changlu.agentforge.service.modules.upload.domain.vo.UploadFileVo;
import cloud.changlu.agentforge.service.modules.upload.service.impl.LocalFileUploadServiceImpl;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LocalFileUploadServiceImplTest {

    @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldUploadFileToBusinessAndDateDirectory() throws Exception {
        Path uploadRoot = temporaryFolder.newFolder("upload").toPath();
        LocalFileUploadServiceImpl service = service(uploadRoot);
        byte[] content = "hello AgentForge".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "demo.TXT", "text/plain", content);

        UploadFileVo result = service.upload(file, 1L);

        assertTrue(result.getFileName().startsWith("1/"));
        assertTrue(result.getFileName().endsWith(".txt"));
        assertEquals("http://127.0.0.1:8080/upload/" + result.getFileName(), result.getVisitUrl());
        assertEquals("demo.TXT", result.getOriginFileName());
        assertEquals("txt", result.getFileType());
        assertEquals(content.length, result.getFileSize());
        assertArrayEquals(content, Files.readAllBytes(uploadRoot.resolve(result.getFileName())));
    }

    @Test(expected = AgentForgeException.class)
    public void shouldRejectInvalidUser() throws Exception {
        LocalFileUploadServiceImpl service = service(temporaryFolder.newFolder("upload").toPath());
        MockMultipartFile file =
                new MockMultipartFile("file", "demo.txt", "text/plain", "x".getBytes());

        service.upload(file, 0L);
    }

    @Test(expected = AgentForgeException.class)
    public void shouldRejectEmptyFile() throws Exception {
        LocalFileUploadServiceImpl service = service(temporaryFolder.newFolder("upload").toPath());
        MockMultipartFile file =
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        service.upload(file, 1L);
    }

    @Test(expected = AgentForgeException.class)
    public void shouldRejectFileLargerThanTenMegabytes() throws Exception {
        LocalFileUploadServiceImpl service = service(temporaryFolder.newFolder("upload").toPath());
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "large.bin",
                        "application/octet-stream",
                        new byte[10 * 1024 * 1024 + 1]);

        service.upload(file, 1L);
    }

    private LocalFileUploadServiceImpl service(Path uploadRoot) {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("agentforge.file.local.storage-path", uploadRoot.toString())
                        .withProperty("agentforge.file.local.visit-path", "/upload");
        return new LocalFileUploadServiceImpl(
                new LocalUploadProperties(new EnvironmentContext(environment)));
    }
}
