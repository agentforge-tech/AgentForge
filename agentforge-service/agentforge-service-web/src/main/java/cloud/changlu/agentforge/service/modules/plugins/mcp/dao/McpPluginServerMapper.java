package cloud.changlu.agentforge.service.modules.plugins.mcp.dao;

import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.pojo.McpPluginServerPojo;
import cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req.McpServerPageQueryReq;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MCP Server Mapper，对应 {@code ai_plugin_mcp_server} 表。
 *
 * @author changlu
 * @since 2026-10-27
 */
@Mapper
public interface McpPluginServerMapper {

    McpPluginServerPojo selectById(@Param("id") Long id);

    List<McpPluginServerPojo> selectByPluginId(
            @Param("pluginId") Long pluginId, @Param("onlyEnabled") boolean onlyEnabled);

    List<McpPluginServerPojo> selectByPluginIds(
            @Param("pluginIds") List<Long> pluginIds, @Param("onlyEnabled") boolean onlyEnabled);

    List<McpPluginServerPojo> selectByIds(@Param("ids") List<Long> ids);

    List<McpPluginServerPojo> selectPage(
            @Param("query") McpServerPageQueryReq query, @Param("pluginIds") List<Long> pluginIds);

    long selectCount(
            @Param("query") McpServerPageQueryReq query, @Param("pluginIds") List<Long> pluginIds);

    long countByServerName(
            @Param("pluginId") Long pluginId,
            @Param("serverName") String serverName,
            @Param("excludeId") Long excludeId);

    int insert(McpPluginServerPojo pojo);

    int update(McpPluginServerPojo pojo);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /** 逻辑删除。表上无唯一键，重新添加同名 Server 不受影响。 */
    int deleteById(@Param("id") Long id);

    /** 插件删除时级联逻辑删除其下 Server。 */
    int deleteByPluginId(@Param("pluginId") Long pluginId);
}
