package cloud.changlu.agentforge.model.dashscope.fim;

import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimChunk;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.FimUsage;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 阿里云百炼（DashScope）Partial Mode 真实端点验证入口。
 *
 * <p><b>一、只需填写三项配置</b>
 *
 * <pre>
 * 1) FIM_BASE_URL   : 百炼兼容模式地址（含 /compatible-mode/v1）
 * 2) FIM_API_KEY    : 百炼 API Key（注意与地域绑定）
 * 3) FIM_MODEL_NAME : Partial Mode 支持的模型，例如 qwen3-coder-flash
 * </pre>
 *
 * <p><b>二、也可用系统属性，避免密钥进源码</b>
 *
 * <pre>
 * mvn -pl agentforge-model/agentforge-model-dashscope -am \
 *     -Dfim.baseUrl=https://dashscope.aliyuncs.com/compatible-mode/v1 \
 *     -Dfim.apiKey=sk-xxx \
 *     -Dfim.modelName=qwen3-coder-flash \
 *     -Dtest="cloud.changlu.agentforge.model.dashscope.fim.DashScopeLiveVerificationTest" test
 * </pre>
 *
 * <p><b>三、选择只跑某几组场景</b>：改 {@link #SELECTED_SCENARIOS} 或命令行传 {@code -Dfim.scenarios=2,4}， 支持 {@code
 * [2,4]} 这种带方括号的写法。留空表示全部。编号越界会立刻报错并列出合法范围， 避免"以为跑过了其实没跑"。
 *
 * <pre>
 * mvn ... -Dfim.scenarios=2,4 -Dtest="...DashScopeLiveVerificationTest#shouldVerifyBailianFim" test
 * </pre>
 *
 * <p><b>四、流式是「实时打印」</b>：每个增量块到达就立刻输出并 flush，不做任何缓冲， 因此运行过程中能亲眼看到代码一行行"长出来"，而不是等结束才一次性刷屏。 同时输出：
 *
 * <ul>
 *   <li><b>TTFT</b>：首块到达耗时（行内补全的核心 SLA）；
 *   <li><b>完整耗时</b>：从发起请求到流结束的总时间；
 *   <li><b>块间隔</b>：相邻块到达间隔的均值/最大值，用来判断是否真的在"流"。
 * </ul>
 *
 * <p><b>五、重要前提</b>：Partial Mode 只支持前缀续写，<b>没有原生 suffix</b>。 因此这里验证的是"能否补出中间逻辑"，不是"是否严格尊重后文"。 {@code
 * deepseek-v4.1-flash} 不在官方 Partial Mode 支持清单内，实测可能报错或效果很差。
 *
 * @author changlu
 * @date 2026/11/25
 */
public class DashScopeLiveVerificationTest {

    // ==================== ① 三项配置 ====================

    /** 百炼兼容模式地址（旧域名，官方仍可用；建议迁到业务空间专属域名）。 */
    private static final String FIM_BASE_URL =
            System.getProperty("fim.baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1");

    /** 百炼 API Key：仅从系统属性读取，留空则跳过。 */
    private static final String FIM_API_KEY = System.getProperty("fim.apiKey", "");

    /** Partial Mode 支持清单内的模型。 */
    private static final String FIM_MODEL_NAME =
            System.getProperty("fim.modelName", "qwen3-coder-flash");

    /**
     * 默认引导语长度（与 {@code DashScopePartialWire.DEFAULT_INSTRUCTION} 保持一致）。
     *
     * <p>这里单独维护一份是为了在测试输出里展示"指令本身占多少字符"， 避免把整段前缀的规模误读成提示词规模。
     */
    private static final String INSTRUCTION = "请补全这段代码中间缺失的部分，只输出需要补全的代码，不要输出解释或 Markdown 代码块。";

    /** 本测试默认关闭 suffix 提示（实测开启会导致长上下文空返回）。 */
    private static final boolean SUFFIX_HINT_ENABLED = false;

    /**
     * 只跑指定场景编号：写 {@code {2, 4}} 表示只验证 2 号与 4 号。
     *
     * <p>留空（{@code {}}）表示跑全部。也可命令行传 {@code -Dfim.scenarios=2,4}（支持 {@code [2,4]}）， 命令行优先级更高。编号从 1
     * 开始，越界会直接报错。
     */
    private static final int[] SELECTED_SCENARIOS = new int[] {
                // 例如：new int[] { 2, 4 }  → 只跑 2 号与 4 号
            };

