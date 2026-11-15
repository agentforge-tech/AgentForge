package cloud.changlu.agentforge.agent.governance.tool.askuser;

import cloud.changlu.agentforge.model.chat.message.ToolExecutionRequest;
import cloud.changlu.agentforge.model.internal.json.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ask_user 的协议实现：参数解析、问题快照校验、回答归一化/校验以及稳定 Tool Result 渲染。
 *
 * <p>一次调用最多十道题，支持 single_choice、multiple_choice、text，选择题允许 customValue 作为“其他”答案。
 */
public final class AskUserSupport {

    private static final int MAX_QUESTIONS = 10;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 20;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int DEFAULT_MAX_TEXT_LENGTH = 4000;
    private static final Pattern READABLE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private AskUserSupport() {}

    /** 将模型发起的 ask_user Tool Call 解析并校验为问题快照。 */
    public static AskUserRequest parse(ToolExecutionRequest toolRequest) {
        Map<String, Object> arguments = arguments(toolRequest);
        if (!arguments.containsKey("questions")) {
            arguments = legacyArguments(arguments);
        }
        List<Object> rawQuestions = arrayValue(arguments.get("questions"));
        if (rawQuestions == null || rawQuestions.isEmpty()) {
            throw new IllegalArgumentException("ask_user.questions must contain one to ten items");
        }
        if (rawQuestions.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("ask_user.questions must not exceed ten items");
        }
        List<AskQuestion> questions = new ArrayList<AskQuestion>();
        for (int i = 0; i < rawQuestions.size(); i++) {
            questions.add(question(rawQuestions.get(i), i));
        }
        validateQuestions(questions);
        return new AskUserRequest(
                toolRequest.id(),
                toolRequest.name(),
                questions,
                Json.string(arguments.get("reason")),
                Json.string(arguments.get("submitLabel")));
    }

    /** 按问题快照校验回答；任一问题不合法时抛出 {@link IllegalArgumentException}。 */
    public static void validate(AskUserRequest request, AskUserResponse response) {
        if (request == null) return;
        Map<String, AskQuestion> questions = new HashMap<String, AskQuestion>();
        for (AskQuestion question : request.getQuestions()) {
            questions.put(question.getId(), question);
        }
        Map<String, AskUserAnswer> answers = new HashMap<String, AskUserAnswer>();
        if (response != null && response.getAnswers() != null) {
            for (AskUserAnswer answer : response.getAnswers()) {
                if (answer == null || blank(answer.getQuestionId())) {
                    throw new IllegalArgumentException("ask_user answer questionId is required");
                }
                if (!questions.containsKey(answer.getQuestionId())) {
                    throw new IllegalArgumentException(
                            "unknown ask_user questionId: " + answer.getQuestionId());
                }
                if (answers.put(answer.getQuestionId(), answer) != null) {
                    throw new IllegalArgumentException(
                            "duplicate ask_user answer: " + answer.getQuestionId());
                }
            }
        }
        for (AskQuestion question : request.getQuestions()) {
            AskUserAnswer answer = answers.get(question.getId());
            if (empty(answer)) {
                if (question.isRequired()) {
                    throw new IllegalArgumentException(
                            "required ask_user question is unanswered: " + question.getId());
                }
                continue;
            }
            validateAnswer(question, answer);
        }
    }

