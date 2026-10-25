package cloud.changlu.agentforge.service.protocol.enums;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Service 流式事件枚举。 */
public final class StreamEventEnums {

    /** 事件枚举，同时声明当前事件允许的类型。 */
    public enum EventEnum {
        SESSION("session", "会话事件", TypeEnum.CREATED),
        THINK("think", "AI思考", TypeEnum.TEXT),
        RESP("resp", "AI回答事件", TypeEnum.TEXT),
        TOOL_CALL("tool_call", "工具调用事件", TypeEnum.TOOL_CARD),
        RESP_END("resp_end", "AI回答结束"),
        ERROR("error", "异常事件", TypeEnum.TEXT);

        private final String code;
        private final String msg;
        private final Set<TypeEnum> types;

        EventEnum(String code, String msg, TypeEnum... types) {
            this.code = code;
            this.msg = msg;
            this.types =
                    types.length == 0
                            ? Collections.emptySet()
                            : Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(types)));
        }

        public String getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }

        public Set<TypeEnum> getTypes() {
            return types;
        }

        public boolean supports(TypeEnum type) {
            return types.contains(type);
        }
    }

    /** 事件里的类型枚举。 */
    public enum TypeEnum {
        CREATED("created", "会话创建"),
        TEXT("text", "文本内容"),
        TOOL_CARD("tool_card", "工具调用卡片");

        private final String code;
        private final String msg;

        TypeEnum(String code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        public String getCode() {
            return code;
        }

        public String getMsg() {
            return msg;
        }
    }

    private StreamEventEnums() {}
}
