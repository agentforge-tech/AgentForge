package cloud.changlu.agentforge.service.modules.upload.service.impl;

import cloud.changlu.agentforge.service.modules.upload.dao.FileInfoMapper;
import cloud.changlu.agentforge.service.modules.upload.domain.pojo.FileInfoPojo;
import cloud.changlu.agentforge.service.modules.upload.service.FileInfoService;

import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * {@link FileInfoService} 默认实现。
 *
 * @author changlu
 * @since 2026-11-21
 */
@Service
public class FileInfoServiceImpl implements FileInfoService {

    private final FileInfoMapper fileInfoMapper;

    public FileInfoServiceImpl(FileInfoMapper fileInfoMapper) {
        this.fileInfoMapper = fileInfoMapper;
    }

    @Override
    public Long save(FileInfoPojo pojo) {
        fileInfoMapper.insert(pojo);
        return pojo.getId();
    }

    @Override
    public FileInfoPojo selectById(Long id) {
        return id == null ? null : fileInfoMapper.selectById(id);
    }

    @Override
    public List<FileInfoPojo> selectByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return fileInfoMapper.selectByIds(ids);
    }
}
