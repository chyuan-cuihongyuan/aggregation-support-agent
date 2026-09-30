package cn.chyuan.ai.domain.iockernel.service;

/**
 * Bean 实例（工单 1119 EV 簇，spring-framework 思想）。
 * beanName + scope + 创建序号：单例同 bean 复用同 seq，原型每次 get 新 seq。
 */
public record Instance(String beanName, String scope, long seq) {
}
