package cloud.changlu.demo.fim;

import cloud.changlu.agentforge.model.fim.FimModel;
import cloud.changlu.agentforge.model.fim.FimTemplate;
import cloud.changlu.agentforge.model.fim.StreamingFimModel;
import cloud.changlu.agentforge.model.fim.request.FimRequest;
import cloud.changlu.agentforge.model.fim.response.FimChunk;
import cloud.changlu.agentforge.model.fim.response.FimResponse;
import cloud.changlu.agentforge.model.fim.response.FimUsage;
import cloud.changlu.agentforge.model.fim.response.StreamingFimResponseHandler;
import cloud.changlu.agentforge.model.fim.template.FimTemplates;
import cloud.changlu.agentforge.model.registry.LlmFactory;
import cloud.changlu.agentforge.model.registry.config.LlmBasicConfig;
import cloud.changlu.agentforge.model.registry.constant.LlmConstant;
import cloud.changlu.agentforge.model.registry.enums.LlmEnum;
import cloud.changlu.demo.fim.service.TokenProbe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地 OpenAI-compatible FIM 真实端点验证集。
 *
 * <p><b>一、三项配置（可用系统属性覆盖，避免地址进源码）</b>
 *
 * <pre>
 * 1) FIM_BASE_URL   : OpenAI-compatible 地址（含 /v1），默认本地 Xinference
 * 2) FIM_MODEL_NAME : 模型标识，需与 /v1/models 的 id 一致
 * 3) FIM_TEMPLATE   : 特殊 Token 模板，<b>必须按实测填</b>，默认 qwen-no-pipe
 * </pre>
 *
 * <p>也可用命令行：
 *
 * <pre>
 * mvn -Dtest=FimLiveVerificationTest \
 *     -Dfim.baseUrl=http://127.0.0.1:9997/v1 \
 *     -Dfim.modelName=CodeQwen1.5-7B \
 *     -Dfim.template=qwen-no-pipe \
 *     -Dfim.scenarios=1,3 test
 * </pre>
 *
 * <p><b>二、选择场景</b>：{@code -Dfim.scenarios=1,3} 或写成 {@code [1,3]}。留空跑全部。
 * 编号越界会立刻报错并列出合法范围，避免"以为跑过了其实没跑"。
 *
 * <p><b>三、流式实时打印</b>：每个增量块到达立刻输出并 flush，运行中能亲眼看到代码"长出来"， 同时统计 TTFT、总耗时与块间隔（用来判断是不是真的在流）。
 *
 * <p><b>四、重要前提</b>：本模型<b>不会自己吐 {@code <fim_pad>} 收尾</b>， 必须依赖结构级 stop。若补全里出现多个函数，属 stop
 * 配置不足，不是模型问题。
 *
 * @author changlu
 * @date 2026/11/26
 */
@EnabledIfSystemProperty(named = "fim.live", matches = "true")
class FimLiveVerificationTest {

    // ==================== ① 配置 ====================

    private static final String FIM_BASE_URL =
            System.getProperty("fim.baseUrl", "http://127.0.0.1:9997/v1");

    private static final String FIM_MODEL_NAME =
            System.getProperty("fim.modelName", "CodeQwen1.5-7B");

    /**
     * 特殊 Token 模板。
     *
     * <p>本部署实测只注册了无竖线形态，因此默认 {@code qwen-no-pipe}。 换部署前请先跑 {@link #shouldProbeTokenShape()} 或访问
     * {@code /api/fim/probe}。
     */
    private static final String FIM_TEMPLATE = System.getProperty("fim.template", "qwen-no-pipe");

    private static final int MAX_TOKENS = 96;

    /**
     * 结构级 stop 才是主力：模型不会自己收尾。
     *
     * <p>顺序按"从近到远"排列，短序列优先命中，避免多写一个方法：
     *
     * <ul>
     *   <li>{@code \n }\n} —— Java 方法体收尾（实测最关键，缺了它会连续写 mul/div/pow）；
     *   <li>{@code \n\ndef } / {@code \n\nclass } —— Python / 通用顶层定义；
     *   <li>{@code \n\n} —— 通用兜底。
     * </ul>
     */
    private static final String STOP_SEQUENCE =
            "\\n    }\\n|\\n\\ndef |\\n\\nclass |\\n\\nfunc |\\n\\n#|\\n\\n//|\\n\\n";

