package cn.chyuan.ai.domain.modelkernel.service;

import java.util.List;
import java.util.Map;

/**
 * 模型清单端口（工单 1036 EL8，ollama 思想）。
 * pull·load·unload 入口统一编排/与 inferkernel 运行参数作参数形状只读联动（泛型形状串不 import）/
 * model-kernel.enabled 默认关（开启才改变行为）。
 */
public interface ModelPort {

    /** 拉取：解析 manifest + 校验 digest + 存层 + 引用 + 注册 tag */
    long pull(String tag, List<Map<String, Object>> rawLayers);

    List<String> tags();

    /** 加载请求入队（容量超限拒绝） */
    LoadQueue.Request enqueueLoad(String tag, String requester);

    LoadQueue.Request completeLoad();

    /** 加载完成：取得驻留（keep-alive） */
    void loaded(String tag, long keepAliveTicks);

    /** 续期：取 max(剩余，新值) */
    void renew(String tag, long keepAliveTicks);

    /** 显式卸载：引用归零才释放；仍有引用拒绝 */
    void unload(String tag);

    int refs(String tag);

    /** 驻留步进：返回到期卸载名单 */
    List<String> tick();

    /** inferkernel 运行参数形状只读联动：默认参数键值形状串（形状数据不 import inferkernel） */
    String optionsShape();

    void setDefaultOption(String key, String value);

    static ModelPort inMemory() {
        return new ModelHub();
    }
}
