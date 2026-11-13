package cloud.changlu.agentforge.harness.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RuntimeTypeTest {

    @Test
    public void exposesStableRuntimeMetadata() {
        assertEquals(1, RuntimeType.LOCAL.getCode());
        assertEquals("local", RuntimeType.LOCAL.getName());
        assertEquals("Local workspace runtime", RuntimeType.LOCAL.getDescription());
    }

    @Test
    public void resolvesByCodeAndName() {
        assertEquals(RuntimeType.LOCAL, RuntimeType.fromCode(1));
        assertEquals(RuntimeType.LOCAL, RuntimeType.fromName("LOCAL"));
        assertNull(RuntimeType.fromCode(null));
        assertNull(RuntimeType.fromCode(999));
        assertNull(RuntimeType.fromName(null));
        assertNull(RuntimeType.fromName("unknown"));
    }
}
