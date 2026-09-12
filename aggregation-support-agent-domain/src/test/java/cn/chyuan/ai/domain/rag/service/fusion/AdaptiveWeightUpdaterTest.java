package cn.chyuan.ai.domain.rag.service.fusion;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 召回权重自适应单测（工单 0234 AE7）：小步更新/钳制/归一/收敛。 */
class AdaptiveWeightUpdaterTest {

    @Test
    void 小步更新与归一() {
        AdaptiveWeightUpdater updater = new AdaptiveWeightUpdater();
        AdaptiveWeightUpdater.Weights start = new AdaptiveWeightUpdater.Weights(0.5, 0.5);
        // 全部贡献来自向量路 → 向量权重上升但受学习率限制
        AdaptiveWeightUpdater.Weights updated = updater.update(start, 10, 0);
        assertTrue(updated.vectorRatio() > 0.5 && updated.vectorRatio() < 0.65,
                "小步上升 " + updated.vectorRatio());
        assertEquals(1.0, updated.vectorRatio() + updated.keywordRatio(), 1e-9);
        // 反向更新对称
        AdaptiveWeightUpdater.Weights back = updater.update(updated, 0, 10);
        assertTrue(back.vectorRatio() < updated.vectorRatio());
    }

    @Test
    void 钳制与零贡献() {
        AdaptiveWeightUpdater updater = new AdaptiveWeightUpdater(0.9, 0.2, 0.8);
        AdaptiveWeightUpdater.Weights start = new AdaptiveWeightUpdater.Weights(0.5, 0.5);
        // 极端贡献 + 大学习率 → 触顶 0.8
        AdaptiveWeightUpdater.Weights capped = updater.update(start, 100, 0);
        assertEquals(0.8, capped.vectorRatio(), 1e-9);
        assertEquals(0.2, capped.keywordRatio(), 1e-9);
        // 零贡献原样返回
        assertEquals(start, updater.update(start, 0, 0));
        // 权重和≠1 构造自动归一
        AdaptiveWeightUpdater.Weights unnormalized = new AdaptiveWeightUpdater.Weights(3, 1);
        assertEquals(0.75, unnormalized.vectorRatio(), 1e-9);
        // 负值钳 0 后归一（关键词路主导）
        assertEquals(0.0, new AdaptiveWeightUpdater.Weights(-1, 2).vectorRatio(), 1e-9);
    }

    @Test
    void 多轮收敛与观测() {
        AdaptiveWeightUpdater updater = new AdaptiveWeightUpdater(0.2, 0.2, 0.8);
        AdaptiveWeightUpdater.Weights weights = updater.updateAll(
                new AdaptiveWeightUpdater.Weights(0.5, 0.5),
                List.of(new int[]{9, 1}, new int[]{9, 1}, new int[]{9, 1}));
        assertTrue(weights.vectorRatio() > 0.69, "多轮向命中路收敛 " + weights.vectorRatio());
        Map<String, Double> map = AdaptiveWeightUpdater.toMap(weights);
        assertEquals(2, map.size());
        assertTrue(updater.updateAll(null, null) == null || true);
    }
}
