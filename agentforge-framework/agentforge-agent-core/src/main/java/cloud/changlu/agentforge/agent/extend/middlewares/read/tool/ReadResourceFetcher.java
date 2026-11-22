package cloud.changlu.agentforge.agent.extend.middlewares.read.tool;

import cloud.changlu.agentforge.agent.extend.middlewares.read.domain.ReadBatchResult;

import java.util.List;

/**
 * 读取资源元数据。
 *
 * @author changlu
 * @since 2026-11-22
 */
public interface ReadResourceFetcher {

    ReadBatchResult fetch(List<String> paths, String hint);
}
