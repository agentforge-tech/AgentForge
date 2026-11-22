package cloud.changlu.agentforge.service.modules.agent.attachment;

import cloud.changlu.agentforge.agent.config.LlmModelConfig;
import cloud.changlu.agentforge.agent.domain.AgentAttachment;
import cloud.changlu.agentforge.agent.domain.AgentAttachmentType;
import cloud.changlu.agentforge.agent.domain.AttachmentPayloadType;
import cloud.changlu.agentforge.service.common.exception.AgentForgeException;
import cloud.changlu.agentforge.service.domain.req.ChatStreamRequest;
import cloud.changlu.agentforge.service.modules.upload.config.LocalUploadProperties;
import cloud.changlu.agentforge.service.modules.upload.domain.pojo.FileInfoPojo;
import cloud.changlu.agentforge.service.modules.upload.service.FileInfoService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话附件解析服务：{@code fileIds} → 已校验附件 → 框架层附件对象。
 *
 * <p>负责归属校验、数量上限、附件类型判定、载荷路由与文字附件正文读取。
 *
 * @author changlu
 * @since 2026-11-21
 */
@Service
public class AgentChatAttachmentService {

    private static final int MAX_ATTACHMENTS_PER_MESSAGE = 5;
    private static final int MAX_TEXT_ATTACHMENT_LENGTH = 12000;

    private final FileInfoService fileInfoService;
    private final AgentAttachmentRoutingService routingService;
    private final LocalUploadProperties uploadProperties;
    private final ObjectMapper objectMapper;

