package cloud.changlu.agentforge.service.modules.plugins.http.dao;

import cloud.changlu.agentforge.service.modules.plugins.http.domain.pojo.HttpPluginPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HttpPluginMapper {
    HttpPluginPojo selectById(@Param("id") Long id);

    List<HttpPluginPojo> selectPage(
            @Param("keyword") String keyword,
            @Param("status") Integer status,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize);

    long selectCount(@Param("keyword") String keyword, @Param("status") Integer status);

    long countByAlias(
            @Param("alias") String alias,
            @Param("userId") Long userId,
            @Param("excludeId") Long excludeId);

    int countMethods(@Param("id") Long id);

    int insert(HttpPluginPojo pojo);

    int update(HttpPluginPojo pojo);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    int deleteById(@Param("id") Long id);
}
