package cn.chyuan.ai.domain.rag.support;

import cn.chyuan.ai.domain.auth.model.valobj.TenantScopeVO;
import cn.chyuan.ai.domain.auth.support.RequestScopeContext;
import cn.chyuan.ai.domain.rag.model.valobj.RagSourceVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RAG 证据收集器 — 在请求线程内累积本次对话所有检索命中的证据，由 ChatService 在调用结束时取回。
 * <p>
 * 设计变更（Phase 3 → Phase 4 → Phase 4.1）：
 * <ul>
 *   <li>Phase 3：两个 {@code InheritableThreadLocal}（sources / traceId），仅依赖线程创建时的父线程继承</li>
 *   <li>Phase 4：改为 {@code ThreadLocal<Holder>} + 闭包 Holder 引用，解决 onComplete 回调跨线程读问题，
 *       但漏修了 append/setTraceId 的写路径 — reactor IO 线程的 ThreadLocal 未初始化，导致 sources 静默丢失</li>
 *   <li>Phase 4.1：恢复 {@code InheritableThreadLocal<Holder>}（让 reactor 池线程首次创建时能继承父线程 Holder），
 *       同时新增 {@link #attach(Holder)} / {@link #detach()} 公共 API，
 *       供 reactor 订阅链（doOnSubscribe / doFinally）显式注入和清理跨线程上下文。
 *       兼容 reactor 池化复用 — 已存在的 IO 线程不会继承新值，必须显式 attach。</li>
 * </ul>
 * <p>
 * 生命周期：
 * <ul>
 *   <li>请求线程入口：{@link #begin()} 重置 Holder</li>
 *   <li>InternalDocsTools / RagService 在 searchWithTrace 完成后 {@link #append(List)}，并写入 {@link #setTraceId(String)}</li>
 *   <li>请求线程出口：{@link #drain()} 取出所有证据并自动 clear（同步清理 traceId）</li>
 *   <li>SSE 路径：
 *     <ul>
 *       <li>入口保存 {@link Holder} 引用 — 通过 {@link #currentHolder()}</li>
 *       <li>订阅链上用 doOnSubscribe 调 {@link #attach(Holder)} 把 Holder 注入到 IO 线程</li>
 *       <li>订阅链上用 doFinally 调 {@link #detach()} 清理 IO 线程的 ThreadLocal</li>
 *       <li>出口（onComplete / onError）调 {@link #drainHolder(Holder)} 取数</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class RagSourceCollector {

    public static final String TOOL_CONTEXT_HOLDER_KEY =
            RagSourceCollector.class.getName() + ".holder";

    /**
     * 跨线程共享的证据 Holder，sources 用 CopyOnWriteArrayList 保证多线程 append 安全，traceId 用 volatile 保证可见性
     */
    public static final class Holder {
        /** 当前请求累积的证据列表（线程安全） */
        private final CopyOnWriteArrayList<RagSourceVO> sources = new CopyOnWriteArrayList<>();
        /** 当前请求最近一次 RAG 检索的 traceId */
        private volatile String traceId = "";
        /** 当前请求最近一次 RAG 检索的原始查询，供可观测性上报 */
        private volatile String retrievalQuery = "";
        /** 当前请求最近一次 RAG 检索的改写查询，供可观测性上报 */
        private volatile String rewriteText = "";
        /** 当前请求最近一次 RAG 检索的 TopK 配置，供可观测性上报 */
        private volatile Integer topK;
        /** 当前请求的租户作用域，供工具跨线程执行时兜底恢复 */
        private final TenantScopeVO tenantScope;

        private Holder(TenantScopeVO tenantScope) {
            this.tenantScope = RequestScopeContext.copyOf(tenantScope);
        }

        /** 读取最近一次写入的 traceId，未设置时返回空字符串 */
        public String getTraceId() {
            return traceId == null ? "" : traceId;
        }

        public String getRetrievalQuery() {
            return retrievalQuery == null ? "" : retrievalQuery;
        }

        public String getRewriteText() {
            return rewriteText;
        }

        public Integer getTopK() {
            return topK;
        }

        public TenantScopeVO getTenantScope() {
            return RequestScopeContext.copyOf(tenantScope);
        }

        /** 内部追加方法 — null/空集合被忽略 */
        void appendInternal(List<RagSourceVO> add) {
            if (add != null && !add.isEmpty()) {
                this.sources.addAll(add);
            }
        }

        /** 内部 traceId 写入 — null/空字符串被忽略 */
        void setTraceIdInternal(String t) {
            if (t != null && !t.isEmpty()) {
                this.traceId = t;
            }
        }

        /** 内部检索元数据写入，供可观测性上报 */
        void setRetrievalMetaInternal(String query, String rewriteText, Integer topK) {
            if (query != null && !query.isEmpty()) {
                this.retrievalQuery = query;
            }
            if (rewriteText != null) {
                this.rewriteText = rewriteText;
            }
            if (topK != null) {
                this.topK = topK;
            }
        }

        /** 当前已累积证据的只读快照 */
        public List<RagSourceVO> snapshotSources() {
            return new ArrayList<>(sources);
        }
    }

    /**
     * 当前线程持有的 Holder 引用。
     * 使用 {@link InheritableThreadLocal} 让子线程（如 {@code CompletableFuture.supplyAsync} 首次创建的 worker、
     * RxJava 首次创建的 io worker）能在线程创建时继承父线程 Holder。
     * <p>
     * 池化线程复用时不会再次继承，必须由调用方显式 {@link #attach(Holder)} 注入。
     */
    private static final InheritableThreadLocal<Holder> HOLDER = new InheritableThreadLocal<>();

    private RagSourceCollector() {
    }

    /** 入口重置：创建新 Holder 并塞入当前线程 */
    public static void begin() {
        begin(null);
    }

    /** 入口重置：创建带租户作用域的新 Holder 并塞入当前线程 */
    public static void begin(TenantScopeVO tenantScope) {
        HOLDER.set(new Holder(tenantScope));
    }

    /**
     * 暴露当前线程 Holder 引用 — 供外层（如 SSE Controller）以 final 变量闭包保存，
     * 后续 onComplete / onError 即使跑在另一个 IO 线程，也可以通过该引用 drain。
     *
     * @return 当前线程 Holder；未 begin 时返回 null
     */
    public static Holder currentHolder() {
        return HOLDER.get();
    }

    public static TenantScopeVO currentTenantScope() {
        Holder h = HOLDER.get();
        return h == null ? null : h.getTenantScope();
    }

    /**
     * 显式把外部 Holder 引用注入当前线程的 ThreadLocal。
     * <p>
     * 用途：跨线程订阅链（reactor / RxJava）的 worker 线程在执行工具调用前显式调用此方法，
     * 让 {@link #append(List)} / {@link #setTraceId(String)} 能拿到对应 Holder（而不是 null）。
     * <p>
     * 配合 {@link #detach()} 使用，建议放在 reactor 的 doOnSubscribe / doFinally 钩子里。
     *
     * @param holder 要注入的 Holder；为 null 时等同于 {@link #detach()}
     */
    public static void attach(Holder holder) {
        if (holder == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(holder);
        }
    }

    /**
     * 清理当前线程的 ThreadLocal。
     * <p>
     * 用途：在 reactor 订阅链结束（doFinally）时调用，避免池化 worker 线程持续持有 Holder 引用
     * 导致跨请求污染或内存堆积。
     */
    public static void detach() {
        HOLDER.remove();
    }

    /** 追加一批证据（null 或空列表会被忽略） */
    public static void append(List<RagSourceVO> sources) {
        Holder h = HOLDER.get();
        if (h == null) {
            return;
        }
        h.appendInternal(sources);
    }

    /** 由 RagService.searchWithTrace 调用，记录本次检索的 traceId */
    public static void setTraceId(String traceId) {
        Holder h = HOLDER.get();
        if (h == null) {
            return;
        }
        h.setTraceIdInternal(traceId);
    }

    /** 由 searchWithTrace 调用，记录本次检索的查询/改写/TopK，供出口上报 RAG 检索日志 */
    public static void setRetrievalMeta(String query, String rewriteText, Integer topK) {
        Holder h = HOLDER.get();
        if (h == null) {
            return;
        }
        h.setRetrievalMetaInternal(query, rewriteText, topK);
    }

    /** 读取当前线程最近一次写入的 traceId，未设置时返回空字符串 */
    public static String getTraceId() {
        Holder h = HOLDER.get();
        return h == null ? "" : h.getTraceId();
    }

    /**
     * 出口取走全部证据并清空当前线程 ThreadLocal（同步清理 Holder 引用）
     * <p>
     * 注意：drain 后当前线程 isActive() 变 false，setTraceId/append 都不再生效
     */
    public static List<RagSourceVO> drain() {
        Holder h = HOLDER.get();
        HOLDER.remove();
        if (h == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(h.sources);
    }

    /**
     * 判断当前线程的收集器是否处于激活状态
     */
    public static boolean isActive() {
        return HOLDER.get() != null;
    }

    /**
     * 跨线程 drain — 由外层（如 SSE Controller）显式持有的 Holder 引用直接取走 sources。
     * <p>
     * 用途：reactor onComplete 回调可能在另一个池化 IO 线程上触发，此时该线程的 ThreadLocal 是空的，
     * 不能调 {@link #drain()}；外层必须在请求入口保存 Holder 引用，回调里用此方法取数。
     * <p>
     * 注意：本方法**只清空 Holder 内部的 sources 列表**，不会清理任何 ThreadLocal。
     * 真正的 ThreadLocal 清理由请求线程上的 {@link #drain()} 或 {@link #detach()} 完成。
     *
     * @param h 请求入口保存的 Holder 引用；为 null 时返回空列表
     * @return 该 Holder 内已累积的证据快照
     */
    public static List<RagSourceVO> drainHolder(Holder h) {
        if (h == null) {
            return Collections.emptyList();
        }
        List<RagSourceVO> snapshot = new ArrayList<>(h.sources);
        h.sources.clear();
        return snapshot;
    }
}
