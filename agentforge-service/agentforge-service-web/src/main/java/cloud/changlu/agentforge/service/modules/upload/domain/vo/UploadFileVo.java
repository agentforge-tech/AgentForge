package cloud.changlu.agentforge.service.modules.upload.domain.vo;

/** File metadata returned after an upload succeeds. */
public class UploadFileVo {

    private final String visitUrl;
    private final String fileName;
    private final String originFileName;
    private final String fileType;
    private final long fileSize;

    public UploadFileVo(
            String visitUrl,
            String fileName,
            String originFileName,
            String fileType,
            long fileSize) {
        this.visitUrl = visitUrl;
        this.fileName = fileName;
        this.originFileName = originFileName;
        this.fileType = fileType;
        this.fileSize = fileSize;
    }

    public String getVisitUrl() {
        return visitUrl;
    }

    public String getFileName() {
        return fileName;
    }

    public String getOriginFileName() {
        return originFileName;
    }

    public String getFileType() {
        return fileType;
    }

    public long getFileSize() {
        return fileSize;
    }
}
