package cloud.changlu.agentforge.service.modules.agent.domain.vo;

/** 已发布 Agent 的公开基础信息，不包含运行配置。 */
public class PublishedAgentBasicVo {

    private String aid;
    private String name;
    private String avatar;
    private String description;

    public String getAid() {
        return aid;
    }

    public void setAid(String aid) {
        this.aid = aid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
