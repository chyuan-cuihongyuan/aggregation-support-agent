package cn.chyuan.ai.domain.txkernel.service;

import java.util.Map;

/**
 * undo log（工单 1024 EK4，seata AT 思想）。
 * before/after 镜像生成/主键提取/同值无变更跳过。
 */
public final class UndoLog {

    /** 行镜像：before 为 null 表示插入，after 为 null 表示删除 */
    public record RowImage(String xid, String table, String pk,
                           Map<String, Object> before, Map<String, Object> after) {

        public boolean isInsert() {
            return before == null;
        }

        public boolean isDelete() {
            return after == null;
        }

        String rowKey() {
            return table + "#" + pk;
        }
    }

    private UndoLog() {
    }

    /** 生成镜像：主键从 before（或插入时 after）提取；同值无变更返回 null（跳过）；缺主键字段拒绝 */
    public static RowImage build(String xid, String table, String pkField,
                                 Map<String, Object> before, Map<String, Object> after) {
        if (table == null || table.isEmpty()) {
            throw new IllegalArgumentException("表名为空");
        }
        if (pkField == null || pkField.isEmpty()) {
            throw new IllegalArgumentException("主键字段为空");
        }
        if (before == null && after == null) {
            throw new IllegalArgumentException("前后镜像均为空");
        }
        Object pk = before != null ? before.get(pkField) : after.get(pkField);
        if (pk == null) {
            throw new IllegalArgumentException("主键字段缺失: " + pkField);
        }
        if (before != null && after != null && before.equals(after)) {
            return null;
        }
        return new RowImage(xid, table, String.valueOf(pk),
                before == null ? null : Map.copyOf(before),
                after == null ? null : Map.copyOf(after));
    }
}
