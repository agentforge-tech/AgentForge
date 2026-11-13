package cloud.changlu.agentforge.service.common.pager;

/**
 * 通用分页查询结果。
 *
 * @param <T> 数据类型
 */
public class PageResult<T> {

    private final int currentPage;
    private final int pageSize;
    private final long totalCount;
    private final int totalPage;
    private final T data;

    public PageResult(int currentPage, int pageSize, long totalCount, T data) {
        this.currentPage = currentPage;
        this.pageSize = pageSize;
        this.totalCount = totalCount;
        this.totalPage = totalCount == 0 ? 0 : (int) ((totalCount + pageSize - 1) / pageSize);
        this.data = data;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public int getTotalPage() {
        return totalPage;
    }

    public T getData() {
        return data;
    }
}
