package cloud.changlu.agentforge.service.modules.trace.service;

import cloud.changlu.agentforge.agent.extend.middlewares.trace.AgentTraceCollector;
import cloud.changlu.agentforge.agent.extend.middlewares.trace.domain.AgentTraceSnapshot;
import cloud.changlu.agentforge.service.modules.session.dao.AgentSessionMapper;
import cloud.changlu.agentforge.service.modules.session.domain.AgentSessionPojo;
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
    private final AgentSessionMapper sessionMapper;

    public MysqlAgentTraceCollector(
            AgentTraceSnapshotMapper mapper,
            ObjectMapper objectMapper,
            AgentSessionMapper sessionMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.sessionMapper = sessionMapper;
    }

    @Override
    public void onSnapshot(AgentTraceSnapshot snapshot) {
        if (snapshot == null || snapshot.getMemoryId() == null) return;
        String aid = resolveAid(snapshot);
        if (aid == null || aid.trim().isEmpty()) {
            LOG.warn(
                    "Skip Agent trace without aid, sessionId: {}, step: {}",
                    snapshot.getMemoryId(),
                    snapshot.getStep());
            return;
        }
        try {
            int round = resolveQuestionRound(snapshot);
            snapshot.setQuestionRound(round);
            AgentTraceSnapshotPojo pojo = new AgentTraceSnapshotPojo();
            pojo.setSessionId(snapshot.getMemoryId());
            pojo.setAid(aid);
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

    /** aid 优先取快照；中断恢复丢失扩展时按 sessionId 回查会话绑定的 Agent。 */
    private String resolveAid(AgentTraceSnapshot snapshot) {
        String aid =
                snapshot instanceof AgentForgeTraceSnapshot
                        ? ((AgentForgeTraceSnapshot) snapshot).getAid()
                        : attribute(snapshot, AgentTraceAttributes.AID);
        if (aid != null && !aid.trim().isEmpty()) return aid;
        AgentSessionPojo session = sessionMapper.selectBySessionId(snapshot.getMemoryId());
        return session == null ? null : session.getAid();
    }

    private int resolveDebug(AgentTraceSnapshot snapshot) {
        Boolean debug = null;
        if (snapshot instanceof AgentForgeTraceSnapshot) {
            debug = ((AgentForgeTraceSnapshot) snapshot).getDebug();
        } else {
            Object value = snapshot.getAttributes().get(AgentTraceAttributes.DEBUG);
            if (value != null) debug = Boolean.parseBoolean(String.valueOf(value));
        }
        if (debug == null) {
            AgentSessionPojo session = sessionMapper.selectBySessionId(snapshot.getMemoryId());
            if (session != null && session.getIsDebug() != null) {
                debug = session.getIsDebug() != 0;
            }
        }
        return Boolean.TRUE.equals(debug) ? 1 : 0;
    }
}
