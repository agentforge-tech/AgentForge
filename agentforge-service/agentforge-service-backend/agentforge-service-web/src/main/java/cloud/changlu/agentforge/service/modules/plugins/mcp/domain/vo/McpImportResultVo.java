package cloud.changlu.agentforge.service.modules.plugins.mcp.domain.vo;

import java.util.Collections;
import java.util.List;

/**
 * 一键导入结果。
 *
 * @author changlu
 * @since 2026-10-27
 */
public class McpImportResultVo {

    private Long pluginId;
    private int imported;
    private int skipped;
    private List<String> errors = Collections.emptyList();

    public Long getPluginId() {
        return pluginId;
    }

    public void setPluginId(Long pluginId) {
        this.pluginId = pluginId;
    }

    public int getImported() {
        return imported;
    }

    public void setImported(int imported) {
        this.imported = imported;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors == null ? Collections.<String>emptyList() : errors;
    }
}
