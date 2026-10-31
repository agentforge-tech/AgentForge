package cloud.changlu.agentforge.service.modules.trace.service;

import cloud.changlu.agentforge.agent.trace.AgentTraceCollector;
import cloud.changlu.agentforge.agent.trace.domain.AgentTraceSnapshot;
import cloud.changlu.agentforge.service.modules.trace.AgentTraceAttributes;
import cloud.changlu.agentforge.service.modules.trace.dao.AgentTraceSnapshotMapper;
import cloud.changlu.agentforge.service.modules.trace.domain.AgentForgeTraceSnapshot;
import cloud.changlu.agentforge.service.modules.trace.domain.AgentTraceSnapshotPojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;

/** MySQL trace collector; persistence is intentionally best effort. */
@Component
public class MysqlAgentTraceCollector implements AgentTraceCollector {

    private static final Logger LOG = LoggerFactory.getLogger(MysqlAgentTraceCollector.class);
    private final AgentTraceSnapshotMapper mapper;
    private final ObjectMapper objectMapper;

    public MysqlAgentTraceCollector(AgentTraceSnapshotMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onSnapshot(AgentTraceSnapshot snapshot) {
        if (snapshot == null || snapshot.getMemoryId() == null) return;
        try {
            int round = resolveQuestionRound(snapshot);
            snapshot.setQuestionRound(round);
            AgentTraceSnapshotPojo pojo = new AgentTraceSnapshotPojo();
            pojo.setSessionId(snapshot.getMemoryId());
            pojo.setAid(resolveAid(snapshot));
            pojo.setQuestionRound(round);
            pojo.setStep(snapshot.getStep());
            pojo.setStartedAt(toDate(snapshot.getStartedAt()));
            pojo.setFinishedAt(toDate(snapshot.getFinishedAt()));
            pojo.setDurationMs(snapshot.getDurationMs());
            pojo.setInputTokens(snapshot.getInputTokens());
            pojo.setOutputTokens(snapshot.getOutputTokens());
            pojo.setCachedTokens(snapshot.getCachedTokens());
            pojo.setTotalTokens(snapshot.getTotalTokens());
            pojo.setFinished(snapshot.isFinished() ? 1 : 0);
            pojo.setStatus(snapshot.getStatus().name());
            pojo.setDebug(resolveDebug(snapshot));
            pojo.setSnapshotJson(objectMapper.writeValueAsString(snapshot));
            if (mapper.updateSnapshot(pojo) == 0) {
                mapper.insert(pojo);
            }
        } catch (Exception e) {
            LOG.error(
                    "Save Agent trace failed, sessionId: {}, step: {}",
                    snapshot.getMemoryId(),
                    snapshot.getStep(),
                    e);
        }
    }

    private int resolveQuestionRound(AgentTraceSnapshot snapshot) {
        if (snapshot.getQuestionRound() > 0) return snapshot.getQuestionRound();
        Integer count = mapper.countFinishedBySessionId(snapshot.getMemoryId());
        return (count == null ? 0 : count) + 1;
    }

    private Date toDate(long timestamp) {
        return timestamp <= 0 ? null : new Date(timestamp);
    }

    private String attribute(AgentTraceSnapshot snapshot, String name) {
        Object value = snapshot.getAttributes().get(name);
        return value == null ? "" : String.valueOf(value);
    }

    private String resolveAid(AgentTraceSnapshot snapshot) {
        if (snapshot instanceof AgentForgeTraceSnapshot) {
            return ((AgentForgeTraceSnapshot) snapshot).getAid();
        }
        return attribute(snapshot, AgentTraceAttributes.AID);
    }

    private int resolveDebug(AgentTraceSnapshot snapshot) {
        if (snapshot instanceof AgentForgeTraceSnapshot) {
            Boolean debug = ((AgentForgeTraceSnapshot) snapshot).getDebug();
            return Boolean.TRUE.equals(debug) ? 1 : 0;
        }
        return Boolean.parseBoolean(attribute(snapshot, AgentTraceAttributes.DEBUG)) ? 1 : 0;
    }
}
