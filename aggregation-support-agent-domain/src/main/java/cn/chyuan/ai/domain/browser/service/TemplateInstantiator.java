package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.model.valobj.BrowserActionVO;
import cn.chyuan.ai.domain.browser.model.valobj.BrowserTaskTemplateVO;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板实例化器（工单 0344 AQ6，puppeteer 录制回放思想）。
 * 参数替换（{{param}} 占位 → 实参）→ 动作序列实例化；未替换占位符残留/
 * 未声明参数一律拒绝。domain 纯函数。
 */
public class TemplateInstantiator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_-]+)}}");

    /** 实例化：参数齐备 → 动作序列（校验器过闸）；缺参/残留占位符拒绝 */
    public Result instantiate(BrowserTaskTemplateVO template, Map<String, String> params) {
        if (template == null || template.getActions() == null || template.getActions().isEmpty()) {
            throw new IllegalArgumentException("模板动作序列不能为空");
        }
        List<String> errors = new ArrayList<>();
        // 占位符声明校验：模板中出现的占位必须已声明
        for (BrowserActionVO action : template.getActions()) {
            for (String placeholder : placeholdersOf(action)) {
                if (template.getParameters() == null || !template.getParameters().containsKey(placeholder)) {
                    errors.add("占位符未声明: {{" + placeholder + "}}");
                }
            }
        }
        // 参数齐备校验
        if (template.getParameters() != null) {
            for (String declared : template.getParameters().keySet()) {
                if (params == null || !params.containsKey(declared) || params.get(declared).isBlank()) {
                    errors.add("缺参数: " + declared);
                }
            }
        }
        if (!errors.isEmpty()) {
            return new Result(null, List.copyOf(errors));
        }
        List<BrowserActionVO> instantiated = new ArrayList<>();
        for (BrowserActionVO action : template.getActions()) {
            instantiated.add(replace(action, params));
        }
        List<String> residual = new ArrayList<>();
        for (BrowserActionVO action : instantiated) {
            residual.addAll(residualPlaceholders(action));
        }
        if (!residual.isEmpty()) {
            return new Result(null, residual.stream().map(r -> "残留占位符: " + r).toList());
        }
        return new Result(instantiated, List.of());
    }

    private BrowserActionVO replace(BrowserActionVO action, Map<String, String> params) {
        return BrowserActionVO.builder()
                .type(action.getType())
                .selector(replaceText(action.getSelector(), params))
                .text(replaceText(action.getText(), params))
                .url(replaceText(action.getUrl(), params))
                .direction(action.getDirection())
                .amount(action.getAmount())
                .fields(action.getFields())
                .waitMs(action.getWaitMs())
                .skippable(action.isSkippable())
                .build();
    }

    private String replaceText(String text, Map<String, String> params) {
        if (text == null) {
            return null;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = params.getOrDefault(matcher.group(1), matcher.group(0));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 模板动作中出现的全部占位符名 */
    List<String> placeholdersOf(BrowserActionVO action) {
        List<String> out = new ArrayList<>();
        collect(action.getSelector(), out);
        collect(action.getText(), out);
        collect(action.getUrl(), out);
        return out;
    }

    /** 实例化后残留的占位符（未替换） */
    List<String> residualPlaceholders(BrowserActionVO action) {
        List<String> out = new ArrayList<>();
        collect(action.getSelector(), out);
        collect(action.getText(), out);
        collect(action.getUrl(), out);
        return out;
    }

    private void collect(String text, List<String> out) {
        if (text == null) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }

    /** 实例化结果：动作序列（失败为 null）+ 错误清单 */
    public record Result(List<BrowserActionVO> actions, List<String> errors) {
    }
}
