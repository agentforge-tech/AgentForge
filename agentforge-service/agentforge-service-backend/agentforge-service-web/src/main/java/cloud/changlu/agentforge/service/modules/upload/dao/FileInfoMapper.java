package cloud.changlu.agentforge.service.modules.upload.dao;

import cloud.changlu.agentforge.service.modules.upload.domain.pojo.FileInfoPojo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文件元数据 Mapper，对应 {@code ai_file_info}。
 *
 * @author changlu
 * @since 2026-11-21
 */
@Mapper
public interface FileInfoMapper {

    int insert(FileInfoPojo pojo);

    FileInfoPojo selectById(@Param("id") Long id);

    List<FileInfoPojo> selectByIds(@Param("ids") List<Long> ids);
}
