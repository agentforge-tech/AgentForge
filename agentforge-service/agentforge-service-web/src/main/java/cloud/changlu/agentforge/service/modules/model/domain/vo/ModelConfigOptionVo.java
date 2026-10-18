package cloud.changlu.agentforge.service.modules.model.domain.vo;

/**
 * 枚举下拉选项返回对象，{@code value} 对应枚举 code。
 *
 * @author changlu
 * @since 2026-10-14
 */
public class ModelConfigOptionVo {

    private Integer value;
    private String label;
    private String name;

    public ModelConfigOptionVo() {}

    public ModelConfigOptionVo(Integer value, String label, String name) {
        this.value = value;
        this.label = label;
        this.name = name;
    }

    public Integer getValue() {
        return value;
    }

    public void setValue(Integer value) {
        this.value = value;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
