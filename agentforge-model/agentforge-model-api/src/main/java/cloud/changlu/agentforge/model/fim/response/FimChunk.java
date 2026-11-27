package cloud.changlu.agentforge.model.fim.response;

/**
 * 流式补全的增量片段。
 *
 * <p>与 Chat 协议不同，FIM 的增量就是一段裸文本，没有消息包装，因此单独用值对象表示， 便于后续在片段上附加候选置信度等信息而不破坏接口。
 *
 * @author changlu
 * @date 2026/11/25
 */
public final class FimChunk {

    private static final FimChunk EMPTY = new FimChunk("");

    private final String delta;

    public FimChunk(String delta) {
        this.delta = delta == null ? "" : delta;
    }

    public static FimChunk empty() {
        return EMPTY;
    }

    public String delta() {
        return delta;
    }

    public boolean isEmpty() {
        return delta.isEmpty();
    }
}
