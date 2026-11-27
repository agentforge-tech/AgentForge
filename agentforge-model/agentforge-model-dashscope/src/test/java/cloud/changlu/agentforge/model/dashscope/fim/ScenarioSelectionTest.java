package cloud.changlu.agentforge.model.dashscope.fim;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 场景选择解析的测试（不访问网络）。
 *
 * <p>验证 {@code "2,4"} / {@code "[2,4]"} / {@code "2 4"} 等写法的解析，以及越界时的显式报错。
 *
 * @author changlu
 * @date 2026/11/25
 */
public class ScenarioSelectionTest {

    @Test
    public void shouldParseCommaSeparated() throws Exception {
        assertEquals(Arrays.asList(2, 4), parse("2,4"));
    }

    @Test
    public void shouldParseBracketForm() throws Exception {
        assertEquals(Arrays.asList(2, 4), parse("[2,4]"));
    }

    @Test
    public void shouldParseSingleValue() throws Exception {
        assertEquals(Collections.singletonList(3), parse("3"));
    }

    @Test
    public void shouldParseSpaceAndSemicolonSeparated() throws Exception {
        assertEquals(Arrays.asList(1, 3, 5), parse("1 3;5"));
    }

    @Test
    public void shouldReturnEmptyForBlank() throws Exception {
        assertTrue(parse("").isEmpty());
        assertTrue(parse("   ").isEmpty());
    }

    @Test
    public void shouldTolerateTrailingComma() throws Exception {
        assertEquals(Arrays.asList(2, 4), parse("2,4,"));
    }

    @Test
    public void shouldRejectNonNumeric() {
        // 反射调用会把异常包装成 InvocationTargetException，这里断言底层原因
        Throwable error = assertThrows(Exception.class, () -> parse("2,abc"));
        Throwable cause =
                error instanceof java.lang.reflect.InvocationTargetException
                        ? error.getCause()
                        : error;
        assertTrue(
                "底层应是 IllegalArgumentException，实际: " + cause,
                cause instanceof IllegalArgumentException);
        assertTrue(cause.getMessage().contains("abc"));
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> parse(String raw) throws Exception {
        Method method =
                DashScopeLiveVerificationTest.class.getDeclaredMethod(
                        "parseSelection", String.class);
        method.setAccessible(true);
        return (List<Integer>) method.invoke(null, raw);
    }
}
