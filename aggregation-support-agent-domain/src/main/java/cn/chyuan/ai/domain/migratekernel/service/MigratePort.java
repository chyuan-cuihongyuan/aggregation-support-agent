package cn.chyuan.ai.domain.migratekernel.service;

import java.util.List;

/**
 * 迁移账本端口（工单 1158 EZ8，prisma 思想）。
 * plan·apply·verify 入口统一编排：迁移登记·顺序应用·checksum·baseline·squash·
 * drift·失败恢复组合管线/mvcckernel 事件形状只读联动（形状键与 MvccStore.Event 字段
 * 对齐，不 import mvcckernel）/migrate-kernel.enabled 默认关（开启才改变行为）。
 */
public interface MigratePort {

    /** 登记迁移（EZ1） */
    void record(String name, String content);

    /** 未应用计划（登记序 PENDING/FAILED 名单，EZ2） */
    List<String> plan();

    /** 顺序应用至失败或尽，返回本轮应用名（EZ2） */
    List<String> apply();

    /** 注入下一次应用失败（测试口径，EZ2/EZ7） */
    void failNext(String name);

    /** 修复后重试失败条（EZ7） */
    void retry(String name);

    /** 显式 resolved 跳过失败条（EZ7） */
    void resolve(String name);

    /** 连续失败计数（EZ7） */
    int failures(String name);

    /** 基线：至多含 upToName 的前置段标记既有（EZ4） */
    void baseline(String upToName);

    /** squash 合并连续已应用区间，返回合并条名（EZ5） */
    String squash(String fromName, String toName);

    /** 漂移校验：实际 schema 指纹，返回 CONSISTENT/DRIFT（EZ6） */
    String verify(String actualSchemaFingerprint);

    /** 已应用条目序（EZ2） */
    List<String> applied();

    /** mvcckernel 事件形状只读联动（MvccStore.Event: revision/type/key/value） */
    List<String> revisionsShape();

    static MigratePort inMemory() {
        return new Migrator();
    }
}
