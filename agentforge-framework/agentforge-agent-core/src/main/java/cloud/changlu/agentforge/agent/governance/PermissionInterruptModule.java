package cloud.changlu.agentforge.agent.governance;

import cloud.changlu.agentforge.agent.domain.AgentChatContext;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentInterruptSnapshot;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.AgentSuspendInfo;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptBatchResumeCommand;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptRequest;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptResponse;
import cloud.changlu.agentforge.agent.governance.interrupt.domain.InterruptType;
import cloud.changlu.agentforge.agent.governance.interrupt.spi.AgentInterruptStore;
import cloud.changlu.agentforge.agent.governance.interrupt.support.InMemoryAgentInterruptStore;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDecision;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionDenial;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionMode;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionPreflightResult;
import cloud.changlu.agentforge.agent.governance.permission.domain.PermissionRule;
import cloud.changlu.agentforge.agent.governance.permission.domain.RegexPermissionRule;
import cloud.changlu.agentforge.agent.governance.permission.engine.PermissionContext;
import cloud.changlu.agentforge.agent.governance.permission.engine.PermissionEngine;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionApprovalStore;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionModePolicy;
import cloud.changlu.agentforge.agent.governance.permission.spi.PermissionRuleHandler;
import cloud.changlu.agentforge.agent.governance.permission.support.InMemorySessionPermissionApprovalStore;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserResponse;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserSupport;
import cloud.changlu.agentforge.agent.governance.tool.askuser.AskUserTool;
import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 权限与中断能力的统一装配入口。 */
public final class PermissionInterruptModule {
    public static final String SUSPEND_INFO_KEY = "agentforge.interrupt.suspendInfo";
    private final PermissionMode mode;
    private final PermissionEngine engine;
    private final AgentInterruptStore store;
    private final PermissionApprovalStore approvalStore;
    private final boolean askUserEnabled;

