package cloud.changlu.agentforge.service.modules.model.dao;

import cloud.changlu.agentforge.service.modules.model.domain.ModelConfigPojo;
import cloud.changlu.agentforge.service.modules.model.domain.req.ModelConfigQueryReq;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * LLM 模型配置 Mapper，对应 {@code ai_model_config} 表。
 *
 * @author changlu
 * @since 2026-10-14
 */
@Mapper
public interface ModelConfigMapper {

    ModelConfigPojo selectById(@Param("id") Long id);

    List<ModelConfigPojo> selectList(ModelConfigQueryReq query);

    long selectCount(ModelConfigQueryReq query);

    List<ModelConfigPojo> selectPage(ModelConfigQueryReq query);

    int insert(ModelConfigPojo pojo);

    int update(ModelConfigPojo pojo);

    int deleteById(@Param("id") Long id);
}
