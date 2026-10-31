package cloud.changlu.agentforge.service.modules.upload.service;

import cloud.changlu.agentforge.service.modules.upload.domain.vo.UploadFileVo;

import org.springframework.web.multipart.MultipartFile;

/** File storage abstraction. Other storage modes can implement this contract later. */
public interface FileUploadService {

    /**
     * Stores a file under the current user's directory.
     *
     * @param file uploaded file
     * @param userId current user ID
     */
    UploadFileVo upload(MultipartFile file, Long userId);
}
