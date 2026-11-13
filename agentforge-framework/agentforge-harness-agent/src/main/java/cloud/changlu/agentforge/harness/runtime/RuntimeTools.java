package cloud.changlu.agentforge.harness.runtime;

import java.util.Collection;

/** Tools supplied by one runtime implementation to the underlying Agent. */
public interface RuntimeTools extends AutoCloseable {

    RuntimeType type();

    Collection<Object> toolObjects();

    void cancelAll();

    @Override
    void close();
}
