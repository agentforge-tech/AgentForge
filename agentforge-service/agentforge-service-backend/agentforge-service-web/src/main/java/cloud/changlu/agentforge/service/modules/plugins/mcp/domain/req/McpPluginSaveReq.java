package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.req;

/**
 * MCP 插件新增/修改请求，{@code id} 为空时新增，否则更新。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpPluginSaveReq {

    private Long id;
    private String name;
    private String description;
    private String icon;
    private Integer status;

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
}
