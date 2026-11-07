package cloud.changlu.agentforge.model.tool.spec;

import cloud.changlu.agentforge.model.tool.P;
import cloud.changlu.agentforge.model.tool.Tool;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link ToolSpecification}s from {@link Tool}-annotated methods.
 *
 * @author changlu
 * @since 2026-09-13
 */
public final class ToolSpecifications {

    private ToolSpecifications() {}

    /**
     * Returns a {@link ToolSpecification} for a {@link Tool}-annotated method.
     *
     * @param method the method
     * @return the tool specification
     */
    public static ToolSpecification toolSpecificationFrom(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        if (tool == null) {
            throw new IllegalArgumentException(
                    "Method '" + method + "' is not annotated with @Tool");
        }
        return ToolSpecification.builder()
                .name(toolNameFrom(method))
                .description(joinAndNullIfBlank(tool.value()))
                .parameters(parametersFrom(method))
                .build();
    }

    /**
     * Returns the name under which the given method is exposed to the LLM: the {@link Tool#name()}
     * attribute when set, otherwise the Java method name.
     *
     * @param method the method
     * @return the tool name
     */
    public static String toolNameFrom(Method method) {
        Tool tool = method.getAnnotation(Tool.class);
        String explicitName = tool == null ? "" : tool.name();
        return isNullOrBlank(explicitName) ? method.getName() : explicitName;
    }

    /**
     * Scans the object (and its class hierarchy) for {@link Tool}-annotated methods and returns
     * their {@link ToolSpecification}s.
     *
     * @param objectWithTools the object
     * @return tool specifications
     */
    public static List<ToolSpecification> toolSpecificationsFrom(Object objectWithTools) {
        return toolSpecificationsFrom(objectWithTools.getClass());
    }

    public static List<ToolSpecification> toolSpecificationsFrom(Class<?> classWithTools) {
        List<ToolSpecification> specifications = new ArrayList<ToolSpecification>();
        for (Method method : allConcreteMethods(classWithTools)) {
            if (method.isAnnotationPresent(Tool.class)) {
                specifications.add(toolSpecificationFrom(method));
            }
        }
        return specifications;
    }

    /**
     * Validates that all tool names are unique; throws {@link IllegalArgumentException} otherwise.
     *
     * @param toolSpecifications the tool specifications
     */
    public static void validateSpecifications(List<ToolSpecification> toolSpecifications) {
        Set<String> names = new LinkedHashSet<String>();
        for (ToolSpecification toolSpecification : toolSpecifications) {
            if (!names.add(toolSpecification.name())) {
                throw new IllegalArgumentException(
                        "Tool names must be unique. The tool '"
                                + toolSpecification.name()
                                + "' appears several times");
            }
        }
    }

