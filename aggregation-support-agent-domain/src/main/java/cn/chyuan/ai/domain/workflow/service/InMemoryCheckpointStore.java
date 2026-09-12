package cn.chyuan.ai.domain.workflow.service;

import cn.chyuan.ai.domain.workflow.adapter.port.ICheckpointStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流检查点内存实现（工单 0206 AB3）— 默认实现零依赖；
 * 同 runId 取 seq 最大者。
 *
 * @author chyuan
 */
public class InMemoryCheckpointStore implements ICheckpointStore {

    private final Map<String, Checkpoint> latest = new ConcurrentHashMap<>();

    @Override
    public void save(Checkpoint checkpoint) {
        latest.merge(checkpoint.runId(), checkpoint,
                (oldOne, newOne) -> newOne.seq() >= oldOne.seq() ? newOne : oldOne);
    }

    @Override
    public Checkpoint findLatest(String runId) {
        return latest.get(runId);
    }
}
