package cn.chyuan.ai.domain.rag.support;

import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 证据收集器 — 在请求线程内累积本次对话所有检索命中的证据，由 ChatService 在调用结束时取回。
 * <p>
 * 使用 InheritableThreadLocal，确保 spring-ai 工具在派生线程上执行时仍能继承到收集器。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>ChatService.handleMessage 入口 begin() 重置</li>
 *   <li>InternalDocsTools / RagService 在 searchWithTrace 完成后 append(sources)，并写入 setTraceId</li>
 *   <li>ChatService.handleMessage 出口 drain() 取出所有证据并自动 clear（同步清理 traceId）</li>
 * </ul>
 */
public final class RagSourceCollector {

    /** 当前线程累积的证据列表 */
    private static final ThreadLocal<List<RagSourceVO>> HOLDER = new InheritableThreadLocal<>();

    /** 当前线程最近一次 RAG 检索的 traceId，便于 ChatService 出口拼到响应中 */
    private static final ThreadLocal<String> TRACE_ID = new InheritableThreadLocal<>();

    private RagSourceCollector() {
    }

    /** 入口重置：清空既有证据与 traceId */
    public static void begin() {
        HOLDER.set(new ArrayList<>());
        TRACE_ID.remove();
    }

    /** 追加一批证据（null 或空列表会被忽略） */
    public static void append(List<RagSourceVO> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        List<RagSourceVO> list = HOLDER.get();
        if (list == null) {
            return;
        }
        list.addAll(sources);
    }

    /** 由 RagService.searchWithTrace 调用，记录本次检索的 traceId */
    public static void setTraceId(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return;
        }
        // 仅在收集器处于激活状态时记录，避免污染未 begin 的线程
        if (HOLDER.get() == null) {
            return;
        }
        TRACE_ID.set(traceId);
    }

    /** 读取最近一次写入的 traceId，未设置时返回空字符串 */
    public static String getTraceId() {
        String value = TRACE_ID.get();
        return value == null ? "" : value;
    }

    /** 出口取走全部证据并清空当前线程（同步清理 traceId） */
    public static List<RagSourceVO> drain() {
        List<RagSourceVO> list = HOLDER.get();
        HOLDER.remove();
        TRACE_ID.remove();
        return list == null ? Collections.emptyList() : list;
    }

    public static boolean isActive() {
        return HOLDER.get() != null;
    }
}
