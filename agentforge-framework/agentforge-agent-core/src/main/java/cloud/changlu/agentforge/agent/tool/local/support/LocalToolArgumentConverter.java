package cloud.changlu.agentforge.agent.tool.local.support;

import cloud.changlu.agentforge.model.internal.json.Json;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @description Local Tool复杂参数转换器（AgentForge版）。
 *     <p>兼容模型把Array/Object参数按标准JSON值传入，也兼容把整个JSON值再次编码为String的情况。 支持把 JSON 数组元素绑定到泛型集合中的 POJO 元素。
 * @author changlu
 * @date 2026/10/04
 */
public final class LocalToolArgumentConverter {

    private static final Object NO_MATCH = new Object();

    private LocalToolArgumentConverter() {}

    @SuppressWarnings("unchecked")
    public static <T> T convert(Object argument, Type targetType) {
        Object value = argument;
        if (value instanceof String) {
            Class<?> rawType = rawClass(targetType);
            String json = (String) value;
            Object parsed = parseIfMatches(json, rawType);
            if (parsed == NO_MATCH) {
                parsed = parseIfMatches(cleanJsonString(json), rawType);
            }
            if (parsed == NO_MATCH) {
                throw new IllegalArgumentException(
                        "Cannot convert argument to " + targetType + ": " + json);
            }
            value = parsed;
        }
        return (T) coerce(value, targetType);
    }

    private static Object coerce(Object value, Type targetType) {
        if (value == null) {
            return null;
        }
        Class<?> rawType = rawClass(targetType);
        if (rawType == null || rawType == Object.class) {
            return value;
        }
        if (rawType == String.class || rawType == CharSequence.class) {
            return String.valueOf(value);
        }
        if (rawType == boolean.class || rawType == Boolean.class) {
            return value instanceof Boolean ? value : Boolean.valueOf(String.valueOf(value));
        }
        if (rawType == int.class || rawType == Integer.class) {
            return value instanceof Number
                    ? ((Number) value).intValue()
                    : Integer.valueOf(String.valueOf(value));
        }
        if (rawType == long.class || rawType == Long.class) {
            return value instanceof Number
                    ? ((Number) value).longValue()
                    : Long.valueOf(String.valueOf(value));
        }
        if (rawType == short.class || rawType == Short.class) {
            return value instanceof Number
                    ? ((Number) value).shortValue()
                    : Short.valueOf(String.valueOf(value));
        }
        if (rawType == byte.class || rawType == Byte.class) {
            return value instanceof Number
                    ? ((Number) value).byteValue()
                    : Byte.valueOf(String.valueOf(value));
        }
        if (rawType == double.class || rawType == Double.class) {
            return value instanceof Number
                    ? ((Number) value).doubleValue()
                    : Double.valueOf(String.valueOf(value));
        }
        if (rawType == float.class || rawType == Float.class) {
            return value instanceof Number
                    ? ((Number) value).floatValue()
                    : Float.valueOf(String.valueOf(value));
        }
        if (rawType == BigDecimal.class) {
            return value instanceof Number
                    ? BigDecimal.valueOf(((Number) value).doubleValue())
                    : new BigDecimal(String.valueOf(value));
        }
        if (rawType == BigInteger.class) {
            return value instanceof Number
                    ? BigInteger.valueOf(((Number) value).longValue())
                    : new BigInteger(String.valueOf(value));
        }
        if (rawType == char.class || rawType == Character.class) {
            String text = String.valueOf(value);
            return text.isEmpty() ? null : text.charAt(0);
        }
        if (rawType.isEnum()) {
            return enumValue(rawType, value);
        }
        if (Collection.class.isAssignableFrom(rawType)) {
            if (!(value instanceof Collection)) {
                return value;
            }
            Type elementType = typeArgument(targetType, 0);
            Collection<?> source = (Collection<?>) value;
            List<Object> result = new ArrayList<Object>(source.size());
            for (Object item : source) {
                result.add(elementType == null ? item : coerce(item, elementType));
            }
            return result;
        }
        if (Map.class.isAssignableFrom(rawType)) {
            return value;
        }
        if (value instanceof Map) {
            return bindObject((Map<?, ?>) value, rawType);
        }
        return value;
    }

    private static Object enumValue(Class<?> rawType, Object value) {
        String text = String.valueOf(value);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<? extends Enum> enumType = (Class<? extends Enum>) rawType;
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object exact = enumValueOrNull(enumType, text);
        if (exact != null) {
            return exact;
        }
        return Enum.valueOf(enumType, text.toUpperCase(Locale.ROOT));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValueOrNull(Class<? extends Enum> enumType, String text) {
        try {
            return Enum.valueOf(enumType, text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Object bindObject(Map<?, ?> source, Class<?> targetType) {
        try {
            Object instance = targetType.getDeclaredConstructor().newInstance();
            for (Field field : targetType.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers)
                        || Modifier.isTransient(modifiers)
                        || Modifier.isFinal(modifiers)
                        || field.isSynthetic()) {
                    continue;
                }
                if (!source.containsKey(field.getName())) {
                    continue;
                }
                Object value = coerce(source.get(field.getName()), field.getGenericType());
                field.setAccessible(true);
                field.set(instance, value);
            }
            return instance;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot bind argument to " + targetType.getName() + ": " + e.getMessage(), e);
        }
    }

    private static Object parseIfMatches(String json, Class<?> rawType) {
        if (json == null || json.isEmpty()) {
            return NO_MATCH;
        }
        try {
            Object parsed = Json.parse(json);
            if (rawType == null || rawType.isInstance(parsed)) {
                return parsed;
            }
            return NO_MATCH;
        } catch (Exception e) {
            return NO_MATCH;
        }
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

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (index < arguments.length) {
                return arguments[index];
            }
        }
        return null;
    }

    private static String cleanJsonString(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        String cleaned = json.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).replace("\\\"", "\"");
        }
        return cleaned;
    }
}
