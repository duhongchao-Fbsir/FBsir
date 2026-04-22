package com.wx.fbsir.engine.playwright.util;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.BoundingBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 企微工作流编辑器 Playwright 可观测性：视口、文本命中、菜单候选等。
 * <p>
 * 用于排查分辨率、遮挡、非 {@code <button>} 文案控件导致「添加指定操作」点不中等问题。
 */
public final class PlaywrightWorkflowEditorDiagnostics {

    private PlaywrightWorkflowEditorDiagnostics() {
    }

    public static Map<String, Object> collectEnvironment(Page page) {
        Map<String, Object> env = new LinkedHashMap<>();
        if (page == null) {
            env.put("error", "page-null");
            return env;
        }
        try {
            env.put("url", page.url());
            env.put("viewport", page.viewportSize());
            @SuppressWarnings("unchecked")
            Map<String, Object> win = (Map<String, Object>) page.evaluate(
                "() => ({" +
                " innerWidth: window.innerWidth," +
                " innerHeight: window.innerHeight," +
                " outerWidth: window.outerWidth," +
                " outerHeight: window.outerHeight," +
                " devicePixelRatio: window.devicePixelRatio || 1," +
                " scrollX: window.scrollX," +
                " scrollY: window.scrollY" +
                "})"
            );
            env.put("window", win != null ? win : Map.of());
        } catch (Exception e) {
            env.put("error", e.getMessage());
        }
        return env;
    }

    /**
     * 对一段文案用多种定位策略统计数量与首个可见元素的边界框（不点击）。
     */
    public static List<Map<String, Object>> probeTextHit(Page page, String text) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (page == null || text == null || text.isBlank()) {
            return rows;
        }
        String t = text.trim();
        String[] strategies = {
            "button:has-text",
            "role=button",
            "anchor",
            "getByText-partial"
        };
        try {
            appendProbeRow(rows, strategies[0], probeLocator(page.locator("button:has-text('" + escapeForSelector(t) + "')")));
            appendProbeRow(rows, strategies[1], probeLocator(page.locator("[role=\"button\"]:has-text('" + escapeForSelector(t) + "')")));
            appendProbeRow(rows, strategies[2], probeLocator(page.locator("a:has-text('" + escapeForSelector(t) + "')")));
            Locator byText = page.getByText(t, new Page.GetByTextOptions().setExact(false));
            appendProbeRow(rows, strategies[3], probeLocator(byText));
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("strategy", "exception");
            err.put("error", e.getMessage());
            rows.add(err);
        }
        return rows;
    }

    private static String escapeForSelector(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static void appendProbeRow(List<Map<String, Object>> rows, String name, Map<String, Object> one) {
        one.put("strategy", name);
        rows.add(one);
    }

    private static Map<String, Object> probeLocator(Locator loc) {
        Map<String, Object> m = new HashMap<>();
        try {
            int n = loc.count();
            m.put("count", n);
            if (n == 0) {
                m.put("firstVisible", false);
                return m;
            }
            Locator first = loc.first();
            boolean vis = first.isVisible();
            m.put("firstVisible", vis);
            BoundingBox box = first.boundingBox();
            if (box != null) {
                m.put("bbox", Map.of(
                    "x", round4(box.x),
                    "y", round4(box.y),
                    "width", round4(box.width),
                    "height", round4(box.height)
                ));
            } else {
                m.put("bbox", null);
            }
        } catch (Exception e) {
            m.put("count", 0);
            m.put("firstVisible", false);
            m.put("probeError", e.getMessage());
        }
        return m;
    }

    private static double round4(double v) {
        return Math.round(v * 10000d) / 10000d;
    }

    /**
     * 页面上与「添加/菜单」相关的可见文本节点采样（通过 JS，限制数量）。
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> sampleClickableTexts(Page page, String contains) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (page == null) {
            return out;
        }
        String sub = contains == null ? "添加" : contains;
        try {
            List<Map<String, Object>> js = (List<Map<String, Object>>) page.evaluate(
                "(sub) => {" +
                " const res = [];" +
                " const vw = window.innerWidth, vh = window.innerHeight;" +
                " const all = document.body.querySelectorAll('button, a, [role=button], [role=menuitem], .t-dropdown__item, .t-menu__item, span, div');" +
                " for (const el of all) {" +
                "  if (res.length >= 40) break;" +
                "  const t = (el.innerText || el.textContent || '').trim();" +
                "  if (!t || t.length > 80 || !t.includes(sub)) continue;" +
                "  const r = el.getBoundingClientRect();" +
                "  if (r.width < 2 || r.height < 2) continue;" +
                "  if (r.bottom < 0 || r.top > vh || r.right < 0 || r.left > vw) continue;" +
                "  const st = window.getComputedStyle(el);" +
                "  if (st.visibility === 'hidden' || st.display === 'none' || Number(st.opacity) === 0) continue;" +
                "  res.push({ tag: el.tagName, cls: (el.className||'').toString().slice(0,120), text: t.slice(0,80), " +
                "    x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height) });" +
                " }" +
                " return res;" +
                "}",
                sub
            );
            if (js != null) {
                out.addAll(js);
            }
        } catch (Exception e) {
            Map<String, Object> err = new HashMap<>();
            err.put("error", e.getMessage());
            out.add(err);
        }
        return out;
    }

    /**
     * 对一组触发文案逐一探测（用于与 JSON 配置对照）。
     */
    public static Map<String, Object> probeTriggerList(Page page, List<String> triggers) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (triggers == null) {
            return map;
        }
        for (String tr : triggers) {
            if (tr == null || tr.isBlank()) {
                continue;
            }
            map.put(tr, probeTextHit(page, tr));
        }
        return map;
    }

    public static Map<String, Object> fullSnapshot(Page page, List<String> panelTriggers) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("environment", collectEnvironment(page));
        root.put("panelTriggers", panelTriggers == null ? List.of() : panelTriggers);
        root.put("triggerProbe", probeTriggerList(page, panelTriggers));
        root.put("samples_add", sampleClickableTexts(page, "添加"));
        root.put("samples_menu", sampleClickableTexts(page, "模型"));
        root.put("validationHintDigest", collectAggregatedValidationHints(page));
        return root;
    }

    /**
     * 聚合编辑器内可见「检查结论」、弹层、常见告警节点文案，供校验提示驱动占位与安全对照。
     * 不做业务点击，仅读 DOM 文本（截断以避免巨页）。
     */
    @SuppressWarnings("unchecked")
    public static String collectAggregatedValidationHints(Page page) {
        if (page == null) {
            return "";
        }
        try {
            String s = (String) page.evaluate(
                "() => {" +
                " const parts = [];" +
                " const push = (el) => { " +
                "   if (!el) return;" +
                "   const t = (el.innerText || '').trim().replace(/\\s+/g, ' ');" +
                "   if (t.length < 2) return;" +
                "   parts.push(t.substring(0, 2000));" +
                " };" +
                " try {" +
                "   document.querySelectorAll('[role=dialog], [role=alertdialog], aside, [class*=Drawer], [class*=drawer], " +
                "       [class*=Inspector], [class*=inspector], [class*=Check], [class*=check-panel]').forEach(push);" +
                " } catch (e) {}" +
                " const merged = parts.join(' | ');" +
                " return merged.substring(0, 12000);" +
                "}"
            );
            return s != null ? s : "";
        } catch (Exception e) {
            return "";
        }
    }
}