    // ==================== ② 复杂场景（上下文件都较长） ====================

    /**
     * 场景集：刻意把前缀/后缀都写长，逼近真实 IDE 里"光标夹在一大段代码中间"的情形。
     *
     * <p>设计原则：前缀带足够上下文（import、字段、已声明变量、已完成校验）， 后缀带真实收尾（闭合括号、后续调用、下一个方法），这样才验证得出模型
     * "是否理解前文"与"是否尊重后文"。
     */
    private static final Scenario[] SCENARIOS =
            new Scenario[] {
                new Scenario(
                        "1号 Agent：ReAct 工具调用循环（长上下文）",
                        "import java.util.List;\n"
                                + "\n"
                                + "public class ReActAgent {\n"
                                + "\n"
                                + "    private static final int MAX_ITERATIONS = 8;\n"
                                + "    private static final String SYSTEM_PROMPT = \"You are a tool-using agent.\";\n"
                                + "\n"
                                + "    private final ChatModel chatModel;\n"
                                + "    private final List<ToolSpecification> toolSpecifications;\n"
                                + "    private final ToolService toolService;\n"
                                + "\n"
                                + "    public ReActAgent(\n"
                                + "            ChatModel chatModel,\n"
                                + "            List<ToolSpecification> toolSpecifications,\n"
                                + "            ToolService toolService) {\n"
                                + "        this.chatModel = chatModel;\n"
                                + "        this.toolSpecifications = toolSpecifications;\n"
                                + "        this.toolService = toolService;\n"
                                + "    }\n"
                                + "\n"
                                + "    public String run(String userInput) {\n"
                                + "        ChatRequest request = ChatRequest.builder()\n"
                                + "                .message(SystemMessage.from(SYSTEM_PROMPT))\n"
                                + "                .message(UserMessage.from(userInput))\n"
                                + "                .parameters(DefaultChatRequestParameters.builder()\n"
                                + "                        .tools(toolSpecifications)\n"
                                + "                        .build())\n"
                                + "                .build();\n"
                                + "\n"
                                + "        ChatResponse response = chatModel.chat(request);\n"
                                + "        int iteration = 0;\n"
                                + "        ",
                        "\n"
                                + "        return response.aiMessage().text();\n"
                                + "    }\n"
                                + "\n"
                                + "    private void logIteration(int iteration, String toolName) {\n"
                                + "        System.out.println(\"[iter \" + iteration + \"] \" + toolName);\n"
                                + "    }\n"
                                + "}\n"),
                new Scenario(
                        "2号 流式：SSE 增量聚合（长上下文）",
                        "import java.util.List;\n"
                                + "import java.util.Map;\n"
                                + "\n"
                                + "public class FimStreamAggregator {\n"
                                + "\n"
                                + "    private final StringBuilder buffer = new StringBuilder();\n"
                                + "    private String finishReason;\n"
                                + "    private FimUsage usage;\n"
                                + "\n"
                                + "    public FimResponse aggregate(\n"
                                + "            String modelName,\n"
                                + "            long startMillis,\n"
                                + "            List<String> lines) {\n"
                                + "\n"
                                + "        for (String line : lines) {\n"
                                + "            if (line == null || line.isEmpty()) {\n"
                                + "                continue;\n"
                                + "            }\n"
                                + "            if (!line.startsWith(\"data:\")) {\n"
                                + "                continue;\n"
                                + "            }\n"
                                + "            String data = line.substring(\"data:\".length()).trim();\n"
                                + "            if (\"[DONE]\".equals(data)) {\n"
                                + "                break;\n"
                                + "            }\n"
                                + "            ",
                        "\n"
                                + "        }\n"
                                + "\n"
                                + "        return FimResponse.builder()\n"
                                + "                .completion(buffer.toString())\n"
                                + "                .finishReason(mapFinishReason(finishReason))\n"
                                + "                .usage(usage)\n"
                                + "                .model(modelName)\n"
                                + "                .latencyMs(System.currentTimeMillis() - startMillis)\n"
                                + "                .build();\n"
                                + "    }\n"
                                + "}\n"),
                new Scenario(
                        "3号 业务：事务 + 分页 + Stream 映射（长上下文）",
                        "import java.util.List;\n"
                                + "import java.util.stream.Collectors;\n"
                                + "\n"
                                + "@Service\n"
                                + "public class AgentQueryService {\n"
                                + "\n"
                                + "    private final AgentMapper agentMapper;\n"
                                + "    private static final int MAX_PAGE_SIZE = 200;\n"
                                + "\n"
                                + "    public AgentQueryService(AgentMapper agentMapper) {\n"
                                + "        this.agentMapper = agentMapper;\n"
                                + "    }\n"
                                + "\n"
                                + "    @Transactional(rollbackFor = Exception.class)\n"
                                + "    public PageResult<AgentVO> pageAgents(AgentQuery query) {\n"
                                + "        if (query == null) {\n"
                                + "            throw new IllegalArgumentException(\"query must not be null\");\n"
                                + "        }\n"
                                + "        int pageSize = Math.min(query.getPageSize(), MAX_PAGE_SIZE);\n"
                                + "        LambdaQueryWrapper<Agent> wrapper = Wrappers.lambdaQuery();\n"
                                + "        if (query.getName() != null) {\n"
                                + "            wrapper.like(Agent::getName, query.getName());\n"
                                + "        }\n"
                                + "        ",
                        "\n"
                                + "        Page<Agent> page = new Page<>(query.getPageNo(), pageSize);\n"
                                + "        agentMapper.selectPage(page, wrapper);\n"
                                + "\n"
                                + "        List<AgentVO> records = page.getRecords().stream()\n"
                                + "                .map(AgentVO::from)\n"
                                + "                .collect(Collectors.toList());\n"
                                + "\n"
                                + "        return PageResult.of(page.getTotal(), records);\n"
                                + "    }\n"
                                + "}\n"),
                new Scenario(
                        "4号 并发：分布式锁 + 重试退避（长上下文）",
                        "import java.time.Duration;\n"
                                + "\n"
                                + "public class TaskExecutor {\n"
                                + "\n"
                                + "    private static final int MAX_RETRY = 3;\n"
                                + "    private static final long BASE_BACKOFF_MS = 200L;\n"
                                + "\n"
                                + "    private final LockService lockService;\n"
                                + "    private final TaskRunner taskRunner;\n"
                                + "\n"
                                + "    public TaskExecutor(LockService lockService, TaskRunner taskRunner) {\n"
                                + "        this.lockService = lockService;\n"
                                + "        this.taskRunner = taskRunner;\n"
                                + "    }\n"
                                + "\n"
                                + "    public void executeOnce(String taskKey, Task task) {\n"
                                + "        String lockKey = \"task:lock:\" + taskKey;\n"
                                + "        boolean locked = lockService.tryLock(lockKey, Duration.ofSeconds(30));\n"
                                + "        if (!locked) {\n"
                                + "            log.warn(\"task already running, key={}\", taskKey);\n"
                                + "            return;\n"
                                + "        }\n"
                                + "        try {\n"
                                + "            ",
                        "\n"
                                + "        } finally {\n"
                                + "            lockService.unlock(lockKey);\n"
                                + "        }\n"
                                + "    }\n"
                                + "\n"
                                + "    private long backoffMillis(int attempt) {\n"
                                + "        return BASE_BACKOFF_MS * (1L << attempt);\n"
                                + "    }\n"
                                + "}\n"),
                new Scenario(
                        "5号 协议层：接口默认方法（长上下文）",
                        "import java.util.Arrays;\n"
                                + "import java.util.List;\n"
                                + "import java.util.Objects;\n"
                                + "\n"
                                + "public interface ChatModel {\n"
                                + "\n"
                                + "    ChatResponse chat(ChatRequest chatRequest);\n"
                                + "\n"
                                + "    default String chat(String userMessage) {\n"
                                + "        Objects.requireNonNull(userMessage, \"userMessage\");\n"
                                + "        ChatResponse response =\n"
                                + "                chat(ChatRequest.builder().message(UserMessage.from(userMessage)).build());\n"
                                + "        ",
                        "\n"
                                + "    }\n"
                                + "\n"
                                + "    default ChatResponse chat(ChatMessage... messages) {\n"
                                + "        Objects.requireNonNull(messages, \"messages\");\n"
                                + "        return chat(Arrays.asList(messages));\n"
                                + "    }\n"
                                + "\n"
                                + "    default ChatResponse chat(List<? extends ChatMessage> messages) {\n"
                                + "        Objects.requireNonNull(messages, \"messages\");\n"
                                + "        return chat(ChatRequest.builder().messages(messages).build());\n"
                                + "    }\n"
                                + "}\n"),
            };