    private PermissionInterruptModule(Builder builder) {
        this.mode = builder.mode;
        this.engine = new PermissionEngine(builder.rules, builder.ruleHandlers, builder.modePolicy);
        this.store = builder.store == null ? new InMemoryAgentInterruptStore() : builder.store;
        this.approvalStore =
                builder.approvalStore == null
                        ? new InMemorySessionPermissionApprovalStore()
                        : builder.approvalStore;
        this.askUserEnabled = builder.askUserEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder safeDefaults() {
        return builder().activePermissionMode(PermissionMode.DEFAULT);
    }

    public boolean isAskUserEnabled() {
        return askUserEnabled;
    }

    public AgentInterruptStore getInterruptStore() {
        return store;
    }

    /**
     * 本轮工具执行前的权限审核编排入口。
     *
     * <p>本方法本身不实现权限算法，只负责按原始 Tool Call 顺序分流；真正的 ALLOW / DENY / ASK 由 {@link
     * PermissionEngine#decide(PermissionContext)} 结合规则和 Mode 给出。
     *
     * <ol>
     *   <li>启用了 AskUser 且工具名为 {@code ask_user}：这是“询问用户”，直接转为 {@link InterruptType#ASK_USER}，跳过权限规则和
     *       Mode 判定；
     *   <li>命中 {@link PermissionApprovalStore}：当前上下文已通过 {@code APPROVE_ALWAYS}
     *       批准过相同工具调用，直接放行且不再重复审核；
     *   <li>其余调用才进入真正的权限判定：调用 {@link PermissionEngine#decide}，先按注册顺序匹配权限规则 得到候选决策，再由自定义 ModePolicy
     *       或内置 Mode 结合候选做场景级最终决策。
     * </ol>
     *
     * <p>决策分支：
     *
     * <ul>
     *   <li>{@code ALLOW}：不产生中断项，恢复或首次执行时直接运行；
     *   <li>{@code ASK}：生成 {@link InterruptType#PERMISSION_APPROVAL} 中断项，等待用户审批；
     *   <li>{@code DENY}：立即抛出 {@link SecurityException}，整批判定失败，不会产生任何工具副作用；
     *   <li>AskUser 参数解析失败：抛出 {@link IllegalArgumentException}，同样在工具执行前失败。
     * </ul>
     *
     * <p>批次语义：
     *
     * <ul>
     *   <li>只要存在任意 ASK 或 ASK_USER，本轮所有工具都整体冻结为快照，包括原本 ALLOW 的调用；
     *   <li>快照保存原始顺序和全部请求，恢复时按原顺序执行 ALLOW/批准项，拒绝项写入拒绝结果；
     *   <li>没有任何中断项时返回 {@code null}，表示整批可以立即执行；
     *   <li>本方法只做判定和冻结，不执行工具，也不写入 Tool Result。
     * </ul>
     */
    public PermissionPreflightResult preflight(
            int step, List<ToolExecutionRequest> requests, AgentChatContext context) {
        List<InterruptRequest> interrupts = new ArrayList<InterruptRequest>();
        List<PermissionDenial> denials = new ArrayList<PermissionDenial>();
        for (ToolExecutionRequest request : requests) {
            InterruptType type = null;
            if (askUserEnabled && AskUserTool.TOOL_NAME.equals(request.name())) {
                if (mode == PermissionMode.DONT_ASK) {
                    // 无人值守模式不能等待用户输入，ask_user 也按 DENY 处理并写失败结果。
                    denials.add(
                            new PermissionDenial(request, "ask_user is disabled in DONT_ASK mode"));
                    continue;
                }
                // 询问用户：交互工具不参与权限审核，直接生成 ASK_USER 中断。
                type = InterruptType.ASK_USER;
            } else if (approvalStore.isApproved(context, request)) {
                // 历史总是允许：默认实现按 memoryId 隔离相同工具 + 相同参数的授权。
                continue;
            } else {
                // 真正的权限判定：由 PermissionEngine.decide 做规则优先、Mode 兜底。
                PermissionDecision decision =
                        engine.decide(
                                new PermissionContext(
                                        context.getMemoryId(),
                                        context.getExecutionId(),
                                        request,
                                        mode));
                if (decision == PermissionDecision.ASK) {
                    type = InterruptType.PERMISSION_APPROVAL;
                } else if (decision == PermissionDecision.DENY) {
                    denials.add(
                            new PermissionDenial(
                                    request,
                                    "tool call denied by permission policy: " + request.name()));
                    continue;
                }
            }
            if (type != null) {
                // ASK_USER 在此完成参数解析与校验；权限审核项不携带类型 payload。
                Object payload =
                        type == InterruptType.ASK_USER ? AskUserSupport.parse(request) : null;
                interrupts.add(
                        new InterruptRequest(
                                "int_" + UUID.randomUUID().toString(), type, request, payload));
            }
        }
        if (interrupts.isEmpty()) {
            return denials.isEmpty()
                    ? PermissionPreflightResult.allowed()
                    : PermissionPreflightResult.denied(denials);
        }
        String batchId = "batch_" + UUID.randomUUID().toString();
        AgentInterruptSnapshot snapshot =
                AgentInterruptSnapshot.initial(
                        batchId,
                        context.getMemoryId(),
                        context.getExecutionId(),
                        step,
                        requests,
                        interrupts,
                        denials);
        store.save(snapshot);
        return PermissionPreflightResult.suspended(
                new AgentSuspendInfo(batchId, interrupts), denials);
    }

    /** 校验一次批量恢复并原子占有快照，防止重复执行工具。 */
    public ResolvedInterruptBatch resolve(InterruptBatchResumeCommand command) {
        if (command == null) throw new IllegalArgumentException("resume command must not be null");
        AgentInterruptSnapshot snapshot = store.get(command.getBatchId());
        Map<String, InterruptResponse> responses = new LinkedHashMap<String, InterruptResponse>();
        for (InterruptResponse response : command.getResponses()) {
            if (responses.put(response.getInterruptId(), response) != null) {
                throw new IllegalArgumentException(
                        "duplicate interrupt response: " + response.getInterruptId());
            }
        }
        for (InterruptRequest interrupt : snapshot.getInterrupts()) {
            InterruptResponse response = responses.get(interrupt.getInterruptId());
            if (response == null) {
                throw new IllegalArgumentException(
                        "missing interrupt response: " + interrupt.getInterruptId());
            }
            if (interrupt.getType() == InterruptType.PERMISSION_APPROVAL
                    && response.getAction() == null) {
                throw new IllegalArgumentException(
                        "approval action is required: " + interrupt.getInterruptId());
            }
            if (interrupt.getType() == InterruptType.ASK_USER) {
                AskUserResponse structured = response.getAskUserResponse();
                if (structured == null) {
                    throw new IllegalArgumentException(
                            "user answer is required: " + interrupt.getInterruptId());
                }
                AskUserSupport.validate(interrupt.getAskUserRequest(), structured);
            }
        }
        if (responses.size() != snapshot.getInterrupts().size()) {
            throw new IllegalArgumentException("resume contains unknown interrupt response");
        }
        store.claim(command.getBatchId());
        return new ResolvedInterruptBatch(snapshot, responses);
    }

    /** 将权限中断的三种审批结果连同恢复上下文交给授权存储处理。 */
    public void applyApprovals(ResolvedInterruptBatch batch, AgentChatContext context) {
        if (batch == null) {
            throw new IllegalArgumentException("resolved interrupt batch must not be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("chat context must not be null");
        }
        for (InterruptRequest interrupt : batch.getSnapshot().getInterrupts()) {
            if (interrupt.getType() != InterruptType.PERMISSION_APPROVAL) {
                continue;
            }
            InterruptResponse response = batch.responseOf(interrupt);
            approvalStore.addApproval(context, interrupt.getToolRequest(), response.getAction());
        }
    }

    public void complete(ResolvedInterruptBatch batch) {
        store.complete(batch.getSnapshot().getBatchId());
    }

    public static final class ResolvedInterruptBatch {
        private final AgentInterruptSnapshot snapshot;
        private final Map<String, InterruptResponse> responses;

        private ResolvedInterruptBatch(
                AgentInterruptSnapshot snapshot, Map<String, InterruptResponse> responses) {
            this.snapshot = snapshot;
            this.responses = responses;
        }

        public AgentInterruptSnapshot getSnapshot() {
            return snapshot;
        }

        public InterruptResponse responseOf(InterruptRequest request) {
            return responses.get(request.getInterruptId());
        }

        public List<InterruptResponse> getResponses() {
            return Collections.unmodifiableList(
                    new ArrayList<InterruptResponse>(responses.values()));
        }
    }

    public static final class Builder {
        private PermissionMode mode = PermissionMode.DEFAULT;
        private final List<PermissionRule> rules = new ArrayList<PermissionRule>();
        private final List<PermissionRuleHandler> ruleHandlers =
                new ArrayList<PermissionRuleHandler>();
        private AgentInterruptStore store;
        private PermissionApprovalStore approvalStore;
        private PermissionModePolicy modePolicy;
        private boolean askUserEnabled;

        public Builder activePermissionMode(PermissionMode mode) {
            this.mode = mode;
            return this;
        }

        public Builder enableAskUser() {
            this.askUserEnabled = true;
            return this;
        }

        public Builder allow(String toolRegex, String argumentRegex) {
            return rule(toolRegex, argumentRegex, PermissionDecision.ALLOW);
        }

        public Builder ask(String toolRegex, String argumentRegex) {
            return rule(toolRegex, argumentRegex, PermissionDecision.ASK);
        }

        public Builder deny(String toolRegex, String argumentRegex) {
            return rule(toolRegex, argumentRegex, PermissionDecision.DENY);
        }

        /** 内置正则规则：工具名全匹配，原始 JSON 参数包含匹配。 */
        public Builder rule(String toolRegex, String argumentRegex, PermissionDecision decision) {
            rules.add(
                    new RegexPermissionRule(
                            "rule_" + (rules.size() + 1), toolRegex, argumentRegex, decision));
            return this;
        }

        /** 注册自定义类型规则。 */
        public Builder rule(PermissionRule rule) {
            if (rule == null) throw new IllegalArgumentException("rule must not be null");
            rules.add(rule);
            return this;
        }

        /** 注册自定义规则类型处理器；内置 regex 处理器始终存在。 */
        public Builder ruleHandler(PermissionRuleHandler handler) {
            if (handler == null) throw new IllegalArgumentException("ruleHandler must not be null");
            ruleHandlers.add(handler);
            return this;
        }

        public Builder interruptStore(AgentInterruptStore store) {
            this.store = store;
            return this;
        }

        public Builder permissionApprovalStore(PermissionApprovalStore approvalStore) {
            if (approvalStore == null) {
                throw new IllegalArgumentException("approvalStore must not be null");
            }
            this.approvalStore = approvalStore;
            return this;
        }

        public Builder permissionModePolicy(PermissionModePolicy policy) {
            this.modePolicy = policy;
            return this;
        }

        public PermissionInterruptModule build() {
            return new PermissionInterruptModule(this);
        }
    }
}
