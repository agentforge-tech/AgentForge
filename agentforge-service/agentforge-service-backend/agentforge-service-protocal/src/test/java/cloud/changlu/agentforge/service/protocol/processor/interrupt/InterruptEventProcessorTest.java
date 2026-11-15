package cloud.changlu.agentforge.service.protocol.processor.interrupt;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class InterruptEventProcessorTest {

    @Test
    public void shouldCreateRequestedEvent() {
        StreamEventResponse event =
                InterruptEventProcessor.requested(
                        Collections.<String, Object>singletonMap("batchId", "batch-1"));

        assertEquals("interrupt", event.getEvent());
        assertEquals("interrupt_requested", event.getType());
        assertFalse(event.getIsFinish());
    }
}
