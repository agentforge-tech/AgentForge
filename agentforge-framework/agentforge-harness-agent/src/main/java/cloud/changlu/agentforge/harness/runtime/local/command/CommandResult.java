package cloud.changlu.agentforge.harness.runtime.local.command;

/** Result of a foreground local command. */
public final class CommandResult {
    private final int exitCode;
    private final boolean timedOut;
    private final String output;

    CommandResult(int exitCode, boolean timedOut, String output) {
        this.exitCode = exitCode;
        this.timedOut = timedOut;
        this.output = output;
    }

    public int exitCode() {
        return exitCode;
    }

    public boolean timedOut() {
        return timedOut;
    }

    public String output() {
        return output;
    }

    @Override
    public String toString() {
        return "exitCode=" + exitCode + ", timedOut=" + timedOut + "\n" + output;
    }
}
