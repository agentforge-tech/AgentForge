package cloud.changlu.agentforge.service.modules.trace.service;

import cloud.changlu.agentforge.service.common.exception.AgentForgeException;
import cloud.changlu.agentforge.service.modules.trace.dao.AgentTraceSnapshotMapper;
import cloud.changlu.agentforge.service.modules.trace.domain.AgentTraceSnapshotPojo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentTraceQueryService {

    private final AgentTraceSnapshotMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentTraceQueryService(AgentTraceSnapshotMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> getSessionTrace(String sessionId) {
        if (!StringUtils.hasText(sessionId)) throw new AgentForgeException("sessionId 不能为空");
        List<AgentTraceSnapshotPojo> rows = mapper.selectBySessionId(sessionId.trim());
        if (rows.isEmpty()) {
            throw new AgentForgeException("当前会话暂未生成可查看的链路追踪数据");
        }
        Map<Integer, List<Map<String, Object>>> grouped =
                new LinkedHashMap<Integer, List<Map<String, Object>>>();
        for (AgentTraceSnapshotPojo row : rows) {
            try {
                Map<String, Object> snapshot =
                        objectMapper.readValue(row.getSnapshotJson(), Map.class);
                snapshot.put("id", row.getId());
                snapshot.put("modifyTime", row.getModifyTime());
                grouped.computeIfAbsent(row.getQuestionRound(), key -> new ArrayList<>())
                        .add(snapshot);
            } catch (Exception ignored) {
                // Keep other snapshots visible when one legacy payload is malformed.
            }
        }
        List<Map<String, Object>> rounds = new ArrayList<Map<String, Object>>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : grouped.entrySet()) {
            List<Map<String, Object>> snapshots = entry.getValue();
            Map<String, Object> display = snapshots.get(snapshots.size() - 1);
            for (Map<String, Object> item : snapshots) {
                if ("ROUND_FINAL".equals(item.get("snapshotKind"))) display = item;
            }
            Map<String, Object> round = new LinkedHashMap<String, Object>();
            round.put("questionRound", entry.getKey());
            round.put("question", display.get("question"));
            round.put("latestStep", display.get("step"));
            round.put("stepCount", countSteps(snapshots));
            round.put("finished", display.get("finished"));
            round.put("status", display.get("status"));
            round.put("durationMs", display.get("durationMs"));
            round.put("modelDurationMs", display.get("modelDurationMs"));
            round.put("toolDurationMs", display.get("toolDurationMs"));
            round.put("inputTokens", display.get("inputTokens"));
            round.put("outputTokens", display.get("outputTokens"));
            round.put("totalTokens", display.get("totalTokens"));
            round.put("cachedTokens", display.get("cachedTokens"));
            round.put("snapshots", snapshots);
            rounds.add(round);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("sessionId", sessionId);
        result.put("aid", rows.get(0).getAid());
        result.put("roundCount", rounds.size());
        result.put("rounds", rounds);
        return result;
    }

    public List<AgentTraceSnapshotPojo> getRecentSessions(
            String aid, Integer debug, Integer limit) {
        int safeLimit = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        return mapper.selectRecentSessions(
                StringUtils.hasText(aid) ? aid.trim() : null, debug, safeLimit);
    }

    private int countSteps(List<Map<String, Object>> snapshots) {
        int count = 0;
        for (Map<String, Object> item : snapshots) {
            if ("STEP".equals(item.get("snapshotKind"))) count++;
        }
        return count;
    }
}