    /** 留空表示跑全部场景。 */
    private static final int[] SELECTED_SCENARIOS = new int[] {};

    // ==================== ② 场景集 ====================

    /**
     * 场景设计原则：prefix 带足够上下文（import、字段、已声明变量、已完成校验）， suffix 带真实收尾（闭合括号、后续断言、下一个方法），
     * 这样才能验证模型是否"理解前文"且"尊重后文"。
     */
    private static final Scenario[] SCENARIOS = {
        new Scenario(
                "1号 Python：函数体补全（含断言约束）",
                "python",
                "import os\n" + "import json\n" + "\n" + "def add(a, b):\n" + "    ",
                "\n" + "\n" + "assert add(2, 3) == 5\n" + "assert add(-1, 1) == 0\n"),
        new Scenario(
                "2号 Python：分支续写（需与后文对齐）",
                "python",
                "import os\n"
                        + "import json\n"
                        + "\n"
                        + "def load_config(path):\n"
                        + "    if not os.path.exists(path):\n"
                        + "        ",
                "\n" + "    with open(path) as f:\n" + "        return json.load(f)\n"),
        new Scenario(
                "3号 Java：方法体补全",
                "java",
                "public class MathUtil {\n"
                        + "\n"
                        + "    public static int add(int a, int b) {\n"
                        + "        ",
                "\n"
                        + "    }\n"
                        + "\n"
                        + "    public static int sub(int a, int b) {\n"
                        + "        return a - b;\n"
                        + "    }\n"
                        + "}\n"),
        new Scenario(
                "4号 Go：切片求和（惯用写法）",
                "go",
                "package main\n" + "\n" + "func Sum(nums []int) int {\n" + "    ",
                "\n"
                        + "}\n"
                        + "\n"
                        + "func main() {\n"
                        + "    fmt.Println(Sum([]int{1, 2, 3}))\n"
                        + "}\n"),
        new Scenario(
                "5号 SQL：补 JOIN 与过滤条件",
                "sql",
                "SELECT u.name, COUNT(o.id) AS cnt\n" + "FROM users u\n" + "        ",
                "\n" + "GROUP BY u.name;\n"),
        new Scenario(
                "6号 Python：长上下文（多字段+已声明变量）",
                "python",
                "import requests\n"
                        + "import logging\n"
                        + "\n"
                        + "logger = logging.getLogger(__name__)\n"
                        + "\n"
                        + "DEFAULT_TIMEOUT = 10\n"
                        + "MAX_RETRY = 3\n"
                        + "BASE_URL = \"https://api.example.com\"\n"
                        + "\n"
                        + "\n"
                        + "def fetch_user(user_id):\n"
                        + "    \"\"\"按 ID 拉取用户信息。\"\"\"\n"
                        + "    url = f\"{BASE_URL}/users/{user_id}\"\n"
                        + "    ",
                "\n" + "\n" + "if __name__ == \"__main__\":\n" + "    print(fetch_user(1))\n"),
    };

    // ==================== ③ 验证入口 ====================

    /**
     * 主入口：流式实时打印 + TTFT/总耗时统计。
     *
     * <p>运行中即可看到代码逐块长出来，结束后给出汇总表。
     */
    @Test
    void shouldVerifyStreamingCompletion() throws InterruptedException {
        List<Integer> selected = resolveSelection();

        printBanner("流式补全验证", selected);

        StreamingFimModel model = LlmFactory.buildStreamingFimModel(buildConfig());

        List<Latency> latencies = new ArrayList<Latency>();

        for (Integer index : selected) {
            Scenario scenario = SCENARIOS[index.intValue() - 1];
            StreamingPrinter handler = new StreamingPrinter(scenario.name);

            handler.printHeader(scenario);
            long wallStart = System.nanoTime();

            model.complete(
                    FimRequest.builder()
                            .prefix(scenario.prefix)
                            .suffix(scenario.suffix)
                            .language(scenario.language)
                            .build(),
                    handler);

            assertTrue(handler.await(120, TimeUnit.SECONDS), "[" + scenario.name + "] 流式响应超时");
            long wallMillis = (System.nanoTime() - wallStart) / 1_000_000L;

            assertNull(
                    handler.error,
                    "["
                            + scenario.name
                            + "] 流式失败: "
                            + (handler.error == null ? "" : handler.error.getMessage()));
            assertNotNull(handler.response, "[" + scenario.name + "] 最终响应不应为 null");

            // 单独把"空补全"拆出来断言：这是 HTTP 200 + finish=stop 的静默故障，
            // 若只断言"收到块"，错误信息会误导成"流式没工作"
            assertTrue(
                    !handler.response.isEmpty(),
                    "["
                            + scenario.name
                            + "] 模型返回了空补全（finish="
                            + handler.response.finishReason()
                            + "）。请检查 fim.template 是否与部署 tokenizer 匹配");

            assertTrue(handler.chunkCount > 0, "[" + scenario.name + "] 应收到增量块");

            handler.printFooter(wallMillis);
            latencies.add(
                    new Latency(
                            scenario.name,
                            handler.ttftMillis,
                            wallMillis,
                            handler.chunkCount,
                            handler.charCount,
                            scenario.prefix.length(),
                            scenario.suffix.length()));
        }

        printLatencySummary(latencies);
    }

