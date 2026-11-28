package cloud.changlu.agentforge.service.modules.agent.interrupt.store.mysql;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

@Mapper
public interface AgentInterruptSnapshotMapper {
    int insert(AgentInterruptSnapshotPojo pojo);

    AgentInterruptSnapshotPojo selectByBatchId(@Param("batchId") String batchId);

    AgentInterruptSnapshotPojo selectPendingByMemoryId(@Param("memoryId") String memoryId);

    int claim(@Param("batchId") String batchId, @Param("now") Date now);

    int complete(@Param("batchId") String batchId, @Param("now") Date now);

    int cancel(@Param("batchId") String batchId, @Param("now") Date now);
}