    // ==================== 验证入口 ====================

    /**
     * 主入口：流式实时打印 + TTFT/完整耗时统计。
     *
     * <p>运行过程中就能看到代码逐块"长出来"，结束后给出耗时汇总。
     */
    @Test
    public void shouldVerifyBailianFim() throws InterruptedException {
        if (!configured()) {
            printSkipHint();
            return;
        }

        List<Integer> selected = resolveSelection();
        System.out.println("========== 百炼 Partial Mode 流式验证 ==========");
        System.out.println("baseUrl        = " + FIM_BASE_URL);
        System.out.println("model          = " + FIM_MODEL_NAME);
        System.out.println("模型类型       = " + describeModelKind(FIM_MODEL_NAME));
        System.out.println("思考模式       = " + describeThinkingMode(FIM_MODEL_NAME));
        System.out.println("请求实际参数   = thinkingMode=未开启（Partial Mode 请求体不含 thinking 字段）");
        System.out.println("场景数         = " + selected.size() + " / 共 " + SCENARIOS.length);
        System.out.println("已选           = " + selected);
        System.out.println();

        verifyStreamingRealtime(selected);
    }

    /**
     * 判断模型是否属于"思考模型"。
     *
     * <p>依据模型命名约定做启发式判断，<b>不发起额外请求</b>： 百炼的思考类系列通常是 QwQ / QVQ / 带 `-thinking` 后缀的版本； 而
     * `qwen3-coder-flash` 这类 Coder 模型为非思考模型。
     *
     * <p>注意：Qwen3 系列部分模型支持"思考开关"，是否实际思考取决于请求参数， 因此这里只回答"模型本身是否属于思考类型"，不回答"本次是否思考"。
     */
    private static String describeModelKind(String modelName) {
        if (modelName == null) {
            return "未知";
        }
        String lower = modelName.toLowerCase();
        if (lower.contains("qwq") || lower.contains("qvq")) {
            return "思考模型（QwQ/QVQ 系列，天生输出思维链）";
        }
        if (lower.contains("-thinking") || lower.contains("_thinking")) {
            return "思考模型（显式 thinking 变体）";
        }
        if (lower.contains("coder")) {
            return "非思考模型（Coder 系列，面向补全/生成速度）";
        }
        return "非思考模型（普通 instruct 系列，未启用思维链）";
    }

