package cloud.changlu.agentforge.agent.extend.middlewares.append.store;

import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.AppendedUserMessage;
import cloud.changlu.agentforge.agent.extend.middlewares.append.domain.UserMessageAppendResult;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InMemoryUserMessageAppendStoreTest {

    @Test
    public void shouldRequireOpenAndDrainInFifoOrder() {
        InMemoryUserMessageAppendStore store = new InMemoryUserMessageAppendStore(2);
        AppendedUserMessage first = message("1");
        AppendedUserMessage second = message("2");

        assertEquals(
                UserMessageAppendResult.Code.RUN_NOT_OPEN,
                store.enqueue("session", first).getCode());
        assertTrue(store.open("session"));
        assertEquals(1, store.enqueue("session", first).getQueuePosition());
        assertEquals(2, store.enqueue("session", second).getQueuePosition());
        assertEquals(
                UserMessageAppendResult.Code.QUEUE_FULL,
                store.enqueue("session", message("3")).getCode());

        List<AppendedUserMessage> drained = store.drain("session");
        assertEquals(Long.valueOf(1), drained.get(0).getAppendId());
        assertEquals(Long.valueOf(2), drained.get(1).getAppendId());
        assertEquals(0, store.size("session"));
    }

    @Test
    public void closeShouldReturnResidualAndRejectLateEnqueue() {
        InMemoryUserMessageAppendStore store = new InMemoryUserMessageAppendStore(2);
        assertTrue(store.open("session"));
        store.enqueue("session", message("1"));

        assertEquals(1, store.closeAndDrain("session").size());
        assertEquals(
                UserMessageAppendResult.Code.RUN_ALREADY_CLOSED,
                store.enqueue("session", message("2")).getCode());
        assertTrue(store.closeAndDrain("session").isEmpty());
    }

    @Test
    public void cancelShouldOnlyRemovePendingMessage() {
        InMemoryUserMessageAppendStore store = new InMemoryUserMessageAppendStore(2);
        store.open("session");
        store.enqueue("session", message("1"));

        assertTrue(store.remove("session", 1L).isPresent());
        assertFalse(store.remove("session", 1L).isPresent());
        assertTrue(store.drain("session").isEmpty());
    }

    private static AppendedUserMessage message(String id) {
        return new AppendedUserMessage(
                Long.valueOf(id), "message-" + id, System.currentTimeMillis());
    }
}
