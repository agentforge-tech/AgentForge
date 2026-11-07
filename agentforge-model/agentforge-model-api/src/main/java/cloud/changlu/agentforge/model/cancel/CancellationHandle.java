package cloud.changlu.agentforge.model.cancel;

/** Provider-neutral handle for actively cancelling an in-flight operation. */
public interface CancellationHandle {
    boolean cancel();

    boolean isCancelled();

    CancellationHandle NONE =
            new CancellationHandle() {
                @Override
                public boolean cancel() {
                    return false;
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            };
}
