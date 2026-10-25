package cloud.changlu.agentforge.service.common.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AidGeneratorUtilTest {

    @Test
    public void shouldEncodeLongAsBase32() {
        assertEquals("0", AidGeneratorUtil.toBase32(0));
        assertEquals("v", AidGeneratorUtil.toBase32(31));
        assertEquals("10", AidGeneratorUtil.toBase32(32));
    }

    @Test
    public void shouldGenerateCompactUniqueIds() {
        AidGeneratorUtil generator = AidGeneratorUtil.getInstance();

        String first = generator.nextId();
        String second = generator.nextId();

        assertNotEquals(first, second);
        assertTrue(first.matches("[0-9a-v]+"));
        assertTrue(second.matches("[0-9a-v]+"));
        assertTrue(first.length() < 32);
        assertTrue(second.length() < 32);
    }
}
