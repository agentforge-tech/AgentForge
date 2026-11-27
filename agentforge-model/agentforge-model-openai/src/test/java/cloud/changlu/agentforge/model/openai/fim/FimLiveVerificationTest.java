package cloud.changlu.agentforge.model.openai.fim;

import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.DefaultFimRequestParameters;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimChunk;
import cloud.changlu.agentforge.model.fim.response.FimFinishReason;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.FimUsage;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;
import cloud.changlu.agentforge.model.openai.OpenAiFimModel;
import cloud.changlu.agentforge.model.openai.OpenAiStreamingFimModel;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * FIM 真实端点验证入口：多场景（非流式 + 流式对照）。
 *
 * <p><b>一、需要你填的三项</b>
 *
 * <pre>
 * 1) FIM_BASE_URL      : 服务地址，DeepSeek 用 https://api.deepseek.com/beta
 * 2) FIM_API_KEY       : 你的 API Key
 * 3) FIM_MODEL_NAME    : 模型名，DeepSeek 用 deepseek-flash
 * </pre>
 *
 * <p><b>二、不写死密钥的方式</b>
 *
 * <pre>
 * mvn -pl agentforge-model/agentforge-model-openai -am \
 *     -Dfim.baseUrl=https://api.deepseek.com/beta \
 *     -Dfim.apiKey=sk-xxx \
 *     -Dfim.modelName=deepseek-flash \
 *     -Dtest="cloud.changlu.agentforge.model.openai.fim.FimLiveVerificationTest" test
 * </pre>
 *
 * <p><b>三、追加你自己的 prompt 场景（两种方式）</b>
 *
 * <ol>
 *   <li><b>改代码</b>：往 {@link #CUSTOM_SCENARIOS} 里加 {@code new Scenario("名字", prefix, suffix)}，
 *       非流式与流式会<b>同时</b>跑这一组；
 *   <li><b>命令行一次试跑</b>：{@code -Dfim.custom.prefix=... -Dfim.custom.suffix=...}，
 *       适合临时验证某段真实业务代码，无需改文件。
 * </ol>
 *
 * <p><b>四、关于断言的尺度</b>
 *
 * <p>真实模型每次补全的措辞都可能不同，因此这里只对与模型表述无关的结构性性质做硬断言 （非空、不输出 Markdown 围栏、不重复后缀、finishReason
 * 非空）。具体代码内容只打印出来供人工判读， 否则用例会因模型换措辞而随机失败。
 *
 * @author changlu
 * @date 2026/11/25
 */
public class FimLiveVerificationTest {

    // ==================== ① 三项配置 ====================

    /** 服务地址：DeepSeek FIM 必须带 /beta。 */
    private static final String FIM_BASE_URL =
            System.getProperty("fim.baseUrl", "https://api.deepseek.com/beta");

    /** API Key：留空则整类用例跳过，不产生网络请求。 */
    private static final String FIM_API_KEY = System.getProperty("fim.apiKey", "");

    /** 模型名称。 */
    private static final String FIM_MODEL_NAME =
            System.getProperty("fim.modelName", "deepseek-flash");

    // ==================== ② 自定义场景（在此追加你自己的 prompt） ====================

    /**
     * 你自己的场景集：非流式与流式都会跑（流式专用用例也用它）。
     *
     * <p>下面几条已经写好，<b>开箱即用</b>，你只填三项配置就能跑。要加自己的场景， 照格式追加一条 {@code new Scenario(名称, 前缀, 后缀)} 即可。
     *
     * <p>编写要点（这几条决定了场景是否"有效"）：
     *
     * <ul>
     *   <li><b>前缀</b>要保留足够上下文：类名、字段、已声明变量、import 都别删；
     *   <li><b>后缀</b>要写真实收尾：闭合括号、后续调用、下一个方法头，这样才验证得出"模型是否尊重后文"；
     *   <li><b>光标位置</b>放在"明显缺一段逻辑"的地方，而不是已经写完的语句之后。
     * </ul>
     */
    private static final Scenario[] CUSTOM_SCENARIOS =
            new Scenario[] {
                new Scenario(
                        "自定义1 任务执行：状态流转与日志",
                        "    public TaskResult execute(Task task) {\n"
                                + "        if (task == null) {\n"
                                + "            throw new IllegalArgumentException(\"task must not be null\");\n"
                                + "        }\n"
                                + "        log.info(\"start task, id={}\", task.getId());\n"
                                + "        ",
                        "\n"
                                + "        return TaskResult.success(task.getId(), output);\n"
                                + "    }\n"),
                new Scenario(
                        "自定义2 任务执行：幂等与分布式锁",
                        "    public void runOnce(String taskKey, Runnable action) {\n"
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
                                + "    }\n"),
                new Scenario(
                        "自定义3 任务查询：条件组装与排序",
                        "    public List<TaskVO> listRecentTasks(TaskQuery query) {\n"
                                + "        LambdaQueryWrapper<Task> wrapper = Wrappers.lambdaQuery();\n"
                                + "        ",
                        "\n"
                                + "        wrapper.orderByDesc(Task::getCreatedAt);\n"
                                + "        wrapper.last(\"limit \" + query.getLimit());\n"
                                + "        return taskMapper.selectList(wrapper).stream()\n"
                                + "                .map(TaskVO::from)\n"
                                + "                .collect(Collectors.toList());\n"
                                + "    }\n"),
            };

    // ==================== ③ 内置真实业务场景（取自 AgentForge 自身代码风格） ====================

    /**
     * 内置场景集。
     *
     * <p>这些片段刻意照搬/仿写 AgentForge 自己的代码形态——协议层、Agent 循环、工具执行、 分页事务、Stream 流水线、SSE
     * 聚合——因为它们就是"真实场景里正在写的任务类型代码"。
     *
     * <p>覆盖的难点：
     *
     * <ul>
     *   <li><b>场景 1</b> 协议层便捷方法：需要沿用 `ChatRequest` / `UserMessage` 的正确构造方式；
     *   <li><b>场景 2</b> ReAct 循环体：需要"执行工具 → 回填 ToolExecutionResultMessage → 再次调用模型"， 且必须保持 while
     *       条件语义；
     *   <li><b>场景 3</b> 工具执行：需要 try/catch 包裹并返回统一的 ToolExecutionResult；
     *   <li><b>场景 4</b> 分页 + 事务：需要沿用已声明的 `wrapper` 变量，不能重新 new；
     *   <li><b>场景 5</b> Stream 流水线中段：必须续接 `.map(...)`，不能另起独立语句；
     *   <li><b>场景 6</b> SSE 增量聚合：需要处理"末块 text 为空但带 usage"这一 DeepSeek 特性。
     * </ul>
     */
    private static final Scenario[] BUILT_IN_SCENARIOS =
            new Scenario[] {
                new Scenario(
                        "场景1 协议层：ChatModel 便捷方法",
                        "public interface ChatModel {\n"
                                + "\n"
                                + "    ChatResponse chat(ChatRequest chatRequest);\n"
                                + "\n"
                                + "    default String chat(String userMessage) {\n"
                                + "        Objects.requireNonNull(userMessage, \"userMessage\");\n"
                                + "        ",
                        "\n" + "        return response.aiMessage().text();\n" + "    }\n" + "}\n"),
                new Scenario(
                        "场景2 Agent：ReAct 工具调用循环",
                        "    public String run(String userInput) {\n"
                                + "        ChatRequest request = ChatRequest.builder()\n"
                                + "                .message(SystemMessage.from(SYSTEM_PROMPT))\n"
                                + "                .message(UserMessage.from(userInput))\n"
                                + "                .parameters(DefaultChatRequestParameters.builder()\n"
                                + "                        .tools(toolSpecifications)\n"
                                + "                        .build())\n"
                                + "                .build();\n"
                                + "\n"
                                + "        ChatResponse response = chatModel.chat(request);\n"
                                + "        while (response.finishReason() == FinishReason.TOOL_EXECUTION) {\n"
                                + "            ",
                        "\n"
                                + "        }\n"
                                + "        return response.aiMessage().text();\n"
                                + "    }\n"),
                new Scenario(
                        "场景3 Tool：工具执行与异常封装",
                        "    public ToolExecutionResult execute(ToolExecutionRequest request) {\n"
                                + "        ToolExecutor executor = executors.get(request.name());\n"
                                + "        if (executor == null) {\n"
                                + "            throw new IllegalArgumentException(\"Unknown tool: \" + request.name());\n"
                                + "        }\n"
                                + "        String id = request.id();\n"
                                + "        ",
                        "\n"
                                + "        return ToolExecutionResult.success(id, output);\n"
                                + "    }\n"),
                new Scenario(
                        "场景4 业务：分页查询 + 事务",
                        "    @Transactional(rollbackFor = Exception.class)\n"
                                + "    public PageResult<AgentVO> pageAgents(AgentQuery query) {\n"
                                + "        LambdaQueryWrapper<Agent> wrapper = Wrappers.lambdaQuery();\n"
                                + "        ",
                        "\n"
                                + "        Page<Agent> page = new Page<>(query.getPageNo(), query.getPageSize());\n"
                                + "        agentMapper.selectPage(page, wrapper);\n"
                                + "        return PageResult.of(page, AgentVO::from);\n"
                                + "    }\n"),
                new Scenario(
                        "场景5 Stream：流水线中段续接",
                        "    public List<String> enabledToolNames(List<ToolSpecification> tools) {\n"
                                + "        return tools.stream()\n"
                                + "                .filter(tool -> tool != null)\n"
                                + "                .",
                        "\n" + "                .collect(Collectors.toList());\n" + "    }\n"),
                new Scenario(
                        "场景6 流式：SSE 增量聚合",
                        "    private FimResponse aggregate(String modelName, long startMillis) {\n"
                                + "        StringBuilder buffer = new StringBuilder();\n"
                                + "        String finishReason = null;\n"
                                + "        FimUsage usage = null;\n"
                                + "\n"
                                + "        for (String line : lines) {\n"
                                + "            if (!line.startsWith(\"data:\")) {\n"
                                + "                continue;\n"
                                + "            }\n"
                                + "            ",
                        "\n"
                                + "        }\n"
                                + "        return FimResponse.builder()\n"
                                + "                .completion(buffer.toString())\n"
                                + "                .finishReason(map(finishReason))\n"
                                + "                .usage(usage)\n"
                                + "                .model(modelName)\n"
                                + "                .latencyMs(System.currentTimeMillis() - startMillis)\n"
                                + "                .build();\n"
                                + "    }\n"),
            };

    // ==================== 验证用例 ====================

    @Test
    public void shouldVerifyDeepSeekFim() throws InterruptedException {
        if (!configured()) {
            System.out.println(
                    "跳过验证：请先填写 FimLiveVerificationTest 中的"
                            + " FIM_BASE_URL / FIM_API_KEY / FIM_MODEL_NAME，"
                            + " 或用 -Dfim.baseUrl / -Dfim.apiKey / -Dfim.modelName 传入。");
            return;
        }

        List<Scenario> scenarios = resolveScenarios();

        System.out.println("========== FIM 验证开始（" + scenarios.size() + " 个场景） ==========");
        System.out.println("baseUrl = " + FIM_BASE_URL);
        System.out.println("model   = " + FIM_MODEL_NAME);
        System.out.println();

        List<FimResponse> blockingResults = verifyBlocking(scenarios);
        verifyStreaming(scenarios, blockingResults);
        printSummary(scenarios, blockingResults);
    }

    /**
     * 组装本次要跑的场景。
     *
     * <p>优先级：命令行单次自定义 &gt; {@link #CUSTOM_SCENARIOS} + 内置场景。
     *
     * <p>命令行模式用 {@code -Dfim.custom.prefix} / {@code -Dfim.custom.suffix} 传，
     * 会<b>只跑这一个场景</b>，方便临时验证正在写的那段代码。
     */
    private static List<Scenario> resolveScenarios() {
        String customPrefix = System.getProperty("fim.custom.prefix", "");
        String customSuffix = System.getProperty("fim.custom.suffix", "");
        if (isNotBlank(customPrefix) || isNotBlank(customSuffix)) {
            return Arrays.asList(
                    new Scenario("命令行自定义场景", unescape(customPrefix), unescape(customSuffix)));
        }

        List<Scenario> scenarios = new ArrayList<Scenario>();
        scenarios.addAll(Arrays.asList(CUSTOM_SCENARIOS));
        scenarios.addAll(Arrays.asList(BUILT_IN_SCENARIOS));
        return scenarios;
    }

    /**
     * 单独运行：只测流式（不跑非流式）。
     *
     * <p>用途：只想确认"流式链路能不能用"时，在 IDE 里单独点这个方法的运行箭头即可。 与 {@link #shouldVerifyDeepSeekFim()}
     * 的区别是——它不调用非流式接口，因此更快， 也不会因非流式结果差异产生干扰。
     *
     * <p>跑的场景 = {@link #CUSTOM_SCENARIOS}（你自己写的）+ 内置 6 个。
     */
    @Test
    public void shouldVerifyStreamingOnly() throws InterruptedException {
        if (!configured()) {
            printSkipHint();
            return;
        }

        List<Scenario> scenarios = resolveScenarios();

        StreamingFimModel model =
                OpenAiStreamingFimModel.builder()
                        .baseUrl(FIM_BASE_URL)
                        .apiKey(FIM_API_KEY)
                        .modelName(FIM_MODEL_NAME)
                        .maxTokens(128)
                        .temperature(0.0d)
                        .build();

        System.out.println("========== 流式验证（仅流式，" + scenarios.size() + " 个场景） ==========");
        System.out.println("baseUrl = " + FIM_BASE_URL);
        System.out.println("model   = " + FIM_MODEL_NAME);
        System.out.println();

        for (Scenario scenario : scenarios) {
            RecordingHandler handler = new RecordingHandler();

            model.complete(
                    FimRequest.builder()
                            .prefix(scenario.prefix)
                            .suffix(scenario.suffix)
                            .language("java")
                            .parameters(
                                    DefaultFimRequestParameters.builder()
                                            .stopSequences(STREAMING_STOPS)
                                            .build())
                            .build(),
                    handler);

            assertTrue("[" + scenario.name + "] 流式响应超时", handler.await(45, TimeUnit.SECONDS));
            assertNull(
                    "["
                            + scenario.name
                            + "] 流式失败: "
                            + (handler.error == null ? "" : handler.error.getMessage()),
                    handler.error);
            assertNotNull("[" + scenario.name + "] 最终响应不应为 null", handler.response);
            assertTrue("[" + scenario.name + "] 应收到增量块", handler.chunkCount > 0);
            assertStructurallySound(scenario, handler.response, "流式");

            System.out.println("---------- " + scenario.name + " ----------");
            System.out.println(
                    "TTFT="
                            + handler.ttftMillis
                            + "ms | total="
                            + handler.response.latencyMs()
                            + "ms | chunks="
                            + handler.chunkCount
                            + " | finish="
                            + handler.response.finishReason()
                            + " | usage="
                            + usageText(handler.response.usage()));
            System.out.println("prefix 尾部: ..." + tail(scenario.prefix, 100));
            System.out.println("suffix 头部: " + head(scenario.suffix, 70) + " ...");
            System.out.println("增量片段（前 3 块）: " + handler.firstChunks(3));
            System.out.println("聚合结果:");
            System.out.println(indent(handler.response.completion()));
            System.out.println();
        }

        System.out.println("========== 流式验证结束 ==========");
        System.out.println("关注点：");
        System.out.println("  1. TTFT 应在 300ms 量级；明显偏高先查网络与思考模式");
        System.out.println("  2. chunks 为 1 说明服务端没真正流式返回，或解析把整包当成了一块");
        System.out.println("  3. finish=LENGTH 说明 maxTokens 偏小，补全被截断");
        System.out.println("  4. usage 为 '-' 说明末块的 usage 没有被取到（DeepSeek 附在最后一个内容块上）");
    }

    /**
     * 单独运行：验证「用户自定义 prompt」这一条路径。
     *
     * <p>它只跑 {@link #CUSTOM_SCENARIOS}（不跑内置场景），适合你在反复调自己那段业务代码时用。
     */
    @Test
    public void shouldVerifyCustomScenariosOnly() throws InterruptedException {
        if (!configured()) {
            printSkipHint();
            return;
        }
        if (CUSTOM_SCENARIOS.length == 0) {
            System.out.println(
                    "CUSTOM_SCENARIOS 为空：请在 FimLiveVerificationTest 的 CUSTOM_SCENARIOS 中"
                            + " 添加 new Scenario(名称, 前缀, 后缀)。");
            return;
        }

        StreamingFimModel model =
                OpenAiStreamingFimModel.builder()
                        .baseUrl(FIM_BASE_URL)
                        .apiKey(FIM_API_KEY)
                        .modelName(FIM_MODEL_NAME)
                        .maxTokens(128)
                        .temperature(0.0d)
                        .build();

        System.out.println("========== 自定义场景验证（" + CUSTOM_SCENARIOS.length + " 个） ==========");
        for (Scenario scenario : CUSTOM_SCENARIOS) {
            RecordingHandler handler = new RecordingHandler();
            model.complete(
                    FimRequest.builder()
                            .prefix(scenario.prefix)
                            .suffix(scenario.suffix)
                            .language("java")
                            .parameters(
                                    DefaultFimRequestParameters.builder()
                                            .stopSequences(STREAMING_STOPS)
                                            .build())
                            .build(),
                    handler);

            assertTrue("[" + scenario.name + "] 流式响应超时", handler.await(45, TimeUnit.SECONDS));
            assertNull(
                    "["
                            + scenario.name
                            + "] 流式失败: "
                            + (handler.error == null ? "" : handler.error.getMessage()),
                    handler.error);
            assertNotNull("[" + scenario.name + "] 最终响应不应为 null", handler.response);
            assertStructurallySound(scenario, handler.response, "自定义");

            System.out.println("---------- " + scenario.name + " ----------");
            System.out.println(
                    "TTFT="
                            + handler.ttftMillis
                            + "ms | chunks="
                            + handler.chunkCount
                            + " | finish="
                            + handler.response.finishReason());
            System.out.println(indent(handler.response.completion()));
            System.out.println();
        }
    }

    /** 未配置时统一提示，避免两个入口各写一遍。 */
    private static void printSkipHint() {
        System.out.println(
                "跳过验证：请先填写 FimLiveVerificationTest 中的"
                        + " FIM_BASE_URL / FIM_API_KEY / FIM_MODEL_NAME，"
                        + " 或用 -Dfim.baseUrl / -Dfim.apiKey / -Dfim.modelName 传入。");
    }

    // ==================== 非流式：逐场景 ====================

    private List<FimResponse> verifyBlocking(List<Scenario> scenarios) {
        FimModel model =
                OpenAiFimModel.builder()
                        .baseUrl(FIM_BASE_URL)
                        .apiKey(FIM_API_KEY)
                        .modelName(FIM_MODEL_NAME)
                        .maxTokens(128)
                        .temperature(0.0d)
                        // 行内补全必须设 stop，否则模型可能一次吐出整个类
                        .stopSequences(BLOCKING_STOPS)
                        .build();

        System.out.println("========== 非流式 ==========");
        List<FimResponse> results = new ArrayList<FimResponse>();
        for (Scenario scenario : scenarios) {
            FimResponse response =
                    model.complete(
                            FimRequest.builder()
                                    .prefix(scenario.prefix)
                                    .suffix(scenario.suffix)
                                    .language("java")
                                    .build());

            assertNotNull("[" + scenario.name + "] 响应不应为 null", response);
            assertStructurallySound(scenario, response, "非流式");
            results.add(response);
            printResult(scenario, response);
        }
        System.out.println();
        return results;
    }

    // ==================== 流式：同一组场景 ====================

    /**
     * 流式验证：与 {@link #verifyBlocking} <b>使用同一组场景</b>。
     *
     * <p>这样做的价值是能直接对照"同输入下流式聚合结果 == 非流式结果"， 从而发现聚合漏块、usage 丢失、提前完成之类的实现问题。
     */
    private void verifyStreaming(List<Scenario> scenarios, List<FimResponse> blockingResults)
            throws InterruptedException {
        StreamingFimModel model =
                OpenAiStreamingFimModel.builder()
                        .baseUrl(FIM_BASE_URL)
                        .apiKey(FIM_API_KEY)
                        .modelName(FIM_MODEL_NAME)
                        .maxTokens(128)
                        .temperature(0.0d)
                        .build();

        System.out.println("========== 流式（TTFT 对照） ==========");

        for (int i = 0; i < scenarios.size(); i++) {
            Scenario scenario = scenarios.get(i);
            RecordingHandler handler = new RecordingHandler();

            model.complete(
                    FimRequest.builder()
                            .prefix(scenario.prefix)
                            .suffix(scenario.suffix)
                            .language("java")
                            .parameters(
                                    DefaultFimRequestParameters.builder()
                                            .stopSequences(STREAMING_STOPS)
                                            .build())
                            .build(),
                    handler);

            assertTrue("[" + scenario.name + "] 流式响应超时", handler.await(45, TimeUnit.SECONDS));
            assertNull(
                    "["
                            + scenario.name
                            + "] 流式失败: "
                            + (handler.error == null ? "" : handler.error.getMessage()),
                    handler.error);
            assertNotNull("[" + scenario.name + "] 流式最终响应不应为 null", handler.response);
            assertTrue("[" + scenario.name + "] 应收到增量块", handler.chunkCount > 0);
            assertStructurallySound(scenario, handler.response, "流式");

            String blocking = blockingResults.get(i).completion().trim();
            String streaming = handler.response.completion().trim();

            System.out.println(
                    scenario.name
                            + " | TTFT="
                            + handler.ttftMillis
                            + "ms | total="
                            + handler.response.latencyMs()
                            + "ms | chunks="
                            + handler.chunkCount
                            + " | 与聚合一致="
                            + blocking.equals(streaming));

            // 聚合一致性只做提示，不做硬断言：模型采样可能存在细微差异
            if (!blocking.equals(streaming)) {
                System.out.println("    [提示] 流式聚合与非流式结果不同，建议人工核对：");
                System.out.println("    非流式: " + brief(blocking));
                System.out.println("    流  式: " + brief(streaming));
            }
        }
        System.out.println();
    }

    // ==================== 断言与输出 ====================

    /** 结构性断言：只校验接入层必须保证的性质，与模型措辞无关。 */
    private void assertStructurallySound(Scenario scenario, FimResponse response, String channel) {
        String prefix = "[" + scenario.name + "/" + channel + "] ";
        String completion = response.completion();

        assertTrue(prefix + "补全不应为空", !response.isEmpty());

        // 1) 出现 Markdown 围栏说明模型把"续写"当成了"回答问题"
        assertTrue(
                prefix + "不应出现 Markdown 围栏，实际: " + brief(completion), !completion.contains("```"));

        // 2) 重复输出后缀说明 suffix 没有被利用（FIM 的核心能力失效）
        String suffixHead = firstNonBlankLine(scenario.suffix);
        if (suffixHead != null && suffixHead.length() >= 6) {
            assertTrue(prefix + "不应重复输出后缀内容: " + suffixHead, !completion.contains(suffixHead));
        }

        // 3) 结束原因必须可判读，便于上层决定是否重试
        assertNotNull(prefix + "finishReason 不应为 null", response.finishReason());

        // 4) 命中 length 说明 maxTokens 偏小，给出明确提示而非静默
        if (response.finishReason() == FimFinishReason.LENGTH) {
            System.out.println(prefix + "[提示] finishReason=LENGTH，补全被 maxTokens 截断，可适当调大");
        }
    }

    private void printResult(Scenario scenario, FimResponse response) {
        System.out.println("---------- " + scenario.name + " ----------");
        System.out.println(
                "latency="
                        + response.latencyMs()
                        + "ms | finish="
                        + response.finishReason()
                        + " | usage="
                        + usageText(response.usage()));
        System.out.println("prefix 尾部: ..." + tail(scenario.prefix, 100));
        System.out.println("suffix 头部: " + head(scenario.suffix, 70) + " ...");
        System.out.println("completion:");
        System.out.println(indent(response.completion()));
        System.out.println();
    }

    private void printSummary(List<Scenario> scenarios, List<FimResponse> results) {
        long total = 0L;
        long max = 0L;

        System.out.println("========== 汇总 ==========");
        for (int i = 0; i < scenarios.size(); i++) {
            FimResponse response = results.get(i);
            total += response.latencyMs();
            max = Math.max(max, response.latencyMs());
            System.out.println(
                    scenarios.get(i).name
                            + " | latency="
                            + response.latencyMs()
                            + "ms | chars="
                            + response.completion().length()
                            + " | finish="
                            + response.finishReason());
        }
        System.out.println(
                "avg latency = " + (total / scenarios.size()) + "ms / max = " + max + "ms");
        System.out.println();
        System.out.println("判读建议：");
        System.out.println("  1. 场景2 若没有出现 ToolExecutionResultMessage 回填，说明它没理解 ReAct 循环语义");
        System.out.println("  2. 场景4 若重新 new 了 wrapper，说明它没复用已声明的局部变量");
        System.out.println("  3. 场景5 若返回独立语句而不是 .map(...)，说明它对流式管道上下文不敏感");
        System.out.println("  4. 场景6 若漏掉\"末块 text 为空但带 usage\"，线上会拿不到计费数据");
        System.out.println("  5. TTFT 明显高于 300ms 时，先排查网络与是否误开思考模式");
    }

    // ==================== 常量与工具 ====================

    /**
     * 非流式 stop：覆盖 Java 常见收尾边界。
     *
     * <p>注意第一个元素是"连续两个空行 + 新方法声明"，用于阻止模型续写下一个方法。
     */
    private static final List<String> BLOCKING_STOPS =
            Arrays.asList("\n\n    public ", "\n\n    private ", "\n\n}", "```");

    /** 流式 stop：比非流式更保守，避免过早截断导致聚合结果断裂。 */
    private static final List<String> STREAMING_STOPS =
            Arrays.asList("\n\n    public ", "\n\n    private ", "```");

    private static boolean configured() {
        return isNotBlank(FIM_BASE_URL) && isNotBlank(FIM_API_KEY) && isNotBlank(FIM_MODEL_NAME);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** 把命令行里的字面量 {@code \n} 还原成换行，便于单行传参。 */
    private static String unescape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\n", "\n").replace("\\t", "\t");
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

    private static String firstNonBlankLine(String text) {
        if (text == null) {
            return null;
        }
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) {
                return line.trim();
            }
        }
        return null;
    }

    private static String head(String text, int max) {
        String single = text == null ? "" : text.replace("\n", "\\n");
        return single.length() <= max ? single : single.substring(0, max) + "...";
    }

    private static String tail(String text, int max) {
        String single = text == null ? "" : text.replace("\n", "\\n");
        return single.length() <= max ? single : "..." + single.substring(single.length() - max);
    }

    private static String brief(String text) {
        String single = text == null ? "" : text.replace("\n", "\\n");
        return single.length() <= 160 ? single : single.substring(0, 160) + "...";
    }

    /** 保留缩进输出，便于人工核对代码结构是否正确。 */
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
    }

    /** 记录流式回调：累加片段、统计 TTFT 与块数。 */
    private static final class RecordingHandler implements StreamingFimResponseHandler {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final long startNanos = System.nanoTime();
        private final List<String> chunks = new ArrayList<String>();
        private volatile FimResponse response;
        private volatile Throwable error;
        private volatile long ttftMillis = -1L;
        private volatile int chunkCount;

        @Override
        public void onFirstChunk(FimChunk chunk) {
            this.ttftMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        }

        @Override
        public void onPartialCompletion(FimChunk chunk) {
            chunkCount++;
            if (chunks.size() < 16) {
                // 只为展示前几块，避免长补全时把内存撑大
                chunks.add(chunk.delta());
            }
        }

        @Override
        public void onComplete(FimResponse completeResponse) {
            this.response = completeResponse;
            latch.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
            this.error = throwable;
            latch.countDown();
        }

        /** 展示前 N 个增量片段（转义换行，便于看清分块边界）。 */
        private String firstChunks(int n) {
            List<String> head = chunks.subList(0, Math.min(n, chunks.size()));
            StringBuilder builder = new StringBuilder();
            for (String chunk : head) {
                builder.append('[').append(chunk.replace("\n", "\\n")).append(']');
            }
            return builder.toString();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
    }
}
