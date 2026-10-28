package cloud.changlu.agentforge.service.modules.plugins.http.dao;

import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.HttpPluginMethodPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HttpPluginMethodMapper {
    HttpPluginMethodPojo selectById(@Param("id") Long id);

    List<HttpPluginMethodPojo> selectByPluginId(
            @Param("pluginId") Long pluginId, @Param("onlyEnabled") boolean onlyEnabled);

    List<HttpPluginMethodPojo> selectByIds(@Param("ids") List<Long> ids);

    long countByName(
            @Param("pluginId") Long pluginId,
            @Param("methodName") String methodName,
            @Param("excludeId") Long excludeId);

    int insert(HttpPluginMethodPojo pojo);

    int update(HttpPluginMethodPojo pojo);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    int deleteById(@Param("id") Long id);

    int deleteByPluginId(@Param("pluginId") Long pluginId);
}