    /**
     * 非流式对照。
     *
     * <p>用同一批场景，便于对照"流式与非流式补全内容是否一致"。
     */
    @Test
    void shouldVerifyBlockingCompletion() {
        List<Integer> selected = resolveSelection();

        printBanner("非流式补全验证", selected);

        FimModel model = LlmFactory.buildFimModel(buildConfig());

        for (Integer index : selected) {
            Scenario scenario = SCENARIOS[index.intValue() - 1];

            FimResponse response =
                    model.complete(
                            FimRequest.builder()
                                    .prefix(scenario.prefix)
                                    .suffix(scenario.suffix)
                                    .language(scenario.language)
                                    .build());

            assertNotNull(response, "[" + scenario.name + "] 响应不应为 null");
            assertTrue(
                    !response.isEmpty(),
                    "["
                            + scenario.name
                            + "] 补全为空（finish="
                            + response.finishReason()
                            + "）。请检查 fim.template 配置");

            System.out.println("---------- 场景" + index + " " + scenario.name + " ----------");
            System.out.println(
                    "latency="
                            + response.latencyMs()
                            + "ms | finish="
                            + response.finishReason()
                            + " | usage="
                            + usageText(response.usage()));
            System.out.println("completion:");
            System.out.println(indent(response.completion()));
            System.out.println();
        }
    }

    /**
     * Token 形态探测：接入前必做。
     *
     * <p>原理：真正的 special token 只占 1 个 token。三个标记裸拼后 {@code prompt_tokens == 3} 才算可用，否则说明该形态未被注册。
     */
    @Test
    void shouldProbeTokenShape() {
        TokenProbe probe = new TokenProbe();

        System.out.println("========== FIM Token 形态探测 ==========");
        System.out.println("baseUrl = " + FIM_BASE_URL);
        System.out.println("model   = " + FIM_MODEL_NAME);
        System.out.println();

        java.util.Map<String, Object> result = probe.probe(FIM_BASE_URL, FIM_MODEL_NAME);

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> candidates =
                (List<java.util.Map<String, Object>>) result.get("candidates");

        boolean anySpecial = false;
        for (java.util.Map<String, Object> candidate : candidates) {
            Object tokens = candidate.get("promptTokens");
            boolean special = Boolean.TRUE.equals(candidate.get("isSpecialToken"));
            anySpecial = anySpecial || special;
            System.out.println(
                    pad(String.valueOf(candidate.get("template")), 14)
                            + (special ? "[可用] " : "[不可用] ")
                            + (tokens == null ? "N/A" : tokens)
                            + " tokens");
        }

        System.out.println();
        System.out.println("推荐模板 = " + result.get("recommendedTemplate"));
        System.out.println("说明     = " + result.get("hint"));
        System.out.println();

        assertTrue(anySpecial, "未探测到任何可用的 FIM 特殊 Token 形态，请检查部署或改用 native-suffix");
    }

    // ==================== ④ 内部方法 ====================

    private LlmBasicConfig buildConfig() {
        LlmBasicConfig.Builder builder =
                LlmBasicConfig.builder()
                        .provider(LlmEnum.OPENAI.getCode())
                        .url(FIM_BASE_URL)
                        .modelName(FIM_MODEL_NAME)
                        .prop(LlmConstant.FIM_TEMPLATE, FIM_TEMPLATE)
                        .prop(LlmConstant.TEMPERATURE, "0")
                        .prop(LlmConstant.MAX_TOKENS, String.valueOf(MAX_TOKENS))
                        .prop(LlmConstant.TIMEOUT, "60");
        if (STOP_SEQUENCE != null && !STOP_SEQUENCE.trim().isEmpty()) {
            builder.prop(LlmConstant.STOP, STOP_SEQUENCE);
        }
        return builder.build();
    }

