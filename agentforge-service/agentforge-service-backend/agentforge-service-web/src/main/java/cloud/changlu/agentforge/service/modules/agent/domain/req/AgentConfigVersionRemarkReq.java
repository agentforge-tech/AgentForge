package cloud.changlu.agentforge.service.modules.agent.domain.req;

/** Agent 历史版本备注更新请求。 */
public class AgentConfigVersionRemarkReq {

    /** 版本备注，最多 1024 个字符；传空表示清空备注。 */
    private String versionRemark;

    public String getVersionRemark() {
        return versionRemark;
    }

    public void setVersionRemark(String versionRemark) {
        this.versionRemark = versionRemark;
    }
}
