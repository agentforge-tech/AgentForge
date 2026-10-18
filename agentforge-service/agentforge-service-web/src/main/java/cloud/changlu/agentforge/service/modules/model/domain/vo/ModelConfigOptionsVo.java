package cloud.changlu.agentforge.service.modules.model.domain.vo;

import java.util.List;

/**
 * 模型厂商与模型类别枚举选项，供前端下拉框统一使用枚举值。
 *
 * @author changlu
 * @since 2026-10-14
 */
public class ModelConfigOptionsVo {

    private List<ModelConfigOptionVo> providers;
    private List<ModelConfigOptionVo> modelTypes;

    public ModelConfigOptionsVo() {}

    public ModelConfigOptionsVo(
            List<ModelConfigOptionVo> providers, List<ModelConfigOptionVo> modelTypes) {
        this.providers = providers;
        this.modelTypes = modelTypes;
    }

    public List<ModelConfigOptionVo> getProviders() {
        return providers;
    }

    public void setProviders(List<ModelConfigOptionVo> providers) {
        this.providers = providers;
    }

    public List<ModelConfigOptionVo> getModelTypes() {
        return modelTypes;
    }

    public void setModelTypes(List<ModelConfigOptionVo> modelTypes) {
        this.modelTypes = modelTypes;
    }
}
