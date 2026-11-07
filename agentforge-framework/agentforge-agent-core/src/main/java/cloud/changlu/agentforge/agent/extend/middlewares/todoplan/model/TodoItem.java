package cloud.changlu.agentforge.agent.extend.middlewares.todoplan.model;

import cloud.changlu.agentforge.model.tool.P;

import java.util.Objects;

/** 单条任务计划。 */
public class TodoItem {

    /** 任务顺序号，从 1 开始，在同一份计划内唯一。 */
    @P("任务序号，从 1 开始，在同一份计划内唯一")
    private Integer seq;

    @P("任务内容，简短、可执行，最长 300 字符")
    private String content;

    @P("状态：1=待处理，2=进行中，3=已完成，4=已取消")
    private Integer status;

    @P("优先级：1=高，2=中，3=低")
    private Integer priority;

    public TodoItem() {}

    public TodoItem(Integer seq, String content, Integer status, Integer priority) {
        this.seq = seq;
        this.content = content;
        this.status = status;
        this.priority = priority;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TodoItem)) {
            return false;
        }
        TodoItem item = (TodoItem) other;
        return Objects.equals(seq, item.seq)
                && Objects.equals(content, item.content)
                && status == item.status
                && priority == item.priority;
    }

    @Override
    public int hashCode() {
        return Objects.hash(seq, content, status, priority);
    }
}
