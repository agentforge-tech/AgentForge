package cloud.changlu.agentforge.service.modules.agent.event.store.mysql;

import cloud.changlu.agentforge.service.modules.agent.event.store.StoredMessageEvent;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageEventMapper {
    int insert(StoredMessageEvent event);

    List<StoredMessageEvent> selectAfter(
            @Param("messageId") Long messageId,
            @Param("afterSeq") Long afterSeq,
            @Param("limit") Integer limit);

    List<StoredMessageEvent> selectAll(@Param("messageId") Long messageId);

    Long selectMaxSeq(@Param("messageId") Long messageId);

    int deleteByMessageId(@Param("messageId") Long messageId);
}
