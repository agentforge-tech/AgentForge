package cloud.changlu.agentforge.service.modules.model.domain.req;

/**
 * LLM 模型配置查询请求，所有字段均可为空，为空时该条件不参与过滤。
 *
 * @author changlu
 * @since 2026-10-14
 */
public class ModelConfigQueryReq {

    private Long id;
    private String name;
    private Integer provider;
    private Integer modelType;
    private Integer createType;
    private Long createUserId;
    private String keyword;
    private Integer page = 1;
    private Integer pageSize = 12;

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

    public Integer getProvider() {
        return provider;
    }

    public void setProvider(Integer provider) {
        this.provider = provider;
    }

    public Integer getModelType() {
        return modelType;
    }

    public void setModelType(Integer modelType) {
        this.modelType = modelType;
    }

    public Integer getCreateType() {
        return createType;
    }

    public void setCreateType(Integer createType) {
        this.createType = createType;
    }

    public Long getCreateUserId() {
        return createUserId;
    }

    public void setCreateUserId(Long createUserId) {
        this.createUserId = createUserId;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public int getStart() {
        int safePage = page == null || page < 1 ? 1 : page;
        int safePageSize = pageSize == null || pageSize < 1 ? 12 : Math.min(pageSize, 100);
        return (safePage - 1) * safePageSize;
    }
}
