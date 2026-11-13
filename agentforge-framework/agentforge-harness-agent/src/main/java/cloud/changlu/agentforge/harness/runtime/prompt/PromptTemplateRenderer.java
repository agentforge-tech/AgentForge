package cloud.changlu.agentforge.harness.runtime.prompt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, dependency-free renderer for runtime prompt templates. */
public final class PromptTemplateRenderer {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.-]*)}");

    private PromptTemplateRenderer() {}

    public static String renderResource(
            Class<?> resourceOwner, String resourcePath, Map<String, ?> variables) {
        if (resourceOwner == null)
            throw new IllegalArgumentException("resourceOwner must not be null");
        if (resourcePath == null || resourcePath.trim().isEmpty()) {
            throw new IllegalArgumentException("resourcePath must not be blank");
        }
        return render(readResource(resourceOwner, resourcePath), variables);
    }

    public static String render(String template, Map<String, ?> variables) {
        if (template == null) throw new IllegalArgumentException("template must not be null");
        if (variables == null) throw new IllegalArgumentException("variables must not be null");

        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!variables.containsKey(name) || variables.get(name) == null) {
                throw new IllegalArgumentException("missing prompt template variable: " + name);
            }
            matcher.appendReplacement(
                    rendered, Matcher.quoteReplacement(String.valueOf(variables.get(name))));
        }
        matcher.appendTail(rendered);
        return rendered.toString().trim();
    }

    private static String readResource(Class<?> resourceOwner, String resourcePath) {
        InputStream input = resourceOwner.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalArgumentException(
                    "prompt template resource not found: " + resourcePath);
        }
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (content.length() > 0) content.append('\n');
                content.append(line);
            }
            return content.toString();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read prompt template: " + resourcePath, e);
        }
    }
}
