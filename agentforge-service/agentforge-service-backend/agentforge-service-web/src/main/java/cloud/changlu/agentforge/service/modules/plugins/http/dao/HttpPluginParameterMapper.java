package cloud.changlu.agentforge.service.modules.plugins.http.dao;

import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.HttpPluginParameterPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HttpPluginParameterMapper {
    List<HttpPluginParameterPojo> selectByMethodId(@Param("methodId") Long methodId);

    List<HttpPluginParameterPojo> selectByMethodIds(@Param("methodIds") List<Long> methodIds);

    int insert(HttpPluginParameterPojo pojo);

    int deleteByMethodId(@Param("methodId") Long methodId);

    int deleteByPluginId(@Param("pluginId") Long pluginId);
}
