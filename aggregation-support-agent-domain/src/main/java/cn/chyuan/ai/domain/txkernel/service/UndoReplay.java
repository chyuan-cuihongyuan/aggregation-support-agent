package cn.chyuan.ai.domain.txkernel.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * undo 回放（工单 1026 EK6，seata 反向补偿思想）。
 * undo 逆序回放/镜像不匹配脏写拒绝/回滚释放锁。
 */
public final class UndoReplay {

    /** 数据面：rowKey(table#pk) → 行数据 */
    private final Map<String, Map<String, Object>> data = new HashMap<>();
    private final List<UndoLog.RowImage> logs = new ArrayList<>();

    /** 业务写入：记录 after 前的 before 镜像并落 after（null 表示删除） */
    public synchronized void write(UndoLog.RowImage image) {
        String rowKey = table("#", image);
        if (image.after() == null) {
            data.remove(rowKey);
        } else {
            data.put(rowKey, new HashMap<>(image.after()));
        }
        logs.add(image);
    }

    private static String table(String sep, UndoLog.RowImage image) {
        return image.table() + sep + image.pk();
    }

    /** 逆序回放：当前值须等于 after 镜像，否则脏写拒绝；回放恢复 before（插入则删除） */
    public synchronized void replay(String xid) {
        for (int i = logs.size() - 1; i >= 0; i--) {
            UndoLog.RowImage image = logs.get(i);
            if (!image.xid().equals(xid)) {
                continue;
            }
            String rowKey = table("#", image);
            Map<String, Object> current = data.get(rowKey);
            if (image.after() != null && (current == null || !image.after().equals(current))) {
                throw new IllegalStateException("脏写拒绝: " + rowKey + " 当前 " + current);
            }
            if (image.before() == null) {
                data.remove(rowKey);
            } else {
                data.put(rowKey, new HashMap<>(image.before()));
            }
            logs.remove(i);
        }
    }

    public synchronized Map<String, Object> row(String table, String pk) {
        Map<String, Object> row = data.get(table + "#" + pk);
        return row == null ? null : Map.copyOf(row);
    }

    public synchronized int pendingLogs() {
        return logs.size();
    }
}
