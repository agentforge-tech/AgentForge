package cloud.changlu.agentforge.agent.extend.middlewares.read.tool;

import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadBatchResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadResult;
import cloud.changlu.agentforge.agent.extend.middlewares.read.support.ReadResultJson;
import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * 无状态读取工具：只提取远程 URL 的元数据，不读取正文。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class ReadResourceTool {

    public static final String TOOL_NAME = "read_resource";

    private final ReadResourceFetcher fetcher;
    private final Logger log;

    private ReadResourceTool(Builder builder) {
        Logger logger =
                builder.log == null
                        ? Logger.getLogger(ReadResourceTool.class.getName())
                        : builder.log;
        this.log = logger;
        ReadToolOptions options =
                builder.options == null ? ReadToolOptions.defaults() : builder.options;
        this.fetcher =
                builder.fetcher == null
                        ? new HttpReadResourceFetcher(options, logger)
                        : builder.fetcher;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Tool(
            name = TOOL_NAME,
            value =
                    "Read metadata for one or more image or web page URLs. "
                            + "Use this when the user provides http/https image links or article links that need understanding. "
                            + "Always pass paths as a list, even for a single resource. "
                            + "Only http/https URLs are supported. "
                            + "For images this tool returns metadata and an accessible URL; image bytes are loaded by the middleware. "
                            + "For web pages this tool returns metadata; page text is extracted by the middleware.")
    public String read(
            @P(
                            name = "paths",
                            description =
                                    "List of http/https image URLs, 1 to 5 items, for example [\"https://example.com/a.png\"]")
                    List<String> paths,
            @P(name = "hint", description = "why these resources are needed", required = false)
                    String hint) {
        long start = System.currentTimeMillis();
        int count = paths == null ? 0 : paths.size();
        log.fine("read_resource.start paths=" + count + " hint=" + safe(hint));
        ReadBatchResult batch =
                fetcher.fetch(paths == null ? Collections.<String>emptyList() : paths, hint);
        log.fine(
                "read_resource.done success="
                        + successCount(batch)
                        + " failed="
                        + failCount(batch)
                        + " elapsedMs="
                        + (System.currentTimeMillis() - start));
        return ReadResultJson.toJson(batch);
    }

    private static int successCount(ReadBatchResult batch) {
        int count = 0;
        for (ReadResult result : batch.getResults()) {
            if (result.isOk()) {
                count++;
            }
        }
        return count;
    }

    private static int failCount(ReadBatchResult batch) {
        return batch.getResults().size() - successCount(batch);
    }

    private static String safe(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    public static final class Builder {
        private ReadResourceFetcher fetcher;
        private Logger log;
        private ReadToolOptions options;

        private Builder() {}

        public Builder fetcher(ReadResourceFetcher fetcher) {
            this.fetcher = fetcher;
            return this;
        }

        public Builder log(Logger log) {
            this.log = log;
            return this;
        }

        public Builder options(ReadToolOptions options) {
            this.options = options;
            return this;
        }

        public ReadResourceTool build() {
            return new ReadResourceTool(this);
        }
    }
}
