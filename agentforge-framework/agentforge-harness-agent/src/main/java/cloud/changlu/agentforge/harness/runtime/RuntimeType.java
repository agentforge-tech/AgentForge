package cloud.changlu.agentforge.harness.runtime;

/** Execution environment selected for a HarnessAgent. */
public enum RuntimeType {
    LOCAL(1, "local", "Local workspace runtime");

    private final int code;
    private final String runtimeName;
    private final String description;

    RuntimeType(int code, String runtimeName, String description) {
        this.code = code;
        this.runtimeName = runtimeName;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getName() {
        return runtimeName;
    }

    public String getDescription() {
        return description;
    }

    public static RuntimeType fromCode(Integer code) {
        if (code == null) return null;
        for (RuntimeType type : values()) {
            if (type.code == code) return type;
        }
        return null;
    }

    public static RuntimeType fromName(String name) {
        if (name == null) return null;
        for (RuntimeType type : values()) {
            if (type.runtimeName.equalsIgnoreCase(name.trim())) return type;
        }
        return null;
    }
}
