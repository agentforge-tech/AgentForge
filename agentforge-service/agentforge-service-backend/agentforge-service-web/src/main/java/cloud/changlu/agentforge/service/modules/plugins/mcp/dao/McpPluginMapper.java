package cloud.changlu.agentforge.service.modules.plugins.mcp.dao;

import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MCP 插件 Mapper，对应 {@code ai_plugin_mcp} 表。
 *
 * @author changlu
 * @since 2026-10-27
 */
@Mapper
public interface McpPluginMapper {

    McpPluginPojo selectById(@Param("id") Long id);

    List<McpPluginPojo> selectList(
            @Param("keyword") String keyword,
            @Param("createUserId") Long createUserId,
            @Param("onlyEnabled") boolean onlyEnabled);

    long countByName(
            @Param("name") String name,
            @Param("createUserId") Long createUserId,
            @Param("excludeId") Long excludeId);

    int insert(McpPluginPojo pojo);

    int update(McpPluginPojo pojo);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    int updateOwnerStatus(
            @Param("id") Long id,
            @Param("createUserId") Long createUserId,
            @Param("status") Integer status);

    int deleteById(@Param("id") Long id);

    int deleteByOwner(@Param("id") Long id, @Param("createUserId") Long createUserId);
}
