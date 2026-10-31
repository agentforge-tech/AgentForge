package cloud.changlu.agentforge.service.modules.upload.controller;

import cloud.changlu.agentforge.service.modules.upload.domain.vo.UploadFileVo;
import cloud.changlu.agentforge.service.modules.upload.service.FileUploadService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Standard file upload API. */
@RestController
@RequestMapping("/api/file")
@CrossOrigin
public class FileController {

    private static final long DEFAULT_USER_ID = 1L;

    private final FileUploadService fileUploadService;

    public FileController(FileUploadService fileUploadService) {
        this.fileUploadService = fileUploadService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadFileVo upload(@RequestParam("file") MultipartFile file) {
        return fileUploadService.upload(file, currentUserId());
    }

    /** Current service edition has no login context and consistently uses the default user. */
    private Long currentUserId() {
        return DEFAULT_USER_ID;
    }
}