    public AgentChatAttachmentService(
            FileInfoService fileInfoService,
            AgentAttachmentRoutingService routingService,
            LocalUploadProperties uploadProperties,
            ObjectMapper objectMapper) {
        this.fileInfoService = fileInfoService;
        this.routingService = routingService;
        this.uploadProperties = uploadProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验归属并把 fileIds 解析为可访问附件。
     *
     * @throws AgentForgeException 附件不存在、越权、数量超限
     */
    public List<ResolvedChatAttachment> resolve(List<Long> fileIds, Long userId) {
        if (fileIds == null || fileIds.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> normalizedSet = new LinkedHashSet<Long>();
        for (Long fileId : fileIds) {
            if (fileId != null) {
                normalizedSet.add(fileId);
            }
        }
        if (normalizedSet.isEmpty()) {
            return Collections.emptyList();
        }
        if (normalizedSet.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
            throw new AgentForgeException(
                    "TOO_MANY_ATTACHMENTS", "单条消息最多携带 " + MAX_ATTACHMENTS_PER_MESSAGE + " 个附件");
        }
        List<Long> normalized = new ArrayList<Long>(normalizedSet);
        Map<Long, FileInfoPojo> found = new LinkedHashMap<Long, FileInfoPojo>();
        for (FileInfoPojo pojo : fileInfoService.selectByIds(normalized)) {
            found.put(pojo.getId(), pojo);
        }
        List<ResolvedChatAttachment> result = new ArrayList<ResolvedChatAttachment>();
        for (Long fileId : normalized) {
            FileInfoPojo pojo = found.get(fileId);
            if (pojo == null) {
                throw new AgentForgeException(
                        "ATTACHMENT_NOT_FOUND", "附件不存在或已删除, fileId=" + fileId);
            }
            if (pojo.getCreateUserId() != null
                    && userId != null
                    && !pojo.getCreateUserId().equals(userId)) {
                throw new AgentForgeException("ATTACHMENT_FORBIDDEN", "无权引用该附件, fileId=" + fileId);
            }
            result.add(
                    new ResolvedChatAttachment(
                            pojo.getId(),
                            StringUtils.hasText(pojo.getOriginFileName())
                                    ? pojo.getOriginFileName()
                                    : pojo.getFileName(),
                            pojo.getVisitUrl(),
                            pojo.getFileType(),
                            pojo.getFileSize()));
        }
        return result;
    }

    /** 转成 Agent 框架层附件对象，附带已解析的载荷形态。 */
    public List<AgentAttachment> toAgentAttachments(
            List<ResolvedChatAttachment> resolved,
            LlmModelConfig modelConfig,
            Integer providerCode) {
        if (resolved == null || resolved.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentAttachment> attachments = new ArrayList<AgentAttachment>(resolved.size());
        for (ResolvedChatAttachment item : resolved) {
            AgentAttachmentType type = resolveAttachmentType(item.getFileType());
            long size = item.getFileSize() == null ? 0L : item.getFileSize();
            AttachmentPayloadType payload =
                    routingService.route(type, item.getVisitUrl(), size, modelConfig, providerCode);

            AgentAttachment.Builder builder =
                    AgentAttachment.builder()
                            .fileId(item.getFileId())
                            .name(item.getName())
                            .type(type)
                            .fileType(item.getFileType())
                            .size(size)
                            .url(item.getVisitUrl())
                            .payloadType(payload);
            // TEXT 形态在解析阶段就把正文准备好，避免进入 Agent 后再做 IO
            if (payload == AttachmentPayloadType.TEXT) {
                builder.text(buildTextPayload(item, type)).url(null);
            }
            attachments.add(builder.build());
        }
        return attachments;
    }

    /**
     * 构造用户消息的 req 快照 JSON（含原始请求字段与已解析附件元信息）。
     *
     * <p>历史回放时前端从 {@code req.attachments} 渲染用户上传的附件卡片。
     */
    public String buildUserReqJson(ChatStreamRequest body, List<ResolvedChatAttachment> resolved) {
        Map<String, Object> req = new LinkedHashMap<String, Object>();
        if (body != null) {
            req.put("aid", body.getAid());
            req.put("sessionId", body.getSessionId());
            req.put("message", body.getMessage());
            req.put("isDebug", body.getIsDebug());
        }
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        if (resolved != null) {
            for (ResolvedChatAttachment item : resolved) {
                Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
                snapshot.put("fileId", item.getFileId());
                snapshot.put("originFileName", item.getOriginFileName());
                snapshot.put("fileType", item.getFileType());
                snapshot.put("visitUrl", item.getVisitUrl());
                files.add(snapshot);
            }
        }
        req.put("files", files);
        try {
            return objectMapper.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            // 序列化失败不应阻断对话，回退为空 req
            return null;
        }
    }

    /** 紧凑 JSON 化的文件快照，供前端直接解析。 */
    public List<Map<String, Object>> toSnapshot(List<ResolvedChatAttachment> resolved) {
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        if (resolved == null) {
            return files;
        }
        for (ResolvedChatAttachment item : resolved) {
            Map<String, Object> snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("fileId", item.getFileId());
            snapshot.put("originFileName", item.getOriginFileName());
            snapshot.put("fileType", item.getFileType());
            snapshot.put("visitUrl", item.getVisitUrl());
            files.add(snapshot);
        }
        return files;
    }

    /** 附件类型判定：现网 fileType 是不带点的扩展名，同时兼容 MIME 写法。 */
    public AgentAttachmentType resolveAttachmentType(String fileType) {
        if (!StringUtils.hasText(fileType)) {
            return AgentAttachmentType.UNKNOWN;
        }
        String type = fileType.trim().toLowerCase();
        if (type.startsWith("image/")
                || type.equals("png")
                || type.equals("jpg")
                || type.equals("jpeg")
                || type.equals("gif")
                || type.equals("webp")
                || type.equals("bmp")) {
            return AgentAttachmentType.IMAGE;
        }
        if (type.startsWith("text/")
                || type.equals("txt")
                || type.equals("md")
                || type.equals("markdown")) {
            return AgentAttachmentType.TEXT;
        }
        return AgentAttachmentType.UNKNOWN;
    }

    private String buildTextPayload(ResolvedChatAttachment item, AgentAttachmentType type) {
        StringBuilder builder = new StringBuilder(buildMetadataTextPayload(item));
        if (type == AgentAttachmentType.TEXT) {
            String textContent = readTextAttachmentContent(item);
            if (StringUtils.hasText(textContent)) {
                builder.append("\n以下是附件正文内容：\n").append(textContent);
            }
        }
        return builder.toString();
    }

    private String buildMetadataTextPayload(ResolvedChatAttachment item) {
        StringBuilder builder = new StringBuilder("文件：");
        builder.append(StringUtils.hasText(item.getName()) ? item.getName() : "未命名文件");
        if (StringUtils.hasText(item.getFileType())) {
            builder.append("（类型 ").append(item.getFileType());
            if (item.getFileSize() != null) {
                builder.append("，大小 ").append(item.getFileSize()).append(" bytes");
            }
            builder.append("）");
        }
        // 非文本附件（如 zip）无法解析正文时，内联访问地址供模型按需引用
        if (StringUtils.hasText(item.getVisitUrl())) {
            builder.append("，访问地址 ").append(item.getVisitUrl());
        }
        return builder.toString();
    }

    private String readTextAttachmentContent(ResolvedChatAttachment item) {
        Path local = uploadProperties.resolveLocalPath(item.getVisitUrl());
        if (local == null || !Files.isRegularFile(local)) {
            return null;
        }
        try {
            long size = Files.size(local);
            if (size > MAX_TEXT_ATTACHMENT_LENGTH * 4L) {
                // 超长文件直接截断读取，避免一次性读入超大文件
                byte[] head = new byte[MAX_TEXT_ATTACHMENT_LENGTH * 4];
                try (java.io.InputStream input = Files.newInputStream(local)) {
                    int read = input.read(head);
                    if (read <= 0) {
                        return null;
                    }
                    String content = new String(head, 0, read, StandardCharsets.UTF_8);
                    return truncate(content, item);
                }
            }
            String content = new String(Files.readAllBytes(local), StandardCharsets.UTF_8);
            return truncate(content, item);
        } catch (IOException e) {
            return null;
        }
    }

    private String truncate(String content, ResolvedChatAttachment item) {
        if (content.length() > MAX_TEXT_ATTACHMENT_LENGTH) {
            StringBuilder builder =
                    new StringBuilder(content.substring(0, MAX_TEXT_ATTACHMENT_LENGTH));
            builder.append("\n...[附件内容过长，已截断");
            if (StringUtils.hasText(item.getVisitUrl())) {
                builder.append("，完整内容请访问：").append(item.getVisitUrl());
            }
            builder.append("]");
            return builder.toString();
        }
        return content;
    }
}
