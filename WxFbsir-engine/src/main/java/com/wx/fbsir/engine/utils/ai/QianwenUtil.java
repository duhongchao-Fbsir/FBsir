package com.wx.fbsir.engine.utils.ai;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.wx.fbsir.engine.utils.common.FileDownloadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 千问（Qianwen）网页版自动化工具。
 */
@Component
public class QianwenUtil {

    private static final Logger log = LoggerFactory.getLogger(QianwenUtil.class);

    @Autowired
    private FileDownloadUtil fileDownloadUtil;

    public static final String QIANWEN_HOME_URL = "https://www.qianwen.com/";

    private static final Pattern CHAT_ID_IN_URL = Pattern.compile(
        "qianwen\\.com/(?:chat/|c/|conversation/)([A-Za-z0-9_-]{8,})");

    public String checkLoginStatus(Page page, boolean navigate) {
        if (navigate) {
            try {
                page.navigate(QIANWEN_HOME_URL, new Page.NavigateOptions()
                    .setTimeout(25000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(15000));
                page.waitForTimeout(1200);
            } catch (Exception e) {
                log.warn("[Qianwen] 导航失败: {}", e.getMessage());
                return "false";
            }
        }

        if (isExplicitLoginPage(page)) {
            log.info("[Qianwen] 当前为独立登录页或未登录态");
            return "false";
        }

        if (isPrimaryLoginEntryVisible(page)) {
            log.info("[Qianwen] 右上角登录入口可见，判定未登录");
            return "false";
        }

        String profileHint = tryExtractLoggedInProfileHint(page);
        if (profileHint != null && !profileHint.isEmpty()) {
            return profileHint;
        }

        return "已登录用户";
    }

    public boolean navigateToLoginPage(Page page) {
        try {
            page.navigate(QIANWEN_HOME_URL, new Page.NavigateOptions()
                .setTimeout(25000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(15000));
            page.waitForTimeout(1200);
            return true;
        } catch (Exception e) {
            log.error("[Qianwen] 打开登录页失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean navigateToLoginPageAndOpenLoginLayer(Page page) {
        if (!navigateToLoginPage(page)) {
            return false;
        }
        return openLoginLayerForQrOrPhone(page);
    }

    public boolean openLoginLayerForQrOrPhone(Page page) {
        try {
            page.waitForTimeout(500);
            clickPrimaryLoginButton(page);
            page.waitForTimeout(900);
            waitForLoginLayerContent(page, 8000);
            return true;
        } catch (Exception e) {
            log.warn("[Qianwen] 打开登录浮层异常: {}", e.getMessage());
            return true;
        }
    }

    public boolean navigateToChat(Page page, String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            return navigateToLoginPage(page);
        }

        try {
            if (chatId.startsWith("http://") || chatId.startsWith("https://")) {
                page.navigate(chatId, new Page.NavigateOptions()
                    .setTimeout(30000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            } else {
                String target = "https://www.qianwen.com/chat/" + chatId;
                page.navigate(target, new Page.NavigateOptions()
                    .setTimeout(30000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            }
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(15000));
            page.waitForTimeout(1200);
            return true;
        } catch (Exception e) {
            log.error("[Qianwen] 导航到会话失败: {}", e.getMessage());
            return false;
        }
    }

    public String extractChatId(Page page) {
        try {
            String url = page.url();
            if (url == null || url.isEmpty()) {
                return null;
            }
            Matcher m = CHAT_ID_IN_URL.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception e) {
            log.debug("[Qianwen] 解析会话ID失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 上传文件到千问输入区，优先走对话输入区定向上传，再降级到DOM通用上传。
     */
    public boolean uploadFile(Page page, String localFilePath) {
        if (fileDownloadUtil.uploadComposerAreaFile(page, localFilePath, "[千问文件上传]")) {
            return true;
        }
        return fileDownloadUtil.uploadViaDOM(page, localFilePath, new String[]{
            "button:has-text('上传文件')",
            "button:has-text('上传')",
            "[role='button']:has-text('上传')",
            "button:has-text('附件')",
            "button:has-text('本地上传')",
            "[aria-label*='上传']",
            "[class*='upload']",
            "[class*='attach']"
        });
    }

    public String sendMessageAndWaitResponse(Page page, String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.waitForTimeout(500);
        String text = query.trim();
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                prepareChatWorkspace(page);
                if (fillAndSend(page, text)) {
                    return waitForAssistantReply(page);
                }
            } catch (Exception e) {
                log.debug("[Qianwen] 第{}次发送尝试异常: {}", attempt, e.getMessage());
            }
            page.waitForTimeout(1200L * attempt);
        }
        throw new RuntimeException("未找到输入框或发送失败");
    }

    private boolean fillAndSend(Page page, String text) {
        Locator input = findPrimaryInput(page);
        if (input == null) {
            return fillAndSendViaDom(page, text) || fillAndSendViaFrames(page, text);
        }
        try {
            input.click();
            page.waitForTimeout(200);
            input.fill("");
            input.fill(text);
            page.waitForTimeout(200);

            String[] sendSelectors = {
                "button:has-text('发送')",
                "div[role='button']:has-text('发送')",
                "button[aria-label*='发送']",
                "[class*='send']:not([disabled])",
                "button[type='submit']"
            };
            for (String sel : sendSelectors) {
                try {
                    Locator btn = page.locator(sel).first();
                    if (btn.count() > 0 && btn.isVisible(new Locator.IsVisibleOptions().setTimeout(600))) {
                        btn.click(new Locator.ClickOptions().setTimeout(4000));
                        page.waitForTimeout(600);
                        return true;
                    }
                } catch (Exception ignore) {
                    // try next
                }
            }
            input.press("Enter");
            page.waitForTimeout(600);
            return true;
        } catch (Exception e) {
            log.warn("[Qianwen] 常规输入发送失败，尝试DOM兜底: {}", e.getMessage());
            return fillAndSendViaDom(page, text) || fillAndSendViaFrames(page, text);
        }
    }

    private Locator findPrimaryInput(Page page) {
        String[] selectors = {
            "textarea[placeholder*='千问']",
            "textarea[placeholder*='消息']",
            "textarea[placeholder*='对话']",
            "textarea[placeholder*='发']",
            "textarea[placeholder*='输入']",
            "textarea[placeholder*='问']",
            "textarea[aria-label*='输入']",
            "textarea[data-testid*='input']",
            "[class*='input'] textarea",
            "[role='textbox'] textarea",
            "[role='textbox']",
            "div[data-slate-editor='true']",
            "div[contenteditable='plaintext-only']",
            "div[aria-label*='输入'][contenteditable='true']",
            "div[contenteditable='true'][role='textbox']",
            "div[contenteditable='true']",
            "textarea"
        };
        for (String sel : selectors) {
            try {
                Locator all = page.locator(sel);
                int n = all.count();
                for (int i = n - 1; i >= 0; i--) {
                    Locator loc = all.nth(i);
                    if (loc.isVisible(new Locator.IsVisibleOptions().setTimeout(2000))) {
                        try {
                            loc.scrollIntoViewIfNeeded();
                        } catch (Exception ignore) {
                            // ignore
                        }
                        return loc;
                    }
                }
            } catch (Exception ignore) {
                // try next selector
            }
        }
        return null;
    }

    /**
     * 千问页面经常出现动态渲染/隐藏输入区，Playwright定位偶发拿不到可操作输入框。
     * 兜底策略：直接在DOM中挑选最后一个可见编辑区，写入文本并触发发送。
     */
    private boolean fillAndSendViaDom(Page page, String text) {
        try {
            Object ok = page.evaluate("""
                (value) => {
                  const isVisible = (el) => {
                    if (!el) return false;
                    const st = window.getComputedStyle(el);
                    const r = el.getBoundingClientRect();
                    return r.width > 40 && r.height > 20 && st.display !== 'none' && st.visibility !== 'hidden';
                  };
                  const pickTarget = () => {
                    const sels = [
                      'textarea:not([disabled])',
                      '[role="textbox"] textarea:not([disabled])',
                      'div[contenteditable="true"]',
                      'div[contenteditable="plaintext-only"]',
                      '[role="textbox"][contenteditable="true"]',
                      '[role="textbox"]'
                    ];
                    const list = [];
                    for (const sel of sels) {
                      document.querySelectorAll(sel).forEach(el => {
                        if (isVisible(el)) list.push(el);
                      });
                    }
                    return list.length ? list[list.length - 1] : null;
                  };

                  const target = pickTarget();
                  if (!target) return false;
                  target.scrollIntoView({block: 'center', inline: 'nearest'});
                  target.focus();

                  const tag = (target.tagName || '').toLowerCase();
                  if (tag === 'textarea' || tag === 'input') {
                    target.value = '';
                    target.dispatchEvent(new Event('input', { bubbles: true }));
                    target.value = value;
                    target.dispatchEvent(new Event('input', { bubbles: true }));
                    target.dispatchEvent(new Event('change', { bubbles: true }));
                  } else {
                    target.textContent = '';
                    target.dispatchEvent(new Event('input', { bubbles: true }));
                    target.textContent = value;
                    target.dispatchEvent(new Event('input', { bubbles: true }));
                  }

                  const sendSelectors = [
                    'button[type="submit"]',
                    'button:enabled:has(svg)',
                    'button[aria-label*="发送"]:not([disabled])',
                    '[role="button"][aria-label*="发送"]',
                    '[class*="send"]:not([disabled])'
                  ];
                  for (const sel of sendSelectors) {
                    const btn = document.querySelector(sel);
                    if (!btn || !isVisible(btn) || btn.disabled) continue;
                    btn.click();
                    return true;
                  }

                  const evtInit = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true };
                  target.dispatchEvent(new KeyboardEvent('keydown', evtInit));
                  target.dispatchEvent(new KeyboardEvent('keypress', evtInit));
                  target.dispatchEvent(new KeyboardEvent('keyup', evtInit));
                  return true;
                }
                """, text);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            log.error("[Qianwen] DOM兜底发送失败: {}", e.getMessage());
            return false;
        }
    }

    private boolean fillAndSendViaFrames(Page page, String text) {
        try {
            for (Frame frame : page.frames()) {
                if (frame == null || frame.equals(page.mainFrame())) {
                    continue;
                }
                if (fillAndSendInFrame(frame, text)) {
                    log.info("[Qianwen] 已通过 iframe 输入区发送");
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[Qianwen] iframe 兜底发送异常: {}", e.getMessage());
        }
        return false;
    }

    private void prepareChatWorkspace(Page page) {
        try {
            page.evaluate("""
                () => {
                  const txt = ['开始对话', '新建对话', '继续对话', '立即体验', '进入对话', '开始使用', '我知道了', '知道了', '关闭'];
                  const isVisible = (el) => {
                    const st = window.getComputedStyle(el);
                    const r = el.getBoundingClientRect();
                    return r.width > 16 && r.height > 16 && st.display !== 'none' && st.visibility !== 'hidden';
                  };
                  for (const el of document.querySelectorAll('button, a, [role="button"], span, div')) {
                    const t = (el.innerText || '').trim();
                    if (!t || !isVisible(el)) continue;
                    if (txt.some(k => t.includes(k))) {
                      try { el.click(); } catch (_) {}
                    }
                  }
                  window.scrollTo(0, document.body.scrollHeight);
                }
                """);
            page.waitForTimeout(500);
        } catch (Exception e) {
            log.debug("[Qianwen] 激活会话区失败: {}", e.getMessage());
        }
    }

    private boolean fillAndSendInFrame(Frame frame, String text) {
        String[] selectors = {
            "textarea[placeholder*='千问']",
            "textarea[placeholder*='消息']",
            "textarea[placeholder*='输入']",
            "textarea[aria-label*='输入']",
            "div[contenteditable='true'][role='textbox']",
            "div[contenteditable='true']",
            "textarea"
        };
        for (String sel : selectors) {
            try {
                Locator all = frame.locator(sel);
                int n = all.count();
                for (int i = n - 1; i >= 0; i--) {
                    Locator loc = all.nth(i);
                    if (!loc.isVisible(new Locator.IsVisibleOptions().setTimeout(1000))) {
                        continue;
                    }
                    try {
                        loc.scrollIntoViewIfNeeded();
                    } catch (Exception ignore) {
                        // ignore
                    }
                    loc.click();
                    frame.page().waitForTimeout(150);
                    loc.fill("");
                    loc.fill(text);
                    frame.page().waitForTimeout(150);

                    String[] sendSelectors = {
                        "button:has-text('发送')",
                        "div[role='button']:has-text('发送')",
                        "button[aria-label*='发送']",
                        "[class*='send']:not([disabled])",
                        "button[type='submit']"
                    };
                    for (String s : sendSelectors) {
                        try {
                            Locator btn = frame.locator(s).first();
                            if (btn.count() > 0 && btn.isVisible(new Locator.IsVisibleOptions().setTimeout(700))) {
                                btn.click();
                                frame.page().waitForTimeout(500);
                                return true;
                            }
                        } catch (Exception ignore) {
                            // try next
                        }
                    }
                    loc.press("Enter");
                    frame.page().waitForTimeout(500);
                    return true;
                }
            } catch (Exception ignore) {
                // try next selector
            }
        }
        return false;
    }

    private String waitForAssistantReply(Page page) {
        long start = System.currentTimeMillis();
        long maxWait = 300000;
        String last = "";
        int stable = 0;
        page.waitForTimeout(1500);

        while (System.currentTimeMillis() - start < maxWait) {
            if (page.isClosed()) {
                throw new RuntimeException("页面已关闭");
            }
            if (isGenerating(page)) {
                stable = 0;
                page.waitForTimeout(500);
                continue;
            }

            String text = extractLatestAssistantText(page);
            if (text != null && !text.trim().isEmpty()) {
                if (text.equals(last)) {
                    stable++;
                    if (stable >= 3) {
                        return text.trim();
                    }
                } else {
                    stable = 0;
                    last = text;
                }
            }
            page.waitForTimeout(500);
        }

        String fallback = extractLatestAssistantText(page);
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }
        return "千问超时未返回可读正文，请手动在浏览器中查看页面";
    }

    private boolean isGenerating(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const t = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (t.includes('停止生成') || t.includes('停止回答') || t.includes('思考中')) return true;
                  for (const b of document.querySelectorAll('button, [role="button"]')) {
                    const s = (b.innerText || '').trim();
                    if (s === '停止' || s.includes('停止生成')) return true;
                  }
                  return false;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private String extractLatestAssistantText(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const candidates = [];
                  document.querySelectorAll('[class*="message"], [class*="Message"], [data-role="assistant"]').forEach(el => {
                    const t = (el.innerText || '').trim();
                    if (t.length > 5) candidates.push(t);
                  });
                  if (candidates.length > 0) return candidates[candidates.length - 1];
                  let best = '';
                  document.querySelectorAll('article, [class*="markdown"], [class*="Markdown"]').forEach(el => {
                    const t = (el.innerText || '').trim();
                    if (t.length > best.length) best = t;
                  });
                  return best || '';
                }
                """);
            return o != null ? o.toString() : null;
        } catch (Exception e) {
            log.debug("[Qianwen] 提取正文失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean clickPrimaryLoginButton(Page page) {
        String[] selectors = {
            "header button:has-text('登录')",
            "button:has-text('登录')",
            "[role='button']:has-text('登录')",
            "a:has-text('登录')"
        };
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible(new Locator.IsVisibleOptions().setTimeout(1200))) {
                    loc.click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                    return true;
                }
            } catch (Exception e) {
                log.debug("[Qianwen] 点击登录失败 {}: {}", sel, e.getMessage());
            }
        }
        return false;
    }

    private void waitForLoginLayerContent(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isLoginLayerVisible(page)) {
                return;
            }
            try {
                page.waitForTimeout(300);
            } catch (Exception e) {
                return;
            }
        }
    }

    private boolean isPrimaryLoginEntryVisible(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const cands = [];
                  for (const el of document.querySelectorAll('button, a, [role="button"]')) {
                    const t = (el.innerText || '').trim();
                    if (t !== '登录' && t !== '登录/注册') continue;
                    const r = el.getBoundingClientRect();
                    const st = window.getComputedStyle(el);
                    if (r.width < 4 || r.height < 4 || st.display === 'none' || st.visibility === 'hidden') continue;
                    cands.push(r);
                  }
                  if (cands.length === 0) return false;
                  cands.sort((a,b) => (b.right - a.right) || (a.top - b.top));
                  const top = cands[0];
                  return top.top < 220 && top.right > window.innerWidth * 0.4;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLoginLayerVisible(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (body.includes('扫码登录') || body.includes('手机号登录') || body.includes('验证码登录')) return true;
                  const ifr = document.querySelector('iframe[src*="login"], iframe[src*="passport"], iframe[src*="qr"]');
                  if (ifr && ifr.offsetParent !== null) return true;
                  const qr = document.querySelector('canvas, img[alt*="二维码"], img[src*="qr"], img[src*="QR"]');
                  if (qr) {
                    const r = qr.getBoundingClientRect();
                    if (r.width > 40 && r.height > 40) return true;
                  }
                  return false;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private String tryExtractLoggedInProfileHint(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const tryText = (sel) => {
                    const el = document.querySelector(sel);
                    if (!el) return '';
                    const t = (el.innerText || el.getAttribute('title') || '').trim();
                    return t.length > 0 && t.length < 48 ? t : '';
                  };
                  let s = tryText('[class*="userName"], [class*="nickname"], [class*="UserName"]');
                  if (s) return s;
                  s = tryText('[class*="avatar"][title]');
                  if (s) return s;
                  return '';
                }
                """);
            if (o == null) {
                return null;
            }
            String t = o.toString().trim();
            return t.isEmpty() ? null : t;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isExplicitLoginPage(Page page) {
        String url = page.url();
        if (url != null && (url.contains("/login") || url.contains("passport"))) {
            return true;
        }
        try {
            Object o = page.evaluate("""
                () => {
                  const t = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (t.includes('手机号登录') || t.includes('验证码登录')) return true;
                  return !!document.querySelector('[class*="login"], [class*="Login"] input[type="tel"]');
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }
}
