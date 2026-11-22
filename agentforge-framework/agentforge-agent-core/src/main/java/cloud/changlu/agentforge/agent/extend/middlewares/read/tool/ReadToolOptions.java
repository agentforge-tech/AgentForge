package cloud.changlu.agentforge.agent.extend.middlewares.read.tool;

/**
 * 无状态读取工具的默认限制。
 *
 * @author changlu
 * @since 2026-11-22
 */
public final class ReadToolOptions {

    private final int maxPaths;
    private final long maxImageBytes;
    private final int timeoutMillis;

    private ReadToolOptions(Builder builder) {
        this.maxPaths = builder.maxPaths;
        this.maxImageBytes = builder.maxImageBytes;
        this.timeoutMillis = builder.timeoutMillis;
    }

    public static ReadToolOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getMaxPaths() {
        return maxPaths;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public static final class Builder {
        private int maxPaths = 5;
        private long maxImageBytes = 10L * 1024 * 1024;
        private int timeoutMillis = 5000;

        private Builder() {}

        public Builder maxPaths(int maxPaths) {
            this.maxPaths = maxPaths;
            return this;
        }

        public Builder maxImageBytes(long maxImageBytes) {
            this.maxImageBytes = maxImageBytes;
            return this;
        }

        public Builder timeoutMillis(int timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
            return this;
        }

        public ReadToolOptions build() {
            return new ReadToolOptions(this);
        }
    }
}