    private static ToolParameters parametersFrom(Method method) {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return null;
        }
        ToolParameters.Builder builder = ToolParameters.builder();
        for (Parameter parameter : parameters) {
            P pAnnotation = parameter.getAnnotation(P.class);
            String parameterName =
                    pAnnotation != null && !isNullOrBlank(pAnnotation.name())
                            ? pAnnotation.name()
                            : parameter.getName();
            String description = null;
            if (pAnnotation != null) {
                description =
                        !isNullOrBlank(pAnnotation.description())
                                ? pAnnotation.description()
                                : (!isNullOrBlank(pAnnotation.value())
                                        ? pAnnotation.value()
                                        : null);
            }
            Map<String, Object> definition =
                    schemaFor(parameter.getParameterizedType(), new HashSet<Class<?>>());
            if (description != null) {
                definition.put("description", description);
            }
            builder.addProperty(parameterName, definition, true);
        }
        return builder.build();
    }

    private static Map<String, Object> schemaFor(Type type, Set<Class<?>> visiting) {
        Class<?> rawType = rawClass(type);
        if (rawType == null || rawType == Object.class) {
            return typeSchema("object");
        }
        if (rawType == String.class
                || rawType == char.class
                || rawType == Character.class
                || rawType == java.util.UUID.class) {
            return typeSchema("string");
        }
        if (rawType == int.class
                || rawType == Integer.class
                || rawType == long.class
                || rawType == Long.class
                || rawType == short.class
                || rawType == Short.class
                || rawType == byte.class
                || rawType == Byte.class
                || rawType == java.math.BigInteger.class) {
            return typeSchema("integer");
        }
        if (rawType == double.class
                || rawType == Double.class
                || rawType == float.class
                || rawType == Float.class
                || rawType == java.math.BigDecimal.class
                || Number.class.isAssignableFrom(rawType)) {
            return typeSchema("number");
        }
        if (rawType == boolean.class || rawType == Boolean.class) {
            return typeSchema("boolean");
        }
        if (rawType.isEnum()) {
            Map<String, Object> schema = typeSchema("string");
            List<String> values = new ArrayList<String>();
            Object[] constants = rawType.getEnumConstants();
            if (constants != null) {
                for (Object constant : constants) {
                    values.add(((Enum<?>) constant).name().toLowerCase(Locale.ROOT));
                }
            }
            schema.put("enum", values);
            return schema;
        }
        if (rawType.isArray()) {
            Map<String, Object> schema = typeSchema("array");
            schema.put("items", schemaFor(rawType.getComponentType(), visiting));
            return schema;
        }
        if (java.util.Collection.class.isAssignableFrom(rawType)) {
            Map<String, Object> schema = typeSchema("array");
            Type elementType = typeArgument(type, 0);
            if (elementType != null) {
                schema.put("items", schemaFor(elementType, visiting));
            }
            return schema;
        }
        if (Map.class.isAssignableFrom(rawType)) {
            Map<String, Object> schema = typeSchema("object");
            schema.put("additionalProperties", Boolean.TRUE);
            return schema;
        }
        if (!visiting.add(rawType)) {
            return typeSchema("object");
        }
        try {
            Map<String, Object> properties = new LinkedHashMap<String, Object>();
            List<String> required = new ArrayList<String>();
            for (Field field : rawType.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers)
                        || Modifier.isTransient(modifiers)
                        || Modifier.isFinal(modifiers)
                        || field.isSynthetic()) {
                    continue;
                }
                Map<String, Object> definition = schemaFor(field.getGenericType(), visiting);
                P fieldAnnotation = field.getAnnotation(P.class);
                if (fieldAnnotation != null) {
                    String description =
                            !isNullOrBlank(fieldAnnotation.description())
                                    ? fieldAnnotation.description()
                                    : (!isNullOrBlank(fieldAnnotation.value())
                                            ? fieldAnnotation.value()
                                            : null);
                    if (description != null) {
                        definition.put("description", description);
                    }
                }
                properties.put(field.getName(), definition);
                required.add(field.getName());
            }
            Map<String, Object> schema = typeSchema("object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            return schema;
        } finally {
            visiting.remove(rawType);
        }
    }

    private static Map<String, Object> typeSchema(String type) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", type);
        return schema;
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (index < arguments.length) {
                return arguments[index];
            }
        }
        return null;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            if (raw instanceof Class) {
                return (Class<?>) raw;
            }
        }
        return null;
    }

    private static String joinAndNullIfBlank(String[] values) {
        if (values == null || values.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(value);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static boolean isNullOrBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static List<Method> allConcreteMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<Method>();
        List<Method> result = new ArrayList<Method>();
        collectConcreteMethods(clazz, methods);
        Map<String, Method> bySignature = new LinkedHashMap<String, Method>();
        for (Method method : methods) {
            if (method.isAnnotationPresent(Tool.class)) {
                bySignature.put(methodSignature(method), method);
            }
        }
        result.addAll(bySignature.values());
        return result;
    }

    private static void collectConcreteMethods(Class<?> clazz, List<Method> methods) {
        if (clazz == null || clazz == Object.class) {
            return;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                methods.add(method);
            }
        }
        collectConcreteMethods(clazz.getSuperclass(), methods);
    }

    private static String methodSignature(Method method) {
        StringBuilder sb = new StringBuilder(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(types[i].getName());
        }
        return sb.append(')').toString();
    }
}
