package cn.chyuan.ai.test;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * AUTOLOOP al-03 / 工单 1003：DDD 六层架构守卫（借鉴 archunit/ArchUnit）。
 *
 * <p>分层不变量按 2026-09 现实校准（import 扫描结论）：
 * types→无；domain→types；api→domain/types；infrastructure→domain/types；
 * trigger→api+infrastructure+domain+types；app 模块（config 等包）为组装根不入层。
 *
 * <p>刻意不做「domain 禁 spring」规则：本仓 domain 即以 spring-ai/stereotype 为风格，
 * 与现实对抗的守卫没有存活价值。
 */
@AnalyzeClasses(packages = "cn.chyuan.ai", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule 六层依赖方向 = layeredArchitecture().consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            // 层模式锚定 cn.chyuan.ai，避免 ..api.. 误圈 org.springframework.ai.openai.api 等第三方包
            .layer("Types").definedBy("cn.chyuan.ai.types..")
            .layer("Domain").definedBy("cn.chyuan.ai.domain..")
            .layer("Api").definedBy("cn.chyuan.ai.api..")
            .layer("Infrastructure").definedBy("cn.chyuan.ai.infrastructure..")
            .layer("Trigger").definedBy("cn.chyuan.ai.trigger..")
            .whereLayer("Types").mayNotAccessAnyLayer()
            .whereLayer("Domain").mayOnlyAccessLayers("Types")
            .whereLayer("Api").mayOnlyAccessLayers("Domain", "Types")
            .whereLayer("Infrastructure").mayOnlyAccessLayers("Domain", "Types")
            .whereLayer("Trigger").mayOnlyAccessLayers("Api", "Infrastructure", "Domain", "Types");

    // 无环粒度到模块一级（(*)）：wrench 树模式在 domain 内部 factory↔node↔父类的
    // 相互引用是模板固有设计，不属跨模块腐化
    @ArchTest
    static final ArchRule 模块包无环 = SlicesRuleDefinition.slices()
            .matching("cn.chyuan.ai.(*)").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule Web入口归位 = noClasses()
            .that().resideOutsideOfPackage("..trigger..")
            .should().beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.Controller");
}