    /**
     * 说明本次请求是否开启思考模式。
     *
     * <p>依据实际请求体判断：{@code DashScopePartialWire} 构造的消息体里 <b>不含</b> {@code thinking} / {@code
     * enable_thinking} / {@code reasoning_effort} 字段， 因此对支持开关的模型等价于"未显式开启"。
     */
    private static String describeThinkingMode(String modelName) {
        if (modelName == null) {
            return "未知";
        }
        String lower = modelName.toLowerCase();
        if (lower.contains("qwq") || lower.contains("qvq")) {
            return "强制开启（该系列模型本身以思维链为输出形态，无法通过请求关闭）";
        }
        return "未开启（请求体未传 thinking / enable_thinking / reasoning_effort）";
    }

    /**
     * 可选入口：非流式对照（需要时单独运行）。
     *
     * <p>流式与非流式用同一批场景，便于对照"两边补全内容是否一致"。
     */
    @Test
    public void shouldVerifyBailianFimBlocking() {
        if (!configured()) {
            printSkipHint();
            return;
        }

        List<Integer> selected = resolveSelection();
        System.out.println("========== 百炼 Partial Mode 非流式验证 ==========");
        System.out.println("baseUrl  = " + FIM_BASE_URL);
        System.out.println("model    = " + FIM_MODEL_NAME);
        System.out.println("模型类型 = " + describeModelKind(FIM_MODEL_NAME));
        System.out.println("思考模式 = " + describeThinkingMode(FIM_MODEL_NAME));
        System.out.println("已选     = " + selected);
        System.out.println();

        verifyBlocking(selected);
    }

