package cloud.changlu.agentforge.harness.runtime.prompt;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PromptTemplateRendererTest {

    @Test
    public void rendersVariablesLiterally() {
        assertEquals(
                "workspace=$project\\root",
                PromptTemplateRenderer.render(
                        "workspace=${root}",
                        Collections.<String, Object>singletonMap("root", "$project\\root")));
    }

    @Test
    public void rejectsMissingVariables() {
        try {
            PromptTemplateRenderer.render("workspace=${root}", Collections.emptyMap());
            fail("missing variables must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("root"));
        }
    }
}
