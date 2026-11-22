package cloud.changlu.agentforge.service.modules.upload.service;

import cloud.changlu.agentforge.service.modules.upload.domain.pojo.FileInfoPojo;

import java.util.List;

/**
 * 文件元数据服务，支撑附件归属校验与历史回放。
 *
 * @author changlu
 * @since 2026-11-21
 */
public interface FileInfoService {

    /**
     * 保存文件元数据。
     *
     * @param pojo 元数据
     * @return 新增主键，即对话时引用的 {@code fileId}
     */
    Long save(FileInfoPojo pojo);

    FileInfoPojo selectById(Long id);

    List<FileInfoPojo> selectByIds(List<Long> ids);
}
