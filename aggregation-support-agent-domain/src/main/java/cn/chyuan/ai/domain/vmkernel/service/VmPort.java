package cn.chyuan.ai.domain.vmkernel.service;

import java.util.Map;

/**
 * 虚拟机端口（工单 0808 CQ8，cpython 思想）。
 * compile·run 入口统一编排/与 mlkernel 只读联动（管道阶段参数脚本以变量绑定形态执行，泛型 Map 不 import）/
 * vm-kernel.enabled 默认关（开启才改变行为）。
 */
public interface VmPort {

    /** 编译源码为字节码程序 */
    Compiler.Program compile(String source);

    /** 运行程序，返回主代码结果值 */
    Object run(Compiler.Program program);

    /** 编译即运行 */
    Object runSource(String source);

    /** mlkernel 只读联动形态：管道阶段脚本 + 输入变量绑定（形状数据不 import mlkernel） */
    Object runStage(String script, Map<String, Object> inputs);

    static VmPort inMemory() {
        return new InMemoryVm();
    }
}

final class InMemoryVm implements VmPort {

    @Override
    public Compiler.Program compile(String source) {
        return new Compiler().compile(new Parser(source).parseProgram());
    }

    @Override
    public Object run(Compiler.Program program) {
        return new Vm(program).run();
    }

    @Override
    public Object runSource(String source) {
        return run(compile(source));
    }

    @Override
    public Object runStage(String script, Map<String, Object> inputs) {
        return new Vm(compile(script), inputs).run();
    }
}
