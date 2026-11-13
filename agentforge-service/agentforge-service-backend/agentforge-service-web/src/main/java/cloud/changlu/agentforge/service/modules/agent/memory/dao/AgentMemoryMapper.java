package cloud.changlu.agentforge.service.modules.agent.memory.dao;

import cloud.changlu.agentforge.service.modules.agent.memory.domain.AgentMemoryPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Agent 记忆 Mapper，对应 {@code ai_agent_memrory} 表。
 *
 * @author changlu
 * @since 2026-10-25
 */
@Mapper
public interface AgentMemoryMapper {

    AgentMemoryPojo selectByMemoryId(@Param("memoryId") String memoryId);

    int insert(@Param("memoryId") String memoryId, @Param("content") String content);

    int updateByMemoryId(@Param("memoryId") String memoryId, @Param("content") String content);

    int deleteByMemoryId(@Param("memoryId") String memoryId);
}
