package com.wx.fbsir.engine.utils.ai;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百度文心一言（yiyan.baidu.com）网页版自动化。
 */
@Component
public class WenxinUtil {

    private static final Logger log = LoggerFactory.getLogger(WenxinUtil.class);

    public static final String WENXIN_HOME_URL = "https://yiyan.baidu.com/";

    private static final Pattern CHAT_PATH_ID = Pattern.compile(
        "yiyan\\.baidu\\.com/(?:[^?#]+/)?chat/([^/?#]+)");

    public String checkLoginStatus(Page page, boolean navigate) {
        if (navigate) {
            try {
                page.navigate(WENXIN_HOME_URL, new Page.NavigateOptions()
                    .setTimeout(30000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(18000));
                page.waitForTimeout(1200);
            } catch (Exception e) {
                log.warn("[文心一言] 导航失败: {}", e.getMessage());
                return "false";
            }
        }

        String url = safeUrl(page);
        if (url.contains("passport.baidu.com") || url.contains("/login")) {
            return "false";
        }

        // 先判断“强登录态”特征，避免页面上存在无关“登录”文案时误判未登录
        String profileHint = tryExtractLoggedInProfileHint(page);
        if (profileHint != null && !profileHint.isEmpty()) {
            return profileHint;
        }
        if (hasStableWorkbenchReady(page)) {
            return "已登录用户";
        }

        if (isPrimaryLoginEntryVisible(page)) {
            log.info("[文心一言] 登录入口可见，判定未登录");
            return "false";
        }

        if (looksLikeYiyanMainUi(page)) {
            return "已登录用户";
        }

        return "false";
    }

    public boolean navigateToLoginPage(Page page) {
        try {
            page.navigate(WENXIN_HOME_URL, new Page.NavigateOptions()
                .setTimeout(30000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(18000));
            page.waitForTimeout(1200);
            return true;
        } catch (Exception e) {
            log.error("[文心一言] 打开首页失败: {}", e.getMessage());
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
            log.warn("[文心一言] 打开登录浮层异常: {}", e.getMessage());
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
                    .setTimeout(35000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            } else {
                String target = "https://yiyan.baidu.com/chat/" + chatId;
                page.navigate(target, new Page.NavigateOptions()
                    .setTimeout(35000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            }
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(18000));
            page.waitForTimeout(1200);
            return true;
        } catch (Exception e) {
            log.error("[文心一言] 导航到会话失败: {}", e.getMessage());
            return false;
        }
    }

    public String extractChatId(Page page) {
        try {
            String url = page.url();
            if (url == null || url.isEmpty()) {
                return null;
            }
            Matcher m = CHAT_PATH_ID.matcher(url);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception e) {
            log.debug("[文心一言] 解析会话ID失败: {}", e.getMessage());
        }
        return null;
    }

    public String sendMessageAndWaitResponse(Page page, String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.waitForTimeout(500);
        try {
            page.evaluate("() => { window.scrollTo(0, document.body.scrollHeight); }");
            page.waitForTimeout(1200);
        } catch (Exception e) {
            log.debug("[文心一言] 滚动页面: {}", e.getMessage());
        }

        if (!fillAndSend(page, query.trim())) {
            throw new RuntimeException("未找到输入框或发送失败");
        }

        return waitForAssistantReply(page);
    }

    private boolean fillAndSend(Page page, String text) {
        Locator input = findPrimaryInput(page);
        if (input == null) {
            return false;
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
                    if (btn.count() > 0 && btn.isVisible(new Locator.IsVisibleOptions().setTimeout(800))) {
                        btn.click(new Locator.ClickOptions().setTimeout(5000));
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
            log.error("[文心一言] 发送消息失败: {}", e.getMessage());
            return false;
        }
    }

    private Locator findPrimaryInput(Page page) {
        String[] selectors = {
            "textarea[placeholder*='文心']",
            "textarea[placeholder*='输入']",
            "textarea[placeholder*='问题']",
            "textarea[placeholder*='说']",
            "textarea[placeholder*='消息']",
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

    private String waitForAssistantReply(Page page) {
        long start = System.currentTimeMillis();
        // 烟测链路默认等待窗口约120秒，这里控制在其内给出结果，避免长时间“生成中”导致前端超时。
        long maxWait = 110000;
        String last = "";
        int stable = 0;
        long lastChangedAt = start;
        page.waitForTimeout(1500);

        while (System.currentTimeMillis() - start < maxWait) {
            if (page.isClosed()) {
                throw new RuntimeException("页面已关闭");
            }

            String text = extractLatestAssistantText(page);
            if (text != null && !text.trim().isEmpty()) {
                if (text.equals(last)) {
                    stable++;
                    long unchangedMs = System.currentTimeMillis() - lastChangedAt;
                    // 若文本长时间不再变化，即使页面仍显示“生成中”，也先返回当前可读正文。
                    if (stable >= 3 && !isGenerating(page)) {
                        return text.trim();
                    }
                    if (unchangedMs >= 12000 && text.trim().length() >= 8) {
                        return text.trim();
                    }
                } else {
                    stable = 0;
                    last = text;
                    lastChangedAt = System.currentTimeMillis();
                }
            }
            page.waitForTimeout(500);
        }

        String fallback = extractLatestAssistantText(page);
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }
        return "文心一言超时未返回可读正文，请在浏览器中查看页面";
    }

    private boolean isGenerating(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const t = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (t.includes('停止生成') || t.includes('停止回答') || t.includes('思考中') || t.includes('生成中')) return true;
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
            log.debug("[文心一言] 提取正文失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean clickPrimaryLoginButton(Page page) {
        String[] selectors = {
            "header button:has-text('登录')",
            "button:has-text('登录')",
            "button:has-text('登入')",
            "[role='button']:has-text('登录')",
            "[role='button']:has-text('登入')",
            "a:has-text('登录')"
        };
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible(new Locator.IsVisibleOptions().setTimeout(1500))) {
                    loc.click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                    return true;
                }
            } catch (Exception e) {
                log.debug("[文心一言] 点击登录失败 {}: {}", sel, e.getMessage());
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
                    if (t !== '登录' && t !== '登录/注册' && t !== '登入') continue;
                    const r = el.getBoundingClientRect();
                    const st = window.getComputedStyle(el);
                    if (r.width < 36 || r.height < 20 || st.display === 'none' || st.visibility === 'hidden') continue;
                    cands.push(r);
                  }
                  if (cands.length === 0) return false;
                  cands.sort((a,b) => (b.right - a.right) || (a.top - b.top));
                  const top = cands[0];
                  return top.top < 220 && top.right > window.innerWidth * 0.65;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isLoginLayerPresent(Page page) {
        return isLoginLayerVisible(page);
    }

    /**
     * 扫码场景兜底：当已进入工作台且登录弹层消失时，判定登录完成。
     */
    public boolean hasStableWorkbenchReady(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (body.includes('扫码登录') || body.includes('手机号登录') || body.includes('验证码登录')) return false;
                  const hasEditor = !!document.querySelector('textarea, div[contenteditable="true"]');
                  if (!hasEditor) return false;
                  const hasWorkbenchText =
                    body.includes('新对话') || body.includes('创意写作') || body.includes('阅读分析') || body.includes('我的收藏');
                  if (!hasWorkbenchText) return false;
                  const loginBtns = Array.from(document.querySelectorAll('button, a, [role="button"]'))
                    .filter(el => {
                      const t = (el.innerText || '').trim();
                      if (t !== '登录' && t !== '登录/注册' && t !== '登入') return false;
                      const r = el.getBoundingClientRect();
                      const st = window.getComputedStyle(el);
                      return r.width >= 36 && r.height >= 20 && st.display !== 'none' && st.visibility !== 'hidden';
                    });
                  return loginBtns.length === 0;
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

    private boolean looksLikeYiyanMainUi(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const t = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (t.includes('文心一言') || t.includes('ERNIE') || t.includes('百度')) {
                    if (document.querySelector('textarea, div[contenteditable="true"]')) return true;
                  }
                  return false;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private static String safeUrl(Page page) {
        try {
            String u = page.url();
            return u != null ? u : "";
        } catch (Exception e) {
            return "";
        }
    }
}
