package cloud.changlu.agentforge.service.modules.plugins.http.controller;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.req.*;
import cloud.changlu.agentforge.service.modules.plugins.http.domain.vo.HttpPluginVo;
import cloud.changlu.agentforge.service.modules.plugins.http.service.IHttpPluginService;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/http-plugin")
@CrossOrigin
public class HttpPluginController {
    private final IHttpPluginService service;

    public HttpPluginController(IHttpPluginService service) {
        this.service = service;
    }

    @GetMapping("/page")
    public PageResult<List<HttpPluginVo>> page(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "pageSize", required = false) Integer pageSize) {
        return service.page(keyword, status, page, pageSize);
    }

    @GetMapping("/options")
    public List<HttpPluginVo> options() {
        return service.options();
    }

    @GetMapping("/import-file-sop")
    public ResponseEntity<Resource> downloadImportFileSop() {
        Resource resource = new ClassPathResource("http-plugin/HTTP插件文件快速制作SOP.md");
        ContentDisposition disposition =
                ContentDisposition.attachment()
                        .filename("AgentForge-HTTP插件文件快速制作SOP.md", StandardCharsets.UTF_8)
                        .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(resource);
    }

    @GetMapping("/{id}")
    public HttpPluginVo detail(@PathVariable("id") Long id) {
        return service.detail(id);
    }

    @PostMapping("/upsert")
    public HttpPluginVo upsert(@RequestBody HttpPluginSaveReq req) {
        return service.savePlugin(req);
    }

    @PostMapping("/{id}/status")
    public void status(
            @PathVariable("id") Long id, @RequestBody(required = false) HttpPluginStatusReq req) {
        service.updatePluginStatus(id, req == null ? null : req.getStatus());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id) {
        service.deletePlugin(id);
    }

    @PostMapping("/curl/preview")
    public HttpPluginVo previewCurl(@RequestBody HttpPluginCurlReq req) {
        return service.previewCurl(req);
    }

    @PostMapping("/curl/import")
    public HttpPluginVo importCurl(@RequestBody HttpPluginCurlReq req) {
        return service.importCurl(req);
    }

    @PostMapping("/json/import")
    public HttpPluginVo importJson(@RequestBody HttpPluginJsonImportReq req) {
        return service.importJson(req);
    }

    @GetMapping("/{id}/export")
    public HttpPluginJsonImportReq exportJson(@PathVariable("id") Long id) {
        return service.exportJson(id);
    }
}
