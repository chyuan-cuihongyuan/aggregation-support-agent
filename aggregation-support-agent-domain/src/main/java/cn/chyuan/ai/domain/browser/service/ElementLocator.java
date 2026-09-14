package cn.chyuan.ai.domain.browser.service;

import cn.chyuan.ai.domain.browser.model.valobj.ElementVO;
import cn.chyuan.ai.domain.browser.model.valobj.LocateResult;
import cn.chyuan.ai.domain.browser.model.valobj.PageSnapshotVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 元素定位器（工单 0341 AQ3，playwright 定位器语义）。
 * 多策略依序回退：selector 精确 → selector 片段包含 → 文本包含（大小写折叠）
 * → role+name → index 兜底；唯一命中返回，多候选报歧义，零命中报未找到。
 * domain 纯函数。
 */
public class ElementLocator {

    /** 定位：按策略依序回退（strategy 可空=自动依序） */
    public LocateResult locate(PageSnapshotVO snapshot, String selector, String text,
                               String role, String name, Integer index) {
        if (snapshot == null || snapshot.getElements() == null || snapshot.getElements().isEmpty()) {
            return LocateResult.missing();
        }
        // 1. selector 精确
        if (selector != null && !selector.isBlank()) {
            List<ElementVO> exact = byExactSelector(snapshot, selector);
            if (exact.size() == 1) {
                return LocateResult.hit(exact.get(0));
            }
            // 2. selector 片段包含
            List<ElementVO> contains = bySelectorContains(snapshot, selector);
            if (contains.size() == 1) {
                return LocateResult.hit(contains.get(0));
            }
            if (contains.size() > 1) {
                return LocateResult.ambiguous(contains);
            }
        }
        // 3. 文本包含（大小写折叠）
        if (text != null && !text.isBlank()) {
            List<ElementVO> byText = byTextContains(snapshot, text);
            if (byText.size() == 1) {
                return LocateResult.hit(byText.get(0));
            }
            if (byText.size() > 1) {
                return LocateResult.ambiguous(byText);
            }
        }
        // 4. role+name
        if (role != null && !role.isBlank()) {
            List<ElementVO> byRole = byRoleName(snapshot, role, name);
            if (byRole.size() == 1) {
                return LocateResult.hit(byRole.get(0));
            }
            if (byRole.size() > 1) {
                return LocateResult.ambiguous(byRole);
            }
        }
        // 5. index 兜底（越界未找到）
        if (index != null) {
            if (index >= 0 && index < snapshot.getElements().size()) {
                return LocateResult.hit(snapshot.getElements().get(index));
            }
            return LocateResult.missing();
        }
        return LocateResult.missing();
    }

    private List<ElementVO> byExactSelector(PageSnapshotVO snapshot, String selector) {
        List<ElementVO> out = new ArrayList<>();
        for (ElementVO element : snapshot.getElements()) {
            if (selector.equals(element.getSelector())) {
                out.add(element);
            }
        }
        return out;
    }

    private List<ElementVO> bySelectorContains(PageSnapshotVO snapshot, String selector) {
        List<ElementVO> out = new ArrayList<>();
        for (ElementVO element : snapshot.getElements()) {
            if (element.getSelector() != null && element.getSelector().contains(selector)) {
                out.add(element);
            }
        }
        return out;
    }

    private List<ElementVO> byTextContains(PageSnapshotVO snapshot, String text) {
        String needle = text.toLowerCase();
        List<ElementVO> out = new ArrayList<>();
        for (ElementVO element : snapshot.getElements()) {
            if (element.getName() != null && element.getName().toLowerCase().contains(needle)) {
                out.add(element);
            }
        }
        return out;
    }

    private List<ElementVO> byRoleName(PageSnapshotVO snapshot, String role, String name) {
        List<ElementVO> out = new ArrayList<>();
        for (ElementVO element : snapshot.getElements()) {
            boolean roleMatch = role.equalsIgnoreCase(element.getRole());
            boolean nameMatch = name == null || name.isBlank()
                    || (element.getName() != null && element.getName().equalsIgnoreCase(name));
            if (roleMatch && nameMatch) {
                out.add(element);
            }
        }
        return out;
    }
}
