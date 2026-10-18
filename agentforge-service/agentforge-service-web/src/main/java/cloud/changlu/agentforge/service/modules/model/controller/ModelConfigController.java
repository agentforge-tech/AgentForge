package cloud.changlu.agentforge.service.modules.model.controller;

import cloud.changlu.agentforge.service.common.pager.PageResult;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigQueryReq;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigTestReq;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigUpsertReq;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigDetailVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigOptionsVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigTestVo;
import cloud.changlu.agentforge.service.modules.model.domain.vo.ModelConfigVo;
import cloud.changlu.agentforge.service.modules.model.service.IModelConfigService;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * LLM 模型配置 Controller。
 *
 * @author changlu
 * @since 2026-10-14
 */
@RestController
@RequestMapping("/api/model-config")
@CrossOrigin
public class ModelConfigController {

    private final IModelConfigService modelConfigService;

    public ModelConfigController(IModelConfigService modelConfigService) {
        this.modelConfigService = modelConfigService;
    }

    @PostMapping("/upsert")
    public ModelConfigVo upsert(@RequestBody ModelConfigUpsertReq req) {
        return modelConfigService.upsert(req);
    }

    @GetMapping("/options")
    public ModelConfigOptionsVo options() {
        return modelConfigService.options();
    }

    @GetMapping("/list")
    public List<ModelConfigVo> list(ModelConfigQueryReq req) {
        return modelConfigService.list(req);
    }

    @GetMapping("/page")
    public PageResult<List<ModelConfigVo>> page(ModelConfigQueryReq req) {
        return modelConfigService.page(req);
    }

    @GetMapping("/{id}")
    public ModelConfigDetailVo getInfo(@PathVariable("id") Long id) {
        return modelConfigService.getById(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") Long id) {
        modelConfigService.delete(id);
    }

    @PostMapping("/testConn")
    public ModelConfigTestVo testConnectivity(@RequestBody ModelConfigTestReq req) {
        return modelConfigService.testConnectivity(req);
    }

    @PostMapping("/testFunctionCall")
    public ModelConfigTestVo testFunctionCall(@RequestBody ModelConfigTestReq req) {
        return modelConfigService.testFunctionCall(req);
    }
}
