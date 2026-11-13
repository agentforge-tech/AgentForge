package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * MCP 插件返回对象，内联其下 Server 列表，避免前端二次查询。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpPluginVo {

    private Long id;
    private String name;
    private String description;
    private String icon;
    private Integer status;
    private Long createUserId;
    private Date createTime;
    private Date modifyTime;
    private List<McpServerVo> servers = Collections.emptyList();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(Long createUserId) {
        this.createUserId = createUserId;
    }

    public Date getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Date createTime) {
        this.createTime = createTime;
    }

    public Date getModifyTime() {
        return modifyTime;
    }

    public void setModifyTime(Date modifyTime) {
        this.modifyTime = modifyTime;
    }

    public List<McpServerVo> getServers() {
        return servers;
    }

    public void setServers(List<McpServerVo> servers) {
        this.servers = servers == null ? Collections.<McpServerVo>emptyList() : servers;
    }
}
