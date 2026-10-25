package cloud.changlu.agentforge.service.protocol;

import cloud.changlu.agentforge.service.protocol.enums.StreamEventEnums;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

/**
 * Service unified SSE event. eventId and deliveryId are intentionally omitted for the first phase.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamEventResponse {

    private String event;
    private String type;
    private String title;
    private Object content;
    private Boolean isFinish;

    public StreamEventResponse() {}

    private StreamEventResponse(String event) {
        this.event = event;
    }

    public static StreamEventResponse of(StreamEventEnums.EventEnum event) {
        Objects.requireNonNull(event, "event must not be null");
        if (!event.getTypes().isEmpty()) {
            throw new IllegalArgumentException("Event " + event.getCode() + " requires a type");
        }
        return new StreamEventResponse(event.getCode());
    }

    public static StreamEventResponse of(
            StreamEventEnums.EventEnum event, StreamEventEnums.TypeEnum type) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (!event.supports(type)) {
            throw new IllegalArgumentException(
                    "Event " + event.getCode() + " does not support type " + type.getCode());
        }
        return new StreamEventResponse(event.getCode()).type(type.getCode());
    }

    public StreamEventResponse type(String type) {
        this.type = type;
        return this;
    }

    public StreamEventResponse title(String title) {
        this.title = title;
        return this;
    }

    public StreamEventResponse content(Object content) {
        this.content = content;
        return this;
    }

    public StreamEventResponse finish(Boolean finish) {
        isFinish = finish;
        return this;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Object getContent() {
        return content;
    }

    public void setContent(Object content) {
        this.content = content;
    }

    public Boolean getIsFinish() {
        return isFinish;
    }

    public void setIsFinish(Boolean finish) {
        isFinish = finish;
    }
}
