package cloud.changlu.agentforge.service.domain.req;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话消息携带的文件引用。
 *
 * <p>前端上传成功后把 fileId 与展示元信息一起回传，服务端仍以 fileId 做归属校验， 元信息用于消息快照与历史回显。
 *
 * @author changlu
 * @since 2026-11-22
 */
public class ChatFileRef {

    private Long fileId;
    private String originFileName;
    private String fileType;
    private String visitUrl;

    /** 从文件引用列表中提取去重后的 fileId，忽略空值与空列表。 */
    public static List<Long> extractFileIds(List<ChatFileRef> files) {
        if (files == null || files.isEmpty()) {
            return new ArrayList<Long>();
        }
        Set<Long> ids = new LinkedHashSet<Long>();
        for (ChatFileRef file : files) {
            if (file != null && file.getFileId() != null) {
                ids.add(file.getFileId());
            }
        }
        return new ArrayList<Long>(ids);
    }

    public Long getFileId() {
        return fileId;
    }

    public void setFileId(Long fileId) {
        this.fileId = fileId;
    }

    public String getOriginFileName() {
        return originFileName;
    }

    public void setOriginFileName(String originFileName) {
        this.originFileName = originFileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public String getVisitUrl() {
        return visitUrl;
    }

    public void setVisitUrl(String visitUrl) {
        this.visitUrl = visitUrl;
    }
}
