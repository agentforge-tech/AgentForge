package cloud.changlu.agentforge.service.modules.plugins.http.controller;

import cloud.changlu.agentforge.service.modules.plugins.http.domain.req.*;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.vo.*;
import cloud.changlu.agentforge.service.modules.plugins.http.service.IHttpPluginService;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@CrossOrigin
public class HttpPluginMethodController {
    private final IHttpPluginService service;

    public HttpPluginMethodController(IHttpPluginService service) {
        this.service = service;
    }

    @GetMapping("/api/http-plugin/{pluginId}/methods")
    public List<HttpPluginMethodVo> methods(
            @PathVariable("pluginId") Long pluginId,
            @RequestParam(value = "onlyEnabled", defaultValue = "false") boolean onlyEnabled) {
        return service.methods(pluginId, onlyEnabled);
    }

    @GetMapping("/api/http-plugin-method/{id}")
    public HttpPluginMethodVo detail(@PathVariable("id") Long id) {
        return service.methodDetail(id);
    }

    @PostMapping("/api/http-plugin-method/upsert")
    public HttpPluginMethodVo upsert(@RequestBody HttpPluginMethodSaveReq req) {
        return service.saveMethod(req);
    }

    @PostMapping("/api/http-plugin-method/{id}/status")
    public void status(
            @PathVariable("id") Long id, @RequestBody(required = false) HttpPluginStatusReq req) {
        service.updateMethodStatus(id, req == null ? null : req.getStatus());
    }

    @DeleteMapping("/api/http-plugin-method/{id}")
    public void delete(@PathVariable("id") Long id) {
        service.deleteMethod(id);
    }

    @PostMapping("/api/http-plugin-method/{id}/test-run")
    public HttpPluginTestRunVo testRun(
            @PathVariable("id") Long id, @RequestBody(required = false) HttpPluginTestRunReq req) {
        return service.testRun(id, req);
    }
}
