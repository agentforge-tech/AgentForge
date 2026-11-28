package cloud.changlu.agentforge.service.modules.agent.interrupt.store.mysql;

import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchStatus;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDenial;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskQuestion;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserAnswerType;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserOption;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserRequest;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentInterruptSnapshotCodec {
    public static final int CURRENT_SCHEMA_VERSION = 2;

    private final ObjectMapper objectMapper;

    public AgentInterruptSnapshotCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy();
    }

    public int schemaVersion() {
        return CURRENT_SCHEMA_VERSION;
    }

    public String memoryId(Object memoryId) {
        if (memoryId == null) {
            throw new IllegalArgumentException("interrupt snapshot memoryId must not be null");
        }
        if (!(memoryId instanceof String)) {
            throw new IllegalArgumentException(
                    "interrupt snapshot memoryId must be String, actual: "
                            + memoryId.getClass().getName());
        }
        return (String) memoryId;
    }

    public String encode(AgentInterruptSnapshot snapshot) {
        StoredSnapshot stored = new StoredSnapshot();
        stored.setSchemaVersion(CURRENT_SCHEMA_VERSION);
        stored.setContextAttributes(snapshot.getContextAttributes());
        stored.setToolRequests(toStoredToolRequests(snapshot.getToolRequests()));
        stored.setInterrupts(toStoredInterrupts(snapshot.getInterrupts()));
        stored.setDenials(toStoredDenials(snapshot.getDenials()));
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "interrupt snapshot encode failed: " + snapshot.getBatchId(), e);
        }
    }

    public AgentInterruptSnapshot decode(AgentInterruptSnapshotPojo pojo) {
        try {
            StoredSnapshot stored =
                    objectMapper.readValue(pojo.getSnapshotJson(), StoredSnapshot.class);
            if (stored.getSchemaVersion() == null
                    || !Objects.equals(stored.getSchemaVersion(), pojo.getSchemaVersion())
                    || stored.getSchemaVersion() != CURRENT_SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "unsupported interrupt snapshot schemaVersion: "
                                + stored.getSchemaVersion());
            }
            String batchId = pojo.getBatchId();
            List<ToolExecutionRequest> toolRequests =
                    toToolRequests(
                            require(stored.getToolRequests(), "toolRequests", batchId), batchId);
            List<InterruptRequest> interrupts =
                    toInterrupts(
                            require(stored.getInterrupts(), "interrupts", batchId),
                            toolRequests,
                            batchId);
            List<PermissionDenial> denials =
                    toDenials(
                            require(stored.getDenials(), "denials", batchId),
                            toolRequests,
                            batchId);
            return new AgentInterruptSnapshot(
                    batchId,
                    pojo.getMemoryId(),
                    pojo.getSourceExecutionId(),
                    pojo.getStep(),
                    status(pojo.getStatus(), batchId),
                    toolRequests,
                    interrupts,
                    denials,
                    stored.getContextAttributes());
        } catch (IllegalStateException e) {
            throw e;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "interrupt snapshot decode failed: " + pojo.getBatchId(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "interrupt snapshot decode failed: " + pojo.getBatchId(), e);
        }
    }

    public InterruptBatchStatus status(String value, String batchId) {
        if (value == null) {
            throw new IllegalStateException("missing interrupt batch status for batch: " + batchId);
        }
        try {
            return InterruptBatchStatus.valueOf(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "unknown interrupt batch status '" + value + "' for batch: " + batchId, e);
        }
    }

    private static List<StoredToolRequest> toStoredToolRequests(
            List<ToolExecutionRequest> toolRequests) {
        List<StoredToolRequest> stored = new ArrayList<StoredToolRequest>();
        for (ToolExecutionRequest request : toolRequests) {
            stored.add(toStoredToolRequest(request));
        }
        return stored;
    }

    private static List<StoredInterrupt> toStoredInterrupts(List<InterruptRequest> interrupts) {
        List<StoredInterrupt> stored = new ArrayList<StoredInterrupt>();
        for (InterruptRequest interrupt : interrupts) {
            StoredInterrupt item = new StoredInterrupt();
            item.setInterruptId(interrupt.getInterruptId());
            item.setType(interrupt.getType().name());
            item.setToolRequest(toStoredToolRequest(interrupt.getToolRequest()));
            item.setPayload(toStoredPayload(interrupt));
            stored.add(item);
        }
        return stored;
    }

    private static List<StoredDenial> toStoredDenials(List<PermissionDenial> denials) {
        List<StoredDenial> stored = new ArrayList<StoredDenial>();
        for (PermissionDenial denial : denials) {
            StoredDenial item = new StoredDenial();
            item.setToolRequest(toStoredToolRequest(denial.getRequest()));
            item.setReason(denial.getReason());
            stored.add(item);
        }
        return stored;
    }

    private static StoredToolRequest toStoredToolRequest(ToolExecutionRequest request) {
        if (request == null) {
            throw new IllegalStateException("interrupt snapshot toolRequest must not be null");
        }
        StoredToolRequest stored = new StoredToolRequest();
        stored.setId(request.id());
        stored.setName(request.name());
        stored.setArguments(request.arguments());
        return stored;
    }

    private static Object toStoredPayload(InterruptRequest interrupt) {
        if (interrupt.getType() == InterruptType.PERMISSION_APPROVAL) {
            if (interrupt.getPayload() != null) {
                throw new IllegalStateException(
                        "permission approval interrupt payload must be null: "
                                + interrupt.getInterruptId());
            }
            return null;
        }
        if (!(interrupt.getPayload() instanceof AskUserRequest)) {
            throw new IllegalStateException(
                    "ask user interrupt payload must be AskUserRequest: "
                            + interrupt.getInterruptId());
        }
        return toStoredAskUserRequest((AskUserRequest) interrupt.getPayload());
    }

    private static StoredAskUserRequest toStoredAskUserRequest(AskUserRequest request) {
        StoredAskUserRequest stored = new StoredAskUserRequest();
        stored.setToolCallId(request.getToolCallId());
        stored.setToolName(request.getToolName());
        stored.setReason(request.getReason());
        stored.setSubmitLabel(request.getSubmitLabel());
        List<StoredAskQuestion> questions = new ArrayList<StoredAskQuestion>();
        for (AskQuestion question : request.getQuestions()) {
            questions.add(toStoredAskQuestion(question));
        }
        stored.setQuestions(questions);
        return stored;
    }

    private static StoredAskQuestion toStoredAskQuestion(AskQuestion question) {
        StoredAskQuestion stored = new StoredAskQuestion();
        stored.setId(question.getId());
        stored.setTitle(question.getTitle());
        stored.setDescription(question.getDescription());
        stored.setType(question.getType() == null ? null : question.getType().name());
        stored.setRequired(question.getRequired());
        stored.setMinSelections(question.getMinSelections());
        stored.setMaxSelections(question.getMaxSelections());
        stored.setMaxLength(question.getMaxLength());
        stored.setPlaceholder(question.getPlaceholder());
        stored.setOptions(toStoredOptions(question.getOptions()));
        return stored;
    }

    private static List<StoredAskUserOption> toStoredOptions(List<AskUserOption> options) {
        if (options == null) {
            return null;
        }
        List<StoredAskUserOption> stored = new ArrayList<StoredAskUserOption>();
        for (AskUserOption option : options) {
            StoredAskUserOption item = new StoredAskUserOption();
            item.setId(option.getId());
            item.setLabel(option.getLabel());
            item.setDescription(option.getDescription());
            stored.add(item);
        }
        return stored;
    }

    private static List<ToolExecutionRequest> toToolRequests(
            List<StoredToolRequest> stored, String batchId) {
        List<ToolExecutionRequest> toolRequests = new ArrayList<ToolExecutionRequest>();
        for (StoredToolRequest request : stored) {
            toolRequests.add(toToolRequest(request, batchId));
        }
        return toolRequests;
    }

    private static ToolExecutionRequest toToolRequest(StoredToolRequest request, String batchId) {
        if (request == null) {
            throw new IllegalStateException(
                    "interrupt snapshot toolRequest must not be null for batch: " + batchId);
        }
        return ToolExecutionRequest.from(
                request.getId(), request.getName(), request.getArguments());
    }

    private List<InterruptRequest> toInterrupts(
            List<StoredInterrupt> stored, List<ToolExecutionRequest> toolRequests, String batchId) {
        List<InterruptRequest> interrupts = new ArrayList<InterruptRequest>();
        for (StoredInterrupt item : stored) {
            if (item == null) {
                throw new IllegalStateException(
                        "interrupt snapshot interrupt item must not be null for batch: " + batchId);
            }
            InterruptType type = interruptType(item.getType(), batchId);
            ToolExecutionRequest toolRequest = toToolRequest(item.getToolRequest(), batchId);
            if (!toolRequests.contains(toolRequest)) {
                throw new IllegalStateException(
                        "interrupt toolRequest not found in toolRequests for batch: " + batchId);
            }
            Object payload = toPayload(type, item.getPayload(), toolRequest, batchId);
            interrupts.add(new InterruptRequest(item.getInterruptId(), type, toolRequest, payload));
        }
        return interrupts;
    }

    private Object toPayload(
            InterruptType type,
            Object storedPayload,
            ToolExecutionRequest toolRequest,
            String batchId) {
        if (type == InterruptType.PERMISSION_APPROVAL) {
            if (storedPayload != null) {
                throw new IllegalStateException(
                        "permission approval interrupt payload must be null for batch: " + batchId);
            }
            return null;
        }
        if (storedPayload == null) {
            throw new IllegalStateException(
                    "ask user interrupt payload must not be null for batch: " + batchId);
        }
        StoredAskUserRequest storedAskUser =
                objectMapper.convertValue(storedPayload, StoredAskUserRequest.class);
        AskUserRequest askUser = toAskUserRequest(storedAskUser, batchId);
        if (!Objects.equals(askUser.getToolCallId(), toolRequest.id())) {
            throw new IllegalStateException(
                    "ask user payload toolCallId does not match toolRequest id for batch: "
                            + batchId);
        }
        return askUser;
    }

    private AskUserRequest toAskUserRequest(StoredAskUserRequest stored, String batchId) {
        List<AskQuestion> questions = new ArrayList<AskQuestion>();
        for (StoredAskQuestion question :
                require(stored.getQuestions(), "askUser.questions", batchId)) {
            questions.add(toAskQuestion(question, batchId));
        }
        return new AskUserRequest(
                stored.getToolCallId(),
                stored.getToolName(),
                questions,
                stored.getReason(),
                stored.getSubmitLabel());
    }

    private static AskQuestion toAskQuestion(StoredAskQuestion stored, String batchId) {
        if (stored == null) {
            throw new IllegalStateException(
                    "ask user question must not be null for batch: " + batchId);
        }
        if (stored.getType() == null) {
            throw new IllegalStateException(
                    "ask user question type must not be null for batch: " + batchId);
        }
        AskUserAnswerType type;
        try {
            type = AskUserAnswerType.valueOf(stored.getType());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "unknown ask user question type '"
                            + stored.getType()
                            + "' for batch: "
                            + batchId,
                    e);
        }
        List<AskUserOption> options = new ArrayList<AskUserOption>();
        if (stored.getOptions() != null) {
            for (StoredAskUserOption option : stored.getOptions()) {
                options.add(
                        new AskUserOption(
                                option.getId(), option.getLabel(), option.getDescription()));
            }
        }
        return new AskQuestion(
                stored.getId(),
                stored.getTitle(),
                stored.getDescription(),
                type,
                stored.getRequired(),
                options,
                stored.getMinSelections(),
                stored.getMaxSelections(),
                stored.getMaxLength(),
                stored.getPlaceholder());
    }

    private static InterruptType interruptType(String value, String batchId) {
        if (value == null) {
            throw new IllegalStateException(
                    "interrupt type must not be null for batch: " + batchId);
        }
        try {
            return InterruptType.valueOf(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "unknown interrupt type '" + value + "' for batch: " + batchId, e);
        }
    }

    private static List<PermissionDenial> toDenials(
            List<StoredDenial> stored, List<ToolExecutionRequest> toolRequests, String batchId) {
        List<PermissionDenial> denials = new ArrayList<PermissionDenial>();
        for (StoredDenial item : stored) {
            if (item == null) {
                throw new IllegalStateException(
                        "interrupt snapshot denial item must not be null for batch: " + batchId);
            }
            ToolExecutionRequest toolRequest = toToolRequest(item.getToolRequest(), batchId);
            if (!toolRequests.contains(toolRequest)) {
                throw new IllegalStateException(
                        "denial toolRequest not found in toolRequests for batch: " + batchId);
            }
            denials.add(new PermissionDenial(toolRequest, item.getReason()));
        }
        return denials;
    }

    private static <T> T require(T value, String field, String batchId) {
        if (value == null) {
            throw new IllegalStateException(
                    "interrupt snapshot " + field + " must not be null for batch: " + batchId);
        }
        return value;
    }

    public static final class StoredSnapshot {
        private Integer schemaVersion;
        private Map<String, String> contextAttributes;
        private List<StoredToolRequest> toolRequests;
        private List<StoredInterrupt> interrupts;
        private List<StoredDenial> denials;

        public Integer getSchemaVersion() {
            return schemaVersion;
        }

        public void setSchemaVersion(Integer schemaVersion) {
            this.schemaVersion = schemaVersion;
        }

        public Map<String, String> getContextAttributes() {
            return contextAttributes == null
                    ? new LinkedHashMap<String, String>()
                    : contextAttributes;
        }

        public void setContextAttributes(Map<String, String> contextAttributes) {
            this.contextAttributes =
                    contextAttributes == null
                            ? new LinkedHashMap<String, String>()
                            : new LinkedHashMap<String, String>(contextAttributes);
        }

        public List<StoredToolRequest> getToolRequests() {
            return toolRequests;
        }

        public void setToolRequests(List<StoredToolRequest> toolRequests) {
            this.toolRequests = toolRequests;
        }

        public List<StoredInterrupt> getInterrupts() {
            return interrupts;
        }

        public void setInterrupts(List<StoredInterrupt> interrupts) {
            this.interrupts = interrupts;
        }

        public List<StoredDenial> getDenials() {
            return denials;
        }

        public void setDenials(List<StoredDenial> denials) {
            this.denials = denials;
        }
    }

    public static final class StoredToolRequest {
        private String id;
        private String name;
        private String arguments;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }
    }

    public static final class StoredInterrupt {
        private String interruptId;
        private String type;
        private StoredToolRequest toolRequest;
        private Object payload;

        public String getInterruptId() {
            return interruptId;
        }

        public void setInterruptId(String interruptId) {
            this.interruptId = interruptId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public StoredToolRequest getToolRequest() {
            return toolRequest;
        }

        public void setToolRequest(StoredToolRequest toolRequest) {
            this.toolRequest = toolRequest;
        }

        public Object getPayload() {
            return payload;
        }

        public void setPayload(Object payload) {
            this.payload = payload;
        }
    }

    public static final class StoredDenial {
        private StoredToolRequest toolRequest;
        private String reason;

        public StoredToolRequest getToolRequest() {
            return toolRequest;
        }

        public void setToolRequest(StoredToolRequest toolRequest) {
            this.toolRequest = toolRequest;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public static final class StoredAskUserRequest {
        private String toolCallId;
        private String toolName;
        private List<StoredAskQuestion> questions;
        private String reason;
        private String submitLabel;

        public String getToolCallId() {
            return toolCallId;
        }

        public void setToolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
        }

        public String getToolName() {
            return toolName;
        }

        public void setToolName(String toolName) {
            this.toolName = toolName;
        }

        public List<StoredAskQuestion> getQuestions() {
            return questions;
        }

        public void setQuestions(List<StoredAskQuestion> questions) {
            this.questions = questions;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public String getSubmitLabel() {
            return submitLabel;
        }

        public void setSubmitLabel(String submitLabel) {
            this.submitLabel = submitLabel;
        }
    }

    public static final class StoredAskQuestion {
        private String id;
        private String title;
        private String description;
        private String type;
        private Boolean required;
        private List<StoredAskUserOption> options;
        private Integer minSelections;
        private Integer maxSelections;
        private Integer maxLength;
        private String placeholder;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Boolean getRequired() {
            return required;
        }

        public void setRequired(Boolean required) {
            this.required = required;
        }

        public List<StoredAskUserOption> getOptions() {
            return options;
        }

        public void setOptions(List<StoredAskUserOption> options) {
            this.options = options;
        }

        public Integer getMinSelections() {
            return minSelections;
        }

        public void setMinSelections(Integer minSelections) {
            this.minSelections = minSelections;
        }

        public Integer getMaxSelections() {
            return maxSelections;
        }

        public void setMaxSelections(Integer maxSelections) {
            this.maxSelections = maxSelections;
        }

        public Integer getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(Integer maxLength) {
            this.maxLength = maxLength;
        }

        public String getPlaceholder() {
            return placeholder;
        }

        public void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
        }
    }

    public static final class StoredAskUserOption {
        private String id;
        private String label;
        private String description;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
