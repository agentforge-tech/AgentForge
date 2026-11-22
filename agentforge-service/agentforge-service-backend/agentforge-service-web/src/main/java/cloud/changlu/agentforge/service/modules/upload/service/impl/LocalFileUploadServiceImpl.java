package cloud.changlu.agentforge.service.modules.upload.service.impl;

import cloud.changlu.agentforge.service.common.exception.AgentForgeException;
import cloud.changlu.agentforge.service.modules.upload.config.LocalUploadProperties;
import cloud.changlu.agentforge.service.modules.upload.domain.pojo.FileInfoPojo;
import cloud.changlu.agentforge.service.modules.upload.domain.vo.UploadFileVo;
import cloud.changlu.agentforge.service.modules.upload.service.FileInfoService;
import cloud.changlu.agentforge.service.modules.upload.service.FileUploadService;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Local-disk implementation of {@link FileUploadService}. */
@Service
public class LocalFileUploadServiceImpl implements FileUploadService {

    public static final String CODE_UPLOAD_FAILED = "FILE_UPLOAD_FAILED";
    public static final String CODE_INVALID_FILE = "INVALID_UPLOAD_FILE";

    private static final String STORAGE_MODE_LOCAL = "local";
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[A-Za-z0-9]{1,16}");

    private final LocalUploadProperties properties;
    private final FileInfoService fileInfoService;

    public LocalFileUploadServiceImpl(
            LocalUploadProperties properties, FileInfoService fileInfoService) {
        this.properties = properties;
        this.fileInfoService = fileInfoService;
    }

    @Override
    public UploadFileVo upload(MultipartFile file, Long userId) {
        validateFile(file);
        validateUserId(userId);

        String originFileName = normalizeOriginalFileName(file.getOriginalFilename());
        String fileType = extractFileType(originFileName);
        String storageFileName =
                UUID.randomUUID().toString().replace("-", "") + extension(fileType);
        Path relativeFile = Paths.get(String.valueOf(userId), storageFileName);
        Path targetFile = properties.getStorageRoot().resolve(relativeFile).normalize();
        ensureInsideStorageRoot(targetFile);

        try {
            Files.createDirectories(targetFile.getParent());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, targetFile);
            }
        } catch (IOException e) {
            throw new AgentForgeException(CODE_UPLOAD_FAILED, "File upload failed", e);
        }

        String relativeName = relativeFile.toString().replace('\\', '/');
        String visitUrl = properties.buildVisitUrl(relativeName);

        // 先落盘再写库；若写库失败，删除已落盘文件，避免出现「有文件无记录」的孤儿文件。
        Long fileId;
        try {
            FileInfoPojo pojo = new FileInfoPojo();
            pojo.setFileName(relativeName);
            pojo.setOriginFileName(originFileName);
            pojo.setVisitUrl(visitUrl);
            pojo.setFileType(fileType);
            pojo.setFileSize(file.getSize());
            pojo.setStorageMode(STORAGE_MODE_LOCAL);
            pojo.setCreateUserId(userId);
            fileId = fileInfoService.save(pojo);
        } catch (RuntimeException e) {
            try {
                Files.deleteIfExists(targetFile);
            } catch (IOException ignored) {
                // 删除失败只记录，不掩盖原始异常
            }
            throw new AgentForgeException(CODE_UPLOAD_FAILED, "File metadata save failed", e);
        }

        return new UploadFileVo(
                fileId, visitUrl, relativeName, originFileName, fileType, file.getSize());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AgentForgeException(CODE_INVALID_FILE, "Uploaded file must not be empty");
        }
        if (file.getSize() > properties.getMaxFileSize()) {
            throw new AgentForgeException(
                    CODE_INVALID_FILE, "Uploaded file exceeds the configured size limit");
        }
    }

    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new AgentForgeException(CODE_INVALID_FILE, "Invalid upload user");
        }
    }

    private String normalizeOriginalFileName(String rawFileName) {
        if (!StringUtils.hasText(rawFileName)) {
            return "file";
        }
        String normalized = rawFileName.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        return StringUtils.hasText(fileName) ? fileName : "file";
    }

    private String extractFileType(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        String fileType = fileName.substring(index + 1).toLowerCase(Locale.ROOT);
        return SAFE_EXTENSION.matcher(fileType).matches() ? fileType : "";
    }

    private String extension(String fileType) {
        return StringUtils.hasText(fileType) ? "." + fileType : "";
    }

    private void ensureInsideStorageRoot(Path targetFile) {
        if (!targetFile.startsWith(properties.getStorageRoot())) {
            throw new AgentForgeException(CODE_INVALID_FILE, "Illegal upload path");
        }
    }
}
