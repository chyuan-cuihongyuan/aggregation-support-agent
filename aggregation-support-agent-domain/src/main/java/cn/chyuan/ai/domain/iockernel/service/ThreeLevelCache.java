package cn.chyuan.ai.domain.iockernel.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 三级缓存（工单 1121 EV3，spring-framework 思想）。
 * 一级成品单例/二级早期引用/三级工厂：getEarly 依次查一→二→三，三级命中迁移至二级；
 * 构造器环表现为在创建中且三级无工厂——getEarly 返回 null，由容器判环拒绝。
 */
public final class ThreeLevelCache {

    /** 一级：成品单例 */
    private final Map<String, Instance> singletonObjects = new LinkedHashMap<>();

    /** 二级：早期引用（已穿透三级工厂） */
    private final Map<String, Instance> earlySingletonObjects = new LinkedHashMap<>();

    /** 三级：早期引用工厂 */
    private final Map<String, Instance> singletonFactories = new LinkedHashMap<>();

    public void putSingleton(String beanName, Instance instance) {
        singletonObjects.put(beanName, instance);
        earlySingletonObjects.remove(beanName);
        singletonFactories.remove(beanName);
    }

    public Instance getSingleton(String beanName) {
        return singletonObjects.get(beanName);
    }

    /** 早期引用探查：一级命中直返；二级命中直返；三级命中迁移二级后返；全失返回 null（构造器环信号） */
    public Instance getEarly(String beanName) {
        Instance instance = singletonObjects.get(beanName);
        if (instance != null) {
            return instance;
        }
        instance = earlySingletonObjects.get(beanName);
        if (instance != null) {
            return instance;
        }
        Instance factory = singletonFactories.get(beanName);
        if (factory != null) {
            earlySingletonObjects.put(beanName, factory);
            singletonFactories.remove(beanName);
            return factory;
        }
        return null;
    }

    /** 挂工厂：重复挂拒绝 */
    public void addFactory(String beanName, Instance instance) {
        if (singletonFactories.containsKey(beanName) || earlySingletonObjects.containsKey(beanName)) {
            throw new IllegalStateException("重复暴露早期引用: " + beanName);
        }
        singletonFactories.put(beanName, instance);
    }

    public void removeFactory(String beanName) {
        singletonFactories.remove(beanName);
    }

    public boolean inEarlyExposure(String beanName) {
        return singletonFactories.containsKey(beanName) || earlySingletonObjects.containsKey(beanName);
    }

    public int singletonCount() {
        return singletonObjects.size();
    }
}
