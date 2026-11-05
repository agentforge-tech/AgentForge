package cloud.changlu.agentforge.service.protocol.processor.retry;

import cloud.changlu.agentforge.service.protocol.StreamEventResponse;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** {@link AiRetryProcessor} 事件协议测试。 */
public class AiRetryProcessorTest {

    @Test
    public void shouldBuildRetryEventWithStepAttemptAndDelaySeconds() {
        StreamEventResponse event = AiRetryProcessor.retry(2, 1, 3, 4000L);

        assertEquals("retry", event.getEvent());
        assertEquals("retry", event.getType());
        assertFalse(event.getIsFinish());

        AiRetryProcessor.EventContent content = (AiRetryProcessor.EventContent) event.getContent();
        assertEquals(Integer.valueOf(2), content.getCurrentStep());
        assertEquals(Integer.valueOf(1), content.getRetryCount());
        assertEquals(Integer.valueOf(3), content.getMaxRetries());
        assertEquals(Long.valueOf(4000L), content.getDelayMs());
        assertEquals(Long.valueOf(4L), content.getDelaySeconds());
        assertEquals(Long.valueOf(content.getEventAt() + 4000L), content.getRetryAt());
    }

    @Test
    public void shouldUseRetryAsSceneCode() {
        assertEquals("retry", new AiRetryProcessor().getSceneCode());
    }
}
