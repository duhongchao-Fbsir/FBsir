package com.wx.fbsir.engine.controller.qyweixin.support;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「设置变量」DOM 探测脚本的契约测试：结构关键子串 + 可选无头页 evaluate。
 */
class QyWeixinSetVariableDomProbeTest {

    @Test
    void click_script_contains_modal_and_add_logic() {
        String js = QyWeixinSetVariableDomProbe.jsClickAddInSetVariableModal();
        assertAll(
                () -> assertTrue(js.contains("设置变量"), "须识别弹层标题"),
                () -> assertTrue(js.contains("输入变量") || js.contains("变量列表"), "须识别变量区"),
                () -> assertTrue(js.contains("添加"), "须匹配添加控件"),
                () -> assertTrue(js.contains("getBoundingClientRect"), "须做可见性判断")
        );
    }

    @Test
    void fill_script_uses_react_friendly_input_setter() {
        String js = QyWeixinSetVariableDomProbe.jsFillFirstVariableNameField();
        assertAll(
                () -> assertTrue(js.contains("HTMLInputElement")),
                () -> assertTrue(js.contains("dispatchEvent")),
                () -> assertTrue(js.contains("contenteditable")),
                () -> assertTrue(js.contains("设置变量"))
        );
    }

    /**
     * 在极简 DOM 上跑通「填最后一个空 input」；需本机已安装 Chromium（Playwright）。
     * 设置环境变量 RUN_QYWX_DOM_PROBE=1 才执行，避免未装浏览器时 CI 必红。
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_QYWX_DOM_PROBE", matches = "1")
    void fill_script_finds_empty_input_in_minimal_modal_dom() {
        try (Playwright pw = Playwright.create();
                Browser browser = pw.chromium().launch()) {
            Page page = browser.newPage();
            page.setContent(
                    "<!DOCTYPE html><html><body>"
                            + "<div role=\"dialog\" style=\"padding:12px\">"
                            + "<div>设置变量</div>"
                            + "<section>输入变量</section>"
                            + "<p>变量列表</p>"
                            + "<input type=\"text\" placeholder=\"ignore\" value=\"x\"/>"
                            + "<input type=\"text\" value=\"\"/>"
                            + "</div></body></html>"
            );
            Object ok = page.evaluate(QyWeixinSetVariableDomProbe.jsFillFirstVariableNameField(), "probe_var");
            assertTrue(Boolean.TRUE.equals(ok), "应为最后一个空 input 赋占位值");
            String last = (String) page.evaluate(
                    "() => document.querySelectorAll('div[role=dialog] input')[1].value");
            assertNotNull(last);
            assertTrue(last.contains("probe_var") || "probe_var".equals(last));
        }
    }
}
