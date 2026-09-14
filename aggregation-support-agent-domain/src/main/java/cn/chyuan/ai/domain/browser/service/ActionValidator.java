package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.model.valobj.ActionErrorVO;
import cn.chyuan.ai.domain.browser.model.valobj.BrowserActionVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作校验器（工单 0339 AQ1，browser-use 动作空间思想）。
 * 动作类型判别/必填参数校验/取值范围限幅/url scheme 白名单，
 * 错误清单带字段路径。domain 纯函数。
 */
public class ActionValidator {

    private static final int MAX_SCROLL = 10000;
    private static final int MAX_WAIT_MS = 30000;

    /** 校验单动作（合法返回空清单） */
    public List<ActionErrorVO> validate(BrowserActionVO action) {
        List<ActionErrorVO> errors = new ArrayList<>();
        if (action == null || action.getType() == null) {
            errors.add(err("type", "TYPE_UNKNOWN"));
            return errors;
        }
        switch (action.getType()) {
            case BrowserActionVO.NAVIGATE -> {
                if (action.getUrl() == null || action.getUrl().isBlank()) {
                    errors.add(err("url", "REQUIRED_MISSING"));
                } else if (!allowedScheme(action.getUrl())) {
                    errors.add(err("url", "SCHEME_REJECTED"));
                }
            }
            case BrowserActionVO.CLICK -> requireSelector(action, errors);
            case BrowserActionVO.TYPE -> {
                requireSelector(action, errors);
                if (action.getText() == null) {
                    errors.add(err("text", "REQUIRED_MISSING"));
                }
            }
            case BrowserActionVO.SCROLL -> {
                requireSelector(action, errors);
                if (action.getDirection() == null
                        || !List.of("up", "down").contains(action.getDirection())) {
                    errors.add(err("direction", "REQUIRED_MISSING"));
                }
                if (action.getAmount() == null || action.getAmount() < 0 || action.getAmount() > MAX_SCROLL) {
                    errors.add(err("amount", "RANGE_VIOLATION"));
                }
            }
            case BrowserActionVO.EXTRACT -> {
                if (action.getFields() == null || action.getFields().isEmpty()) {
                    errors.add(err("fields", "REQUIRED_MISSING"));
                }
            }
            case BrowserActionVO.WAIT -> {
                if (action.getWaitMs() == null || action.getWaitMs() < 0 || action.getWaitMs() > MAX_WAIT_MS) {
                    errors.add(err("waitMs", "RANGE_VIOLATION"));
                }
            }
            default -> errors.add(err("type", "TYPE_UNKNOWN"));
        }
        return errors;
    }

    /** 批量校验（任一非法即整批拒绝） */
    public List<ActionErrorVO> validateAll(List<BrowserActionVO> actions) {
        List<ActionErrorVO> errors = new ArrayList<>();
        if (actions == null) {
            errors.add(err("actions", "REQUIRED_MISSING"));
            return errors;
        }
        for (int i = 0; i < actions.size(); i++) {
            for (ActionErrorVO error : validate(actions.get(i))) {
                errors.add(ActionErrorVO.builder()
                        .field("[" + i + "]." + error.getField())
                        .code(error.getCode())
                        .build());
            }
        }
        return errors;
    }

    private void requireSelector(BrowserActionVO action, List<ActionErrorVO> errors) {
        if (action.getSelector() == null || action.getSelector().isBlank()) {
            errors.add(err("selector", "REQUIRED_MISSING"));
        }
    }

    private boolean allowedScheme(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private ActionErrorVO err(String field, String code) {
        return ActionErrorVO.builder().field(field).code(code).build();
    }
}
