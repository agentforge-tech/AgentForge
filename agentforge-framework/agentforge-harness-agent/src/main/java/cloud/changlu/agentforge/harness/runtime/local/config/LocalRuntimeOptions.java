package cloud.changlu.agentforge.harness.runtime.local.config;

import cloud.changlu.agentforge.harness.runtime.RuntimeOptions;
import cloud.changlu.agentforge.harness.runtime.RuntimeType;

/** Local process execution options. */
public final class LocalRuntimeOptions implements RuntimeOptions {

    private final String shell;
    private final long commandTimeoutMillis;
    private final int maxCommandOutputChars;

    private LocalRuntimeOptions(Builder builder) {
        this.shell = isBlank(builder.shell) ? "/bin/sh" : builder.shell;
        this.commandTimeoutMillis =
                positive(builder.commandTimeoutMillis, 30000L, "commandTimeoutMillis");
        this.maxCommandOutputChars =
                positive(builder.maxCommandOutputChars, 20000, "maxCommandOutputChars");
    }

    public static LocalRuntimeOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.LOCAL;
    }

    public String shell() {
        return shell;
    }

    public long commandTimeoutMillis() {
        return commandTimeoutMillis;
    }

    public int maxCommandOutputChars() {
        return maxCommandOutputChars;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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
        private String shell;
        private Long commandTimeoutMillis;
        private Integer maxCommandOutputChars;

        private Builder() {}

        public Builder shell(String shell) {
            this.shell = shell;
            return this;
        }

        public Builder commandTimeoutMillis(long commandTimeoutMillis) {
            this.commandTimeoutMillis = commandTimeoutMillis;
            return this;
        }

        public Builder maxCommandOutputChars(int maxCommandOutputChars) {
            this.maxCommandOutputChars = maxCommandOutputChars;
            return this;
        }

        public LocalRuntimeOptions build() {
            return new LocalRuntimeOptions(this);
        }
    }
}