    /**
     * 解析本次要跑的场景编号（从 1 开始）。
     *
     * <p>优先级：命令行 {@code -Dfim.scenarios} &gt; 代码里的 {@link #SELECTED_SCENARIOS} &gt; 全部。
     *
     * <p>刻意做严格校验：编号非法立刻失败，而不是静默跳过——否则很容易"以为验证过了， 其实一个都没跑"。
     */
    private static List<Integer> resolveSelection() {
        List<Integer> fromProperty = parseSelection(System.getProperty("fim.scenarios", ""));
        List<Integer> selected;
        if (!fromProperty.isEmpty()) {
            selected = fromProperty;
        } else if (SELECTED_SCENARIOS != null && SELECTED_SCENARIOS.length > 0) {
            selected = new ArrayList<Integer>();
            for (int index : SELECTED_SCENARIOS) {
                selected.add(Integer.valueOf(index));
            }
        } else {
            selected = allScenarioIndexes();
        }

        for (Integer index : selected) {
            if (index == null || index.intValue() < 1 || index.intValue() > SCENARIOS.length) {
                throw new IllegalArgumentException(
                        "场景编号越界："
                                + index
                                + "，合法范围是 1.."
                                + SCENARIOS.length
                                + "（可用场景："
                                + allScenarioIndexes()
                                + "）");
            }
        }
        return selected;
    }

