package cloud.changlu.agentforge.service.modules.plugins.http.service;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.req.*;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.vo.*;

import java.util.List;

public interface IHttpPluginService {
    PageResult<List<HttpPluginVo>> page(
            String keyword, Integer status, Integer page, Integer pageSize);

    HttpPluginVo detail(Long id);

    HttpPluginVo savePlugin(HttpPluginSaveReq req);

    void updatePluginStatus(Long id, Integer status);

    void deletePlugin(Long id);

    List<HttpPluginMethodVo> methods(Long pluginId, boolean onlyEnabled);

    HttpPluginMethodVo methodDetail(Long id);

    HttpPluginMethodVo saveMethod(HttpPluginMethodSaveReq req);

    void updateMethodStatus(Long id, Integer status);

    void deleteMethod(Long id);

    HttpPluginTestRunVo testRun(Long id, HttpPluginTestRunReq req);

    HttpPluginVo previewCurl(HttpPluginCurlReq req);

    HttpPluginVo importCurl(HttpPluginCurlReq req);

    HttpPluginVo importJson(HttpPluginJsonImportReq req);

    HttpPluginJsonImportReq exportJson(Long id);

    List<HttpPluginVo> options();
}