    private void printBanner(String title, List<Integer> selected) {
        System.out.println("========== " + title + " ==========");
        System.out.println("baseUrl  = " + FIM_BASE_URL);
        System.out.println("model    = " + FIM_MODEL_NAME);
        System.out.println("template = " + describeTemplate());
        System.out.println("maxTokens= " + MAX_TOKENS);
        System.out.println("场景数   = " + selected.size() + " / 共 " + SCENARIOS.length);
        System.out.println("已选     = " + selected);
        System.out.println();
    }

    /** 把配置的模板名解析成实际模板，便于确认"配的"和"用的"一致。 */
    private static String describeTemplate() {
        FimTemplate template = FimTemplates.forName(FIM_TEMPLATE);
        if (template == null) {
            return FIM_TEMPLATE + "（无法识别，装配时会报错）";
        }
        return template.name() + " / " + template.format();
    }

    /** 优先级：命令行 {@code -Dfim.scenarios} > 代码常量 > 全部。 */
    private static List<Integer> resolveSelection() {
        List<Integer> fromProperty = parseSelection(System.getProperty("fim.scenarios", ""));
        if (!fromProperty.isEmpty()) {
            return validate(fromProperty);
        }
        if (SELECTED_SCENARIOS != null && SELECTED_SCENARIOS.length > 0) {
            List<Integer> fromCode = new ArrayList<Integer>();
            for (int index : SELECTED_SCENARIOS) {
                fromCode.add(Integer.valueOf(index));
            }
            return validate(fromCode);
        }
        return allScenarioIndexes();
    }