    /** 渲染模型下一轮可见的稳定工具结果，answers 顺序与问题顺序一致。 */
    public static String renderToolResult(
            AskUserRequest request, AskUserResponse response, String interactionId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "answered");
        result.put("interactionId", interactionId);
        List<Map<String, Object>> answers = new ArrayList<Map<String, Object>>();
        if (request != null) {
            Map<String, AskUserAnswer> indexed = indexAnswers(response);
            for (AskQuestion question : request.getQuestions()) {
                AskUserAnswer answer = indexed.get(question.getId());
                if (answer == null) continue;
                answers.add(renderAnswer(question.getId(), answer));
            }
        } else if (response != null) {
            for (AskUserAnswer answer : response.getAnswers()) {
                answers.add(renderAnswer(answer.getQuestionId(), answer));
            }
        }
        result.put("answers", answers);
        return Json.stringify(result);
    }

    private static Map<String, AskUserAnswer> indexAnswers(AskUserResponse response) {
        Map<String, AskUserAnswer> indexed = new HashMap<String, AskUserAnswer>();
        if (response != null && response.getAnswers() != null) {
            for (AskUserAnswer answer : response.getAnswers()) {
                indexed.put(answer.getQuestionId(), answer);
            }
        }
        return indexed;
    }

    private static Map<String, Object> renderAnswer(String questionId, AskUserAnswer answer) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("questionId", questionId);
        item.put(
                "selectedOptionIds",
                answer.getSelectedOptionIds() == null
                        ? Collections.emptyList()
                        : answer.getSelectedOptionIds());
        item.put("customValue", answer.getCustomValue());
        return item;
    }

    private static void validateAnswer(AskQuestion question, AskUserAnswer answer) {
        List<String> selected =
                answer.getSelectedOptionIds() == null
                        ? Collections.<String>emptyList()
                        : answer.getSelectedOptionIds();
        String custom = blank(answer.getCustomValue()) ? null : answer.getCustomValue();
        if (question.getType() == AskUserAnswerType.TEXT) {
            if (!selected.isEmpty()) {
                throw new IllegalArgumentException(
                        "text ask_user question cannot contain options: " + question.getId());
            }
            if (question.isRequired() && custom == null) {
                throw new IllegalArgumentException(
                        "required ask_user text answer is blank: " + question.getId());
            }
            int maxLength = maxLength(question);
            if (custom != null && custom.length() > maxLength) {
                throw new IllegalArgumentException(
                        "ask_user text answer exceeds maxLength "
                                + maxLength
                                + ": "
                                + question.getId());
            }
            return;
        }
        Set<String> unique = new HashSet<String>(selected);
        if (unique.size() != selected.size()) {
            throw new IllegalArgumentException("duplicate ask_user options: " + question.getId());
        }
        Set<String> allowed = new HashSet<String>();
        if (question.getOptions() != null) {
            for (AskUserOption option : question.getOptions()) {
                allowed.add(option.getId());
            }
        }
        if (!allowed.containsAll(selected)) {
            throw new IllegalArgumentException(
                    "unknown ask_user option id for question: " + question.getId());
        }
        int count = selected.size() + (custom == null ? 0 : 1);
        if (question.isRequired() && count == 0) {
            throw new IllegalArgumentException(
                    "required ask_user choice is unanswered: " + question.getId());
        }
        if (question.getType() == AskUserAnswerType.SINGLE_CHOICE) {
            if (count > 1) {
                throw new IllegalArgumentException(
                        "single_choice accepts at most one answer: " + question.getId());
            }
            return;
        }
        int min = question.getMinSelections() == null ? 0 : question.getMinSelections().intValue();
        int max =
                question.getMaxSelections() == null
                        ? MAX_OPTIONS
                        : question.getMaxSelections().intValue();
        if (count < min || count > max) {
            throw new IllegalArgumentException(
                    "multiple_choice requires "
                            + min
                            + ".."
                            + max
                            + " answers, but got "
                            + count
                            + ": "
                            + question.getId());
        }
    }

    private static void validateQuestions(List<AskQuestion> questions) {
        Set<String> ids = new HashSet<String>();
        for (AskQuestion question : questions) {
            if (question == null || !READABLE_ID.matcher(question.getId()).matches()) {
                throw new IllegalArgumentException("ask_user question id is invalid");
            }
            if (!ids.add(question.getId())) {
                throw new IllegalArgumentException(
                        "duplicate ask_user question id: " + question.getId());
            }
            if (blank(question.getTitle()) || question.getTitle().length() > MAX_TITLE_LENGTH) {
                throw new IllegalArgumentException(
                        "ask_user question title must be 1..200 characters: " + question.getId());
            }
            if (question.getType() == null) {
                throw new IllegalArgumentException(
                        "ask_user question type is required: " + question.getId());
            }
            if (question.getType().isChoice()) {
                validateOptions(question);
            }
        }
    }

    private static void validateOptions(AskQuestion question) {
        List<AskUserOption> options = question.getOptions();
        if (options == null || options.size() < MIN_OPTIONS || options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException(
                    "choice question requires 2..20 options: " + question.getId());
        }
        Set<String> ids = new HashSet<String>();
        for (AskUserOption option : options) {
            if (option == null || !READABLE_ID.matcher(option.getId()).matches()) {
                throw new IllegalArgumentException(
                        "ask_user option id is invalid: " + question.getId());
            }
            if (blank(option.getLabel())) {
                throw new IllegalArgumentException(
                        "ask_user option label is blank: " + question.getId());
            }
            if (!ids.add(option.getId())) {
                throw new IllegalArgumentException(
                        "duplicate ask_user option id: " + option.getId());
            }
        }
    }

    private static int maxLength(AskQuestion question) {
        return question.getMaxLength() == null
                ? DEFAULT_MAX_TEXT_LENGTH
                : Math.min(question.getMaxLength().intValue(), DEFAULT_MAX_TEXT_LENGTH);
    }

    private static AskQuestion question(Object raw, int index) {
        Map<String, Object> map = Json.object(raw);
        if (map == null) {
            throw new IllegalArgumentException("ask_user question must be an object");
        }
        String id = firstNonBlank(map.get("id"), "q" + (index + 1));
        String title =
                firstNonBlank(
                        map.get("title"),
                        map.get("question"),
                        map.get("prompt"),
                        map.get("header"));
        List<AskUserOption> options = options(map.get("options"));
        AskUserAnswerType type =
                map.get("type") == null
                        ? (options.isEmpty()
                                ? AskUserAnswerType.TEXT
                                : AskUserAnswerType.SINGLE_CHOICE)
                        : AskUserAnswerType.parse(String.valueOf(map.get("type")));
        return new AskQuestionBuilder()
                .id(id)
                .title(title)
                .description(Json.string(map.get("description")))
                .type(type)
                .required(Boolean.valueOf(booleanValue(map.get("required"), true)))
                .options(options)
                .minSelections(integerValue(map.get("minSelections")))
                .maxSelections(integerValue(map.get("maxSelections")))
                .maxLength(integerValue(map.get("maxLength")))
                .placeholder(Json.string(map.get("placeholder")))
                .build();
    }

    private static List<AskUserOption> options(Object raw) {
        List<Object> items = arrayValue(raw);
        if (items == null) return Collections.emptyList();
        List<AskUserOption> options = new ArrayList<AskUserOption>();
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item instanceof String) {
                String id = ((String) item).trim();
                options.add(new AskUserOption(id, id));
                continue;
            }
            Map<String, Object> map = Json.object(item);
            if (map == null) {
                throw new IllegalArgumentException("ask_user option must be an object or a string");
            }
            String id = firstNonBlank(map.get("id"), "option_" + (i + 1));
            String label =
                    firstNonBlank(
                            map.get("label"), map.get("title"), map.get("text"), map.get("value"));
            options.add(new AskUserOption(id, label, Json.string(map.get("description"))));
        }
        return options;
    }

    private static Map<String, Object> legacyArguments(Map<String, Object> legacy) {
        Map<String, Object> question = new LinkedHashMap<String, Object>();
        question.put("id", "q1");
        question.put("title", legacy.get("question"));
        question.put(
                "type",
                legacy.get("answerType") == null
                        ? (legacy.get("options") == null ? "text" : "single_choice")
                        : legacy.get("answerType"));
        question.put("required", legacy.get("required"));
        question.put("options", legacy.get("options"));
        question.put("minSelections", legacy.get("minSelections"));
        question.put("maxSelections", legacy.get("maxSelections"));
        Map<String, Object> batch = new LinkedHashMap<String, Object>();
        batch.put("questions", Collections.singletonList(question));
        batch.put("reason", legacy.get("reason"));
        batch.put("submitLabel", legacy.get("submitLabel"));
        return batch;
    }

    private static Map<String, Object> arguments(ToolExecutionRequest toolRequest) {
        String raw = toolRequest == null ? null : toolRequest.arguments();
        if (blank(raw) || "{}".equals(raw.trim())) {
            return Collections.emptyMap();
        }
        try {
            return Json.parseObject(raw);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid ask_user arguments: " + raw, e);
        }
    }

    private static List<Object> arrayValue(Object raw) {
        List<Object> items = Json.array(raw);
        if (items != null) return items;
        if (raw instanceof String) {
            String text = ((String) raw).trim();
            if (!text.isEmpty()) {
                try {
                    return Json.array(Json.parse(text));
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static Integer integerValue(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return Integer.valueOf(((Number) value).intValue());
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("ask_user numeric field must be an integer", e);
        }
    }

    private static boolean empty(AskUserAnswer answer) {
        return answer == null
                || ((answer.getSelectedOptionIds() == null
                                || answer.getSelectedOptionIds().isEmpty())
                        && blank(answer.getCustomValue()));
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 构造器集中处理 AskQuestion 的非 final 字段。 */
    private static final class AskQuestionBuilder {
        private final AskQuestion question = new AskQuestion();

        private AskQuestionBuilder id(String id) {
            question.id = id;
            return this;
        }

        private AskQuestionBuilder title(String title) {
            question.title = title;
            return this;
        }

        private AskQuestionBuilder description(String description) {
            question.description = description;
            return this;
        }

        private AskQuestionBuilder type(AskUserAnswerType type) {
            question.type = type;
            return this;
        }

        private AskQuestionBuilder required(Boolean required) {
            question.required = required;
            return this;
        }

        private AskQuestionBuilder options(List<AskUserOption> options) {
            question.options = options;
            return this;
        }

        private AskQuestionBuilder minSelections(Integer minSelections) {
            question.minSelections = minSelections;
            return this;
        }

        private AskQuestionBuilder maxSelections(Integer maxSelections) {
            question.maxSelections = maxSelections;
            return this;
        }

        private AskQuestionBuilder maxLength(Integer maxLength) {
            question.maxLength = maxLength;
            return this;
        }

        private AskQuestionBuilder placeholder(String placeholder) {
            question.placeholder = placeholder;
            return this;
        }

        private AskQuestion build() {
            return question;
        }
    }
}
