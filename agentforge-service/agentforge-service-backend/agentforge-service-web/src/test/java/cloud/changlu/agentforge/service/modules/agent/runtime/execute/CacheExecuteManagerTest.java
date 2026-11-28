package cloud.changlu.agentforge.service.modules.agent.runtime.execute;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CacheExecuteManagerTest {

    private static final String SESSION = "chat-1";
    private static final long MESSAGE_ID = 100L;
    private static final String EXECUTION_ID = "exec-1";

    @Test
    public void shouldRegisterAndDetectRunning() {
        CacheExecuteManager manager = new CacheExecuteManager();

        assertTrue(manager.register(SESSION, MESSAGE_ID, EXECUTION_ID, () -> {}));
        assertTrue(manager.isRunning(SESSION, MESSAGE_ID));
        assertEquals(1, manager.size());
    }

    @Test
    public void shouldRejectDuplicateRegistrationForSameMessage() {
        CacheExecuteManager manager = new CacheExecuteManager();

        assertTrue(manager.register(SESSION, MESSAGE_ID, EXECUTION_ID, () -> {}));
        assertFalse(manager.register(SESSION, MESSAGE_ID, "exec-2", () -> {}));
        assertEquals(1, manager.size());
    }

    @Test
    public void shouldRejectRegistrationWithNullIds() {
        CacheExecuteManager manager = new CacheExecuteManager();

        assertFalse(manager.register(null, MESSAGE_ID, EXECUTION_ID, () -> {}));
        assertFalse(manager.register(SESSION, null, EXECUTION_ID, () -> {}));
        assertEquals(0, manager.size());
    }

    @Test
    public void shouldNotReportRunningWhenSessionMismatched() {
        CacheExecuteManager manager = new CacheExecuteManager();
        manager.register(SESSION, MESSAGE_ID, EXECUTION_ID, () -> {});

        assertFalse(manager.isRunning("chat-other", MESSAGE_ID));
        assertFalse(manager.isRunning(SESSION, 999L));
    }

    @Test
    public void shouldUnregisterAndClearRunningState() {
        CacheExecuteManager manager = new CacheExecuteManager();
        manager.register(SESSION, MESSAGE_ID, EXECUTION_ID, () -> {});

        assertTrue(manager.unregister(MESSAGE_ID));
        assertFalse(manager.isRunning(SESSION, MESSAGE_ID));
        // 重复注销返回 false，保证收口幂等。
        assertFalse(manager.unregister(MESSAGE_ID));
    }

    @Test
    public void shouldTakeCancelHandleOnlyOnce() {
        CacheExecuteManager manager = new CacheExecuteManager();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        manager.register(SESSION, MESSAGE_ID, EXECUTION_ID, () -> cancelled.set(true));

        Runnable cancel = manager.takeCancel(MESSAGE_ID);
        assertNotNull(cancel);
        cancel.run();
        assertTrue(cancelled.get());

        // 取走即注销，第二次取不到，避免重复下发取消。
        assertNull(manager.takeCancel(MESSAGE_ID));
        assertFalse(manager.isRunning(SESSION, MESSAGE_ID));
    }
}