    private static List<Integer> parseSelection(String raw) {
        List<Integer> result = new ArrayList<Integer>();
        if (raw == null) {
            return result;
        }
        String cleaned = raw.replace("[", "").replace("]", "").trim();
        if (cleaned.isEmpty()) {
            return result;
        }
        Set<Integer> unique = new LinkedHashSet<Integer>();
        for (String part : cleaned.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                unique.add(Integer.valueOf(Integer.parseInt(trimmed)));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "无法解析场景编号: \"" + trimmed + "\"，合法范围 1-" + SCENARIOS.length, e);
            }
        }
        result.addAll(unique);
        return result;
    }

    /** 编号越界立刻失败，避免"以为跑过了其实没跑"。 */
    private static List<Integer> validate(List<Integer> selection) {
        for (Integer index : selection) {
            int value = index.intValue();
            if (value < 1 || value > SCENARIOS.length) {
                throw new IllegalArgumentException(
                        "场景编号越界: " + value + "，合法范围 1-" + SCENARIOS.length);
            }
        }
        return selection;
    }

    private static List<Integer> allScenarioIndexes() {
        List<Integer> all = new ArrayList<Integer>();
        for (int i = 1; i <= SCENARIOS.length; i++) {
            all.add(Integer.valueOf(i));
        }
        return all;
    }

    private static String usageText(FimUsage usage) {
        if (usage == null) {
            return "N/A";
        }
        return "in="
                + usage.inputTokens()
                + ", out="
                + usage.outputTokens()
                + ", total="
                + usage.totalTokens();
    }

    private static String indent(String text) {
        if (text == null || text.isEmpty()) {
            return "  <空>";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static String pad(String text, int width) {
        StringBuilder sb = new StringBuilder(text == null ? "" : text);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static void printLatencySummary(List<Latency> latencies) {
        System.out.println();
        System.out.println("========== 耗时汇总 ==========");
        System.out.println(
                pad("场景", 40)
                        + pad("prefix", 8)
                        + pad("suffix", 8)
                        + pad("TTFT", 9)
                        + pad("总耗时", 10)
                        + pad("块数", 6)
                        + "字符");

        for (Latency latency : latencies) {
            System.out.println(
                    pad(latency.shorten(38), 40)
                            + pad(String.valueOf(latency.prefixChars), 8)
                            + pad(String.valueOf(latency.suffixChars), 8)
                            + pad(latency.ttftMillis + "ms", 9)
                            + pad(latency.totalMillis + "ms", 10)
                            + pad(String.valueOf(latency.chunks), 6)
                            + latency.chars);
        }

        long sumTtft = 0L;
        long sumTotal = 0L;
        for (Latency latency : latencies) {
            sumTtft += latency.ttftMillis;
            sumTotal += latency.totalMillis;
        }
        int count = Math.max(latencies.size(), 1);
        System.out.println();
        System.out.println(
                "均值：TTFT " + (sumTtft / count) + "ms | 总耗时 " + (sumTotal / count) + "ms");
        System.out.println();
    }

    // ==================== ⑤ 辅助类型 ====================

    /** 场景：一段 prefix + 一段 suffix + 语言。 */
    private static final class Scenario {
        private final String name;
        private final String language;
        private final String prefix;
        private final String suffix;

        private Scenario(String name, String language, String prefix, String suffix) {
            this.name = name;
            this.language = language;
            this.prefix = prefix;
            this.suffix = suffix;
        }
    }

    /** 单次耗时记录。 */
    private static final class Latency {
        private final String name;
        private final long ttftMillis;
        private final long totalMillis;
        private final int chunks;
        private final int chars;
        private final int prefixChars;
        private final int suffixChars;

        private Latency(
                String name,
                long ttftMillis,
                long totalMillis,
                int chunks,
                int chars,
                int prefixChars,
                int suffixChars) {
            this.name = name;
            this.ttftMillis = ttftMillis;
            this.totalMillis = totalMillis;
            this.chunks = chunks;
            this.chars = chars;
            this.prefixChars = prefixChars;
            this.suffixChars = suffixChars;
        }

        private String shorten(int max) {
            if (name.length() <= max) {
                return name;
            }
            return name.substring(0, max - 1) + "…";
        }
    }

    /**
     * 流式处理器：实时打印每个增量块。
     *
     * <p>刻意不做缓冲 —— 缓冲会让"流式"看起来和"非流式"一样，失去验证意义。
     */
    private static final class StreamingPrinter implements StreamingFimResponseHandler {
        private final String name;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final StringBuilder received = new StringBuilder();

        /** 请求发起时刻，用于计算相对 TTFT（而不是记录绝对时间戳）。 */
        private final long startMillis = System.currentTimeMillis();

        private volatile long ttftMillis = -1L;
        private volatile int chunkCount;
        private volatile int charCount;
        private volatile FimResponse response;
        private volatile Throwable error;

        private StreamingPrinter(String name) {
            this.name = name;
        }

        private void printHeader(Scenario scenario) {
            System.out.println("---------- " + name + " ----------");
            System.out.println(
                    "language="
                            + scenario.language
                            + " | prefix="
                            + scenario.prefix.length()
                            + " 字符 | suffix="
                            + scenario.suffix.length()
                            + " 字符");
            System.out.print("completion: ");
        }

        @Override
        public void onFirstChunk(FimChunk chunk) {
            ttftMillis = System.currentTimeMillis() - startMillis;
        }

        @Override
        public void onPartialCompletion(FimChunk chunk) {
            chunkCount++;
            String delta = chunk.delta();
            charCount += delta.length();
            received.append(delta);
            // 实时输出：把换行可视化成 ⏎，否则多行补全会把控制台排版打乱
            System.out.print(delta.replace("\n", "⏎\n  "));
            System.out.flush();
        }

        @Override
        public void onComplete(FimResponse completeResponse) {
            this.response = completeResponse;
            System.out.println();
            System.out.println(
                    "  ↳ finish="
                            + completeResponse.finishReason()
                            + " | usage="
                            + usageText(completeResponse.usage()));
            latch.countDown();
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            System.out.println();
            System.out.println("  ↳ ERROR: " + error.getMessage());
            latch.countDown();
        }

        private boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }

        private void printFooter(long wallMillis) {
            // 首块到首块本身耗时无法精确算，这里用"总耗时 - 服务端处理耗时"近似
            long waitMillis =
                    ttftMillis > 0 ? Math.max(wallMillis - response.latencyMs(), 0L) : -1L;
            System.out.println(
                    "  ↳ 总耗时 "
                            + wallMillis
                            + "ms | 服务端 "
                            + response.latencyMs()
                            + "ms | 块数 "
                            + chunkCount
                            + " | 字符 "
                            + charCount
                            + (waitMillis >= 0 ? " | 首块等待≈" + waitMillis + "ms" : ""));
            System.out.println();
        }
    }
}
