package cn.chyuan.ai.domain.leasekernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * watch 事件流（工单 1184 FC5 / 1185 FC6，etcd 思想）。
 * 从指定修订起点（不含）订阅历史与后续事件，事件按修订有序；
 * 按前缀过滤不改修订序；起点超前拒绝；起点早于压缩点拒绝；压缩点之前事件不再发布。
 */
public final class WatchHub implements Revision.Sink {

    /** watch 事件：PUT/DELETE；DELETE 事件 value 为 null */
    public record Event(long revision, String type, String key, String value) {
    }

    private final Revision kv;
    private final List<Event> log = new ArrayList<>();
    private final Map<Long, Long> starts = new LinkedHashMap<>();
    private final Map<Long, String> prefixes = new HashMap<>();
    private long watchSeq;

    public WatchHub(Revision kv) {
        this.kv = kv;
        kv.bind(this);
    }

    @Override
    public void onPut(long revision, String key, String value) {
        log.add(new Event(revision, "PUT", key, value));
    }

    @Override
    public void onDelete(long revision, String key) {
        log.add(new Event(revision, "DELETE", key, null));
    }

    /** 订阅：空前缀全收 */
    public long watch(long startRevision) {
        return watch(startRevision, "");
    }

    public long watch(long startRevision, String prefix) {
        if (startRevision > kv.current()) {
            throw new IllegalArgumentException("watch 起点超前: " + startRevision);
        }
        if (startRevision < kv.compacted()) {
            throw new IllegalArgumentException("watch 起点早于压缩点: " + startRevision);
        }
        long id = ++watchSeq;
        starts.put(id, startRevision);
        prefixes.put(id, prefix == null ? "" : prefix);
        return id;
    }

    /** 收取事件：> 起点修订、> 压缩点、命中前缀，按修订升序 */
    public List<Event> collect(long watchId) {
        Long start = starts.get(watchId);
        if (start == null) {
            throw new IllegalArgumentException("未知 watch: " + watchId);
        }
        String prefix = prefixes.get(watchId);
        long compact = kv.compacted();
        List<Event> out = new ArrayList<>();
        for (Event e : log) {
            if (e.revision() <= start || (compact > 0 && e.revision() <= compact)) {
                continue;
            }
            if (!prefix.isEmpty() && !e.key().startsWith(prefix)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }
}
