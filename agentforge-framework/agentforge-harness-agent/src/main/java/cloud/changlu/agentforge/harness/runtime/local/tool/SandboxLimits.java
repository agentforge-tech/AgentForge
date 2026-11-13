package cloud.changlu.agentforge.harness.runtime.local.tool;

/** Resource limits applied by local workspace tools. */
public final class SandboxLimits {

    private final int maxReadLines;
    private final int maxLineChars;
    private final long maxFileBytes;
    private final int maxSearchResults;

    private SandboxLimits(Builder builder) {
        this.maxReadLines = positive(builder.maxReadLines, 2000, "maxReadLines");
        this.maxLineChars = positive(builder.maxLineChars, 4000, "maxLineChars");
        this.maxFileBytes = positive(builder.maxFileBytes, 2 * 1024 * 1024L, "maxFileBytes");
        this.maxSearchResults = positive(builder.maxSearchResults, 200, "maxSearchResults");
    }

    public static SandboxLimits defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxReadLines() {
        return maxReadLines;
    }

    public int maxLineChars() {
        return maxLineChars;
    }

    public long maxFileBytes() {
        return maxFileBytes;
    }

    public int maxSearchResults() {
        return maxSearchResults;
    }

    private static int positive(Integer value, int fallback, String name) {
        int result = value == null ? fallback : value;
        if (result <= 0) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }

    private static long positive(Long value, long fallback, String name) {
        long result = value == null ? fallback : value;
        if (result <= 0) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }

    public static final class Builder {
        private Integer maxReadLines;
        private Integer maxLineChars;
        private Long maxFileBytes;
        private Integer maxSearchResults;

        private Builder() {}

        public Builder maxReadLines(int value) {
            this.maxReadLines = value;
            return this;
        }

        public Builder maxLineChars(int value) {
            this.maxLineChars = value;
            return this;
        }

        public Builder maxFileBytes(long value) {
            this.maxFileBytes = value;
            return this;
        }

        public Builder maxSearchResults(int value) {
            this.maxSearchResults = value;
            return this;
        }

        public SandboxLimits build() {
            return new SandboxLimits(this);
        }
    }
}