    /**
     * 解析 {@code "2,4"} / {@code "[2,4]"} / {@code "2 4"} 等写法。
     *
     * <p>空串返回空列表（表示"未指定"）。
     */
    private static List<Integer> parseSelection(String raw) {
        List<Integer> result = new ArrayList<Integer>();
        if (raw == null) {
            return result;
        }
        String cleaned = raw.trim();
        // 去掉方括号，允许 [2,4] 这种更直观的写法
        if (cleaned.startsWith("[")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        for (String token : cleaned.split("[,;\\s]+")) {
            String value = token.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                result.add(Integer.valueOf(Integer.parseInt(value)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "无法解析场景编号：\"" + value + "\"，请用逗号分隔的数字，例如 2,4 或 [2,4]");
            }
        }
        return result;
    }

    private static List<Integer> allScenarioIndexes() {
        List<Integer> all = new ArrayList<Integer>();
        for (int i = 0; i < SCENARIOS.length; i++) {
            all.add(Integer.valueOf(i + 1));
        }
        return all;
    }

    // ==================== 流式：实时逐块打印 ====================

    private void verifyStreamingRealtime(List<Integer> selected) throws InterruptedException {
        StreamingFimModel model =
                DashScopeStreamingFimModel.builder()
                        .baseUrl(FIM_BASE_URL)
                        .apiKey(FIM_API_KEY)
                        .modelName(FIM_MODEL_NAME)
                        .maxTokens(256)
                        .temperature(0.0d)
                        .build();

        List<Latency> latencies = new ArrayList<Latency>();

        for (Integer indexValue : selected) {
            int index = indexValue.intValue();
            Scenario scenario = SCENARIOS[index - 1];
            StreamingPrinter handler = new StreamingPrinter(index, scenario.name);

            handler.printHeader(scenario);
            long wallStartNanos = System.nanoTime();

            model.complete(
                    FimRequest.builder()
                            .prefix(scenario.prefix)
                            .suffix(scenario.suffix)
                            .language("java")
                            .build(),
                    handler);

            assertTrue("[" + scenario.name + "] 流式响应超时", handler.await(60, TimeUnit.SECONDS));
            long wallMillis = (System.nanoTime() - wallStartNanos) / 1_000_000L;

            assertNull(
                    "["
                            + scenario.name
                            + "] 流式失败: "
                            + (handler.error == null ? "" : handler.error.getMessage()),
                    handler.error);
            assertNotNull("[" + scenario.name + "] 最终响应不应为 null", handler.response);
            // 单独把"空内容"拆出来断言：这类失效是 HTTP 200 + finish=stop，不报错也很难察觉，
            // 如果只断言"收到块"，报错信息会误导成"流式没工作"，实际是模型没返回内容。
            assertTrue(
                    "["
                            + scenario.name
                            + "] 模型返回了空补全（HTTP 200，finish="
                            + (handler.response == null ? "-" : handler.response.finishReason())
                            + "）。请检查 suffix 是否被放进 user 消息（应放 system）。",
                    !handler.response.isEmpty());
            assertTrue("[" + scenario.name + "] 应收到增量块", handler.chunkCount > 0);

            // 结构性约束：出现 Markdown 围栏说明模型把"续写"当成了"回答问题"
            assertTrue(
                    "[" + scenario.name + "] 不应出现 Markdown 围栏",
                    !handler.response.completion().contains("```"));

            handler.printFooter(wallMillis);
            latencies.add(
                    new Latency(
                            scenario.name,
                            handler.ttftMillis,
                            handler.response.latencyMs(),
                            wallMillis,
                            handler.chunkCount,
                            handler.charCount,
                            handler.averageGapMillis(),
                            handler.maxGapMillis(),
                            scenario.prefix.length(),
                            scenario.suffix.length(),
                            INSTRUCTION.length()));
        }

        printLatencySummary(latencies);
    }

    // ==================== 非流式：对照 ====================

    private void verifyBlocking(List<Integer> selected) {
        FimModel model =
                DashScopeFimModel.builder()
                        .baseUrl(FIM_BASE_URL)
                        .apiKey(FIM_API_KEY)
                        .modelName(FIM_MODEL_NAME)
                        .maxTokens(256)
                        .temperature(0.0d)
                        .build();

        for (Integer indexValue : selected) {
            int index = indexValue.intValue();
            Scenario scenario = SCENARIOS[index - 1];

            FimResponse response =
                    model.complete(
                            FimRequest.builder()
                                    .prefix(scenario.prefix)
                                    .suffix(scenario.suffix)
                                    .language("java")
                                    .build());

            assertNotNull("[" + scenario.name + "] 响应不应为 null", response);
            assertTrue("[" + scenario.name + "] 补全不应为空", !response.isEmpty());

            System.out.println("---------- 场景" + index + " " + scenario.name + " ----------");
            System.out.println(
                    "latency="
                            + response.latencyMs()
                            + "ms | finish="
                            + response.finishReason()
                            + " | model="
                            + response.model()
                            + " | usage="
                            + usageText(response.usage()));
            System.out.println("completion:");
            System.out.println(indent(response.completion()));
            System.out.println();
        }

        System.out.println("[提示] 百炼无原生 suffix：若补全里重复了后文代码，属预期内的能力上限。");
    }

    // ==================== 汇总 ====================

    private void printLatencySummary(List<Latency> latencies) {
        System.out.println();
        System.out.println("========== 耗时汇总 ==========");
        System.out.println(
                pad("场景", 34)
                        + padLeft("prefix", 8)
                        + padLeft("suffix", 8)
                        + padLeft("指令", 7)
                        + padLeft("TTFT", 8)
                        + padLeft("完整", 8)
                        + padLeft("块数", 6)
                        + padLeft("间隔avg", 10)
                        + padLeft("输出字符", 10));
        System.out.println(repeat('-', 99));

        long ttftTotal = 0L;
        long totalTotal = 0L;
        long maxTtft = 0L;
        long maxTotal = 0L;
        int totalChunks = 0;
        long totalChars = 0L;

        for (Latency item : latencies) {
            System.out.println(
                    pad(shorten(item.name, 32), 34)
                            + padLeft(String.valueOf(item.prefixChars), 8)
                            + padLeft(String.valueOf(item.suffixChars), 8)
                            + padLeft(String.valueOf(item.instructionChars), 7)
                            + padLeft(item.ttftMillis + "ms", 8)
                            + padLeft(item.totalMillis + "ms", 8)
                            + padLeft(String.valueOf(item.chunks), 6)
                            + padLeft(String.format("%.1fms", item.avgGapMillis), 10)
                            + padLeft(String.valueOf(item.chars), 10));
            ttftTotal += item.ttftMillis;
            totalTotal += item.totalMillis;
            maxTtft = Math.max(maxTtft, item.ttftMillis);
            maxTotal = Math.max(maxTotal, item.totalMillis);
            totalChunks += item.chunks;
            totalChars += item.chars;
        }

        int n = latencies.size();
        System.out.println(repeat('-', 99));
        System.out.println(
                "平均 TTFT = " + (ttftTotal / n) + "ms / 平均完整耗时 = " + (totalTotal / n) + "ms");
        System.out.println("最大 TTFT = " + maxTtft + "ms / 最大完整耗时 = " + maxTotal + "ms");
        System.out.println("总块数 = " + totalChunks + " / 总字符 = " + totalChars);
        System.out.println();
        System.out.println("判读建议：");
        System.out.println("  1. TTFT 明显高于 300ms：先排查网络与是否误用了思考类模型");
        System.out.println("  2. 块间隔接近完整耗时（只有 1-2 块）：说明服务端没真正流式，或解析把整包当一块");
        System.out.println("  3. 完整耗时远大于 TTFT：属正常流式（首块快、后续持续生成）");
        System.out.println("  4. 补全重复了后文代码：百炼无原生 suffix 的固有限制，不是实现 bug");
    }

    // ==================== 工具 ====================

    private static void printSkipHint() {
        System.out.println(
                "跳过验证：请先填写 DashScopeLiveVerificationTest 中的"
                        + " FIM_BASE_URL / FIM_API_KEY / FIM_MODEL_NAME，"
                        + " 或用 -Dfim.baseUrl / -Dfim.apiKey / -Dfim.modelName 传入。");
    }

    private static boolean configured() {
        return isNotBlank(FIM_BASE_URL) && isNotBlank(FIM_API_KEY) && isNotBlank(FIM_MODEL_NAME);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String usageText(FimUsage usage) {
        if (usage == null) {
            return "-";
        }
        return "in="
                + usage.inputTokens()
                + ", out="
                + usage.outputTokens()
                + ", cached="
                + usage.cachedInputTokens();
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        if (displayWidth(text) <= max) {
            return text;
        }
        // 按显示宽度逐字符累加，避免用字符下标截断导致中英文混排越界
        int budget = Math.max(0, max - 3);
        StringBuilder builder = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int charWidth = isWide(c) ? 2 : 1;
            if (width + charWidth > budget) {
                break;
            }
            builder.append(c);
            width += charWidth;
        }
        return builder.append("...").toString();
    }

    /** 估算终端显示宽度：CJK 字符按 2 列算，其余按 1 列。 */
    private static int displayWidth(String text) {
        if (text == null) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            width += isWide(c) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    /** 左对齐补齐到指定显示宽度。 */
    private static String pad(String text, int width) {
        String value = text == null ? "" : text;
        int diff = width - displayWidth(value);
        if (diff <= 0) {
            return value;
        }
        return value + repeat(' ', diff);
    }

    /** 右对齐补齐到指定显示宽度。 */
    private static String padLeft(String text, int width) {
        String value = text == null ? "" : text;
        int diff = width - displayWidth(value);
        if (diff <= 0) {
            return value;
        }
        return repeat(' ', diff) + value;
    }

    private static String repeat(char c, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    private static String head(String text, int max) {
        String single = text == null ? "" : text.replace("\n", "\\n");
        return single.length() <= max ? single : single.substring(0, max) + "...";
    }

    private static String tail(String text, int max) {
        String single = text == null ? "" : text.replace("\n", "\\n");
        return single.length() <= max ? single : "..." + single.substring(single.length() - max);
    }

    private static String indent(String text) {
        if (text == null || text.isEmpty()) {
            return "  <empty>";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : text.split("\n")) {
            builder.append("    ").append(line).append('\n');
        }
        return builder.toString();
    }

    /** 一个补全场景：名字 + 前缀 + 后缀。 */
    private static final class Scenario {
        private final String name;
        private final String prefix;
        private final String suffix;

        private Scenario(String name, String prefix, String suffix) {
            this.name = name;
            this.prefix = prefix == null ? "" : prefix;
            this.suffix = suffix == null ? "" : suffix;
        }

        private int prefixLines() {
            return prefix.isEmpty() ? 0 : prefix.split("\n", -1).length;
        }

        private int suffixLines() {
            return suffix.isEmpty() ? 0 : suffix.split("\n", -1).length;
        }
    }

    /** 一次运行的耗时记录，用于汇总表。 */
    private static final class Latency {
        private final String name;
        private final long ttftMillis;
        private final long totalMillis;
        private final long wallMillis;
        private final int chunks;
        private final int chars;
        private final double avgGapMillis;
        private final double maxGapMillis;
        private final int prefixChars;
        private final int suffixChars;
        private final int instructionChars;

        private Latency(
                String name,
                long ttftMillis,
                long totalMillis,
                long wallMillis,
                int chunks,
                int chars,
                double avgGapMillis,
                double maxGapMillis,
                int prefixChars,
                int suffixChars,
                int instructionChars) {
            this.name = name;
            this.ttftMillis = ttftMillis;
            this.totalMillis = totalMillis;
            this.wallMillis = wallMillis;
            this.chunks = chunks;
            this.chars = chars;
            this.avgGapMillis = avgGapMillis;
            this.maxGapMillis = maxGapMillis;
            this.prefixChars = prefixChars;
            this.suffixChars = suffixChars;
            this.instructionChars = instructionChars;
        }
    }

    /**
     * 实时打印型流式回调。
     *
     * <p>关键行为：每收到一个增量块就 {@code print} 并 {@code flush}，**不做任何缓冲**， 因此控制台能看到代码逐块出现。回调运行在 HTTP I/O
     * 线程上，这里只做打印与计数， 不涉及阻塞操作。
     */
    private static final class StreamingPrinter implements StreamingFimResponseHandler {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final long startNanos = System.nanoTime();
        private final int index;
        private final String name;
        private final List<Long> arrivalMicros = new ArrayList<Long>();

        private volatile FimResponse response;
        private volatile Throwable error;
        private volatile long ttftMillis = -1L;
        private volatile int chunkCount;
        private volatile int charCount;

        private StreamingPrinter(int index, String name) {
            this.index = index;
            this.name = name;
        }

        private void printHeader(Scenario scenario) {
            System.out.println();
            System.out.println("---------- 场景" + index + " " + name + " ----------");
            System.out.println(
                    "输入规模：prefix "
                            + scenario.prefix.length()
                            + " 字符 / "
                            + scenario.prefixLines()
                            + " 行"
                            + " | suffix "
                            + scenario.suffix.length()
                            + " 字符 / "
                            + scenario.suffixLines()
                            + " 行");
            System.out.println(
                    "长度明细：prefix="
                            + scenario.prefix.length()
                            + " | suffix="
                            + scenario.suffix.length()
                            + " | 指令="
                            + INSTRUCTION.length()
                            + " | suffix提示="
                            + (SUFFIX_HINT_ENABLED ? "开启" : "关闭")
                            + " | 合计="
                            + (scenario.prefix.length()
                                    + scenario.suffix.length()
                                    + INSTRUCTION.length()));
            System.out.println("[prefix 尾部] ..." + tail(scenario.prefix, 110));
            System.out.println("[suffix 头部] " + head(scenario.suffix, 80) + " ...");
            System.out.println();
            System.out.println(">>> 实时流式输出（首块到达即打印）>>>");
            System.out.flush();
        }

        @Override
        public void onFirstChunk(FimChunk chunk) {
            this.ttftMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            System.out.print("[TTFT " + ttftMillis + "ms]\n");
            System.out.flush();
        }

        @Override
        public void onPartialCompletion(FimChunk chunk) {
            chunkCount++;
            charCount += chunk.delta().length();
            arrivalMicros.add((System.nanoTime() - startNanos) / 1_000L);

            // 实时打印：到达即输出并 flush，绝不缓冲到结束
            System.out.print(chunk.delta());
            System.out.flush();
        }

        @Override
        public void onComplete(FimResponse completeResponse) {
            this.response = completeResponse;
            // 保证上一条输出与后续统计之间有换行
            System.out.println();
            latch.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
            System.out.println();
            latch.countDown();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        /** 相邻块到达间隔均值（毫秒）；块数不足 2 时返回 0。 */
        private double averageGapMillis() {
            if (arrivalMicros.size() < 2) {
                return 0d;
            }
            long first = arrivalMicros.get(0);
            long last = arrivalMicros.get(arrivalMicros.size() - 1);
            return (last - first) / 1000d / (arrivalMicros.size() - 1);
        }

        /** 最大块间隔（毫秒），用来发现中途"卡住"的情况。 */
        private double maxGapMillis() {
            if (arrivalMicros.size() < 2) {
                return 0d;
            }
            long max = 0L;
            for (int i = 1; i < arrivalMicros.size(); i++) {
                max = Math.max(max, arrivalMicros.get(i) - arrivalMicros.get(i - 1));
            }
            return max / 1000d;
        }

        private void printFooter(long wallMillis) {
            FimResponse result = response;
            System.out.println();
            System.out.println("<<< 流式结束 <<<");
            System.out.println(
                    "TTFT="
                            + ttftMillis
                            + "ms | 完整耗时="
                            + (result == null ? -1 : result.latencyMs())
                            + "ms | 墙钟="
                            + wallMillis
                            + "ms | 块数="
                            + chunkCount
                            + " | 字符="
                            + charCount
                            + " | finish="
                            + (result == null ? "-" : result.finishReason())
                            + " | usage="
                            + (result == null ? "-" : usageText(result.usage())));
            System.out.printf(
                    "块间隔：avg=%.1fms / max=%.1fms | TTFT 占比=%d%%%n",
                    averageGapMillis(),
                    maxGapMillis(),
                    (result == null || result.latencyMs() <= 0)
                            ? 0
                            : (int) (ttftMillis * 100 / result.latencyMs()));
            System.out.println("聚合结果（缩进展示）：");
            System.out.println(indent(result == null ? "" : result.completion()));
        }
    }
}
