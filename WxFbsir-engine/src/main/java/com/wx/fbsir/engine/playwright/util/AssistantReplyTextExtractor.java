package com.wx.fbsir.engine.playwright.util;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

/**
 * 从常见「对话」类 DOM 中只取<strong>模型侧</strong>最新一条可见正文，避免误把用户气泡或 Composer 当成回复。
 * <p>
 * 同时提供带兜底的文本输入，缓解部分站点对 {@link Locator#fill(String)} 与 React/CE 组合不佳导致的异常字符。
 */
public final class AssistantReplyTextExtractor {

    private AssistantReplyTextExtractor() {
    }

    public static String extractLatestAssistantPlainText(Page page) {
        return extractLatestAssistantPlainText(page, null);
    }

    /**
     * @param userQuery 本回合发送的用户原文；若提供则用于排除与提问几乎完全一致的气泡（多属用户侧）。
     */
    public static String extractLatestAssistantPlainText(Page page, String userQuery) {
        if (page == null) {
            return "";
        }
        try {
            Object arg = userQuery != null ? userQuery : "";
            Object o = page.evaluate(JS_EXTRACT_WITH_USER_QUERY, arg);
            if (o == null) {
                return "";
            }
            String s = o instanceof String ? (String) o : String.valueOf(o);
            return s != null ? s.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 组合域输入：纯英文可走 {@link Locator#fill(String)}。
     * 含中文时部分站点对 fill/逐字输入在 contenteditable 下仍会乱码，优先用 DOM 直接写入 UTF-16 并派发 input。
     */
    public static void fillComposerUtf8(Locator input, String text) {
        if (input == null || text == null) {
            return;
        }
        input.click();
        input.fill("");
        boolean hasNonAscii = text.chars().anyMatch(ch -> ch > 127);
        if (hasNonAscii) {
            try {
                input.evaluate(JS_FILL_DOM_UTF, text);
                return;
            } catch (Exception e1) {
                try {
                    input.pressSequentially(text, new Locator.PressSequentiallyOptions().setDelay(4));
                } catch (Exception e2) {
                    input.fill(text);
                }
                return;
            }
        }
        try {
            input.fill(text);
        } catch (Exception first) {
            input.fill("");
            try {
                input.evaluate(JS_FILL_DOM_UTF, text);
            } catch (Exception e2) {
                input.pressSequentially(text, new Locator.PressSequentiallyOptions().setDelay(3));
            }
        }
    }

    private static final String JS_FILL_DOM_UTF = """
        (el, val) => {
          if (!el || val === undefined || val === null) return;
          const v = String(val);
          const tag = (el.tagName || '').toLowerCase();
          el.focus();
          if (tag === 'textarea' || tag === 'input') {
            el.value = '';
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.value = v;
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.dispatchEvent(new Event('change', { bubbles: true }));
          } else {
            el.textContent = '';
            el.dispatchEvent(new Event('input', { bubbles: true }));
            el.textContent = v;
            el.dispatchEvent(new InputEvent('input', { bubbles: true, data: v, inputType: 'insertText' }));
          }
        }
        """;

    /**
     * userQuery：用于排除与用户提问前缀高度重合的气泡（判为用户消息）。
     */
    private static final String JS_EXTRACT_WITH_USER_QUERY = """
        (userQuery) => {
          const uq = (userQuery || '').replace(/\\s+/g, ' ').trim();
          const textLen = (s) => (s || '').trim().length;

          const looksLikeUserEcho = (t) => {
            if (!uq || !t) return false;
            const tt = t.replace(/\\s+/g, ' ').trim();
            if (tt.length < 40 || uq.length < 40) return false;
            const n = Math.min(120, uq.length, tt.length);
            if (n < 25) return false;
            let match = 0;
            for (let i = 0; i < n; i++) {
              if (uq[i] === tt[i]) match++;
            }
            return (match / n) > 0.9;
          };

          const pickLastNonUser = (texts) => {
             if (!texts || !texts.length) return '';
             for (let i = texts.length - 1; i >= 0; i--) {
               const t = texts[i].trim();
               if (!looksLikeUserEcho(t)) return t;
             }
             return texts[texts.length - 1].trim();
          };

          const pushIfAssistant = (el, arr) => {
            if (!el) return;
            let t = (el.innerText || '').trim();
            if (textLen(t) < 8) return;
            const role = (el.getAttribute('data-role') || el.getAttribute('data-message-role') || '').toLowerCase();
            const cls = ((el.className != null) ? el.className.toString() : '').toLowerCase();
            if (role === 'user' || role === 'human') return;
            if (cls.includes('user-message') || cls.includes('human-message')) return;
            if (role === 'assistant' || role === 'bot' || role === 'model' || role === 'ai'
                || cls.includes('assistant') || cls.includes('bot-reply') || cls.includes('model-view')) {
              arr.push(t);
            }
          };

          const strong = [];
          document.querySelectorAll(
            '[data-role="assistant"],[data-message-role="assistant"],[data-testid*="assistant"],[class*="assistant-message"],[class*="AssistantMessage"]'
          ).forEach(el => pushIfAssistant(el, strong));
          if (strong.length) return pickLastNonUser(strong);

          const byParentRole = [];
          document.querySelectorAll('[class*="message"],[class*="Message"],[class*="msg-item"]').forEach(el => {
            let p = el;
            for (let i = 0; i < 8 && p; i++, p = p.parentElement) {
              const pr = (p.getAttribute && p.getAttribute('data-role')) || '';
              if (pr.toLowerCase() === 'assistant') {
                const t = (el.innerText || '').trim();
                if (textLen(t) > 8) byParentRole.push(t);
                return;
              }
            }
          });
          if (byParentRole.length) return pickLastNonUser(byParentRole);

          const vague = [];
          document.querySelectorAll('[class*="message"],[class*="Message"]').forEach(el => {
            const role = (el.getAttribute('data-role') || el.getAttribute('data-message-role') || '').toLowerCase();
            if (role === 'user' || role === 'human') return;
            const t = (el.innerText || '').trim();
            if (textLen(t) > 12) vague.push(t);
          });
          if (vague.length) return pickLastNonUser(vague);

          let best = '';
          document.querySelectorAll('article,[class*="markdown"],[class*="Markdown"],[class*="prose"]').forEach(el => {
            const t = (el.innerText || '').trim();
            if (textLen(t) > textLen(best)) best = t;
          });
          return best || '';
        }
        """;
}
