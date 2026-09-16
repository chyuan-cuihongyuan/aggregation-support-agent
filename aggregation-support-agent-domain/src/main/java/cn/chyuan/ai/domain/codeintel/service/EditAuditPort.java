package cn.chyuan.ai.domain.codeintel.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 编辑审计端口（工单 0434 AZ8）+ 内存实现。
 * 每次代码编辑落审计（文件/策略/成功/失败清单/检查点 id/耗时），按文件查询、按时间排序。
 */
public interface EditAuditPort {

    /** 编辑策略 */
    enum Strategy {
        SEARCH_REPLACE, UNIFIED_DIFF
    }

    /** 审计记录 */
    record EditRecord(long id, String file, Strategy strategy, boolean success,
                      List<String> errors, String checkpointId, long costMs, long createdAtMs) {
    }

    void append(EditRecord record);

    List<EditRecord> findByFile(String file);

    /** 全量按时间升序 */
    List<EditRecord> listAll();

    /** 内存实现 */
    class InMemoryEditAudit implements EditAuditPort {

        private final List<EditRecord> records = new ArrayList<>();
        private long seq;

        @Override
        public synchronized void append(EditRecord record) {
            records.add(new EditRecord(++seq, record.file(), record.strategy(), record.success(),
                    List.copyOf(record.errors()), record.checkpointId(), record.costMs(), record.createdAtMs()));
        }

        @Override
        public synchronized List<EditRecord> findByFile(String file) {
            return records.stream().filter(r -> r.file().equals(file)).toList();
        }

        @Override
        public synchronized List<EditRecord> listAll() {
            return records.stream().sorted(Comparator.comparingLong(EditRecord::createdAtMs)).toList();
        }
    }
}
