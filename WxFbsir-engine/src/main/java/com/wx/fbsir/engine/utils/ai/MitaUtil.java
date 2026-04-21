package com.wx.fbsir.engine.utils.ai;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.wx.fbsir.engine.playwright.util.AssistantReplyTextExtractor;
import com.wx.fbsir.engine.utils.common.FileDownloadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 秘塔 AI 搜索（metaso.cn）网页版自动化。
 */
@Component
public class MitaUtil {

    private static final Logger log = LoggerFactory.getLogger(MitaUtil.class);

    @Autowired
    private FileDownloadUtil fileDownloadUtil;

    public static final String METASO_HOME_URL = "https://metaso.cn/";

    public String checkLoginStatus(Page page, boolean navigate) {
        if (navigate) {
            try {
                page.navigate(METASO_HOME_URL, new Page.NavigateOptions()
                    .setTimeout(30000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(18000));
                page.waitForTimeout(1200);
            } catch (Exception e) {
                log.warn("[秘塔] 导航失败: {}", e.getMessage());
                return "false";
            }
        }

        if (isExplicitLoginPage(page)) {
            return "false";
        }

        String profileHint = tryExtractLoggedInProfileHint(page);
        if (profileHint != null && !profileHint.isEmpty()) {
            return profileHint;
        }

        if (hasStableWorkbenchReady(page)) {
            return "已登录用户";
        }

        if (hasMainSearchUi(page)) {
            return "已登录用户";
        }

        if (isPrimaryLoginEntryVisible(page)) {
            // 秘塔部分页面会同时出现“登录”文案与可用搜索输入区，直接判未登录会误伤。
            // 若已存在主搜索工作区且未出现登录弹层，按已登录处理。
            if (!isLoginLayerVisible(page) && hasMainSearchUi(page)) {
                return "已登录用户";
            }
            log.info("[秘塔] 登录入口可见，判定未登录");
            return "false";
        }

        return "false";
    }

    public boolean navigateToLoginPage(Page page) {
        try {
            page.navigate(METASO_HOME_URL, new Page.NavigateOptions()
                .setTimeout(30000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(18000));
            page.waitForTimeout(1200);
            return true;
        } catch (Exception e) {
            log.error("[秘塔] 打开首页失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean navigateToLoginPageAndOpenLoginLayer(Page page) {
        if (!navigateToLoginPage(page)) {
            return false;
        }
        try {
            page.waitForTimeout(500);
            clickPrimaryLoginButton(page);
            page.waitForTimeout(900);
            waitForLoginLayerContent(page, 8000);
            return true;
        } catch (Exception e) {
            log.warn("[秘塔] 打开登录浮层异常: {}", e.getMessage());
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
                String path = chatId.startsWith("/") ? chatId.substring(1) : chatId;
                page.navigate("https://metaso.cn/" + path, new Page.NavigateOptions()
                    .setTimeout(35000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            }
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(18000));
            page.waitForTimeout(1200);
            return true;
        } catch (Exception e) {
            log.error("[秘塔] 导航到会话失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从当前 URL 提取可复用的路径标识（不含域名），用于多轮会话恢复。
     */
    public String extractChatId(Page page) {
        try {
            String url = page.url();
            if (url == null || !url.contains("metaso.cn")) {
                return null;
            }
            int q = url.indexOf('?');
            String base = q > 0 ? url.substring(0, q) : url;
            int hashIdx = base.indexOf('#');
            if (hashIdx >= 0) {
                base = base.substring(0, hashIdx);
            }
            int idx = base.indexOf("metaso.cn/");
            if (idx < 0) {
                return null;
            }
            String path = base.substring(idx + "metaso.cn/".length());
            if (path.isEmpty()) {
                return null;
            }
            return path.length() > 512 ? path.substring(0, 512) : path;
        } catch (Exception e) {
            log.debug("[秘塔] 解析会话ID失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 上传文件到秘塔输入区，优先走定向composer上传，失败回退到DOM通用上传。
     */
    public boolean uploadFile(Page page, String localFilePath) {
        if (fileDownloadUtil.uploadComposerAreaFile(page, localFilePath, "[秘塔文件上传]")) {
            return true;
        }
        return fileDownloadUtil.uploadViaDOM(page, localFilePath, new String[]{
            "button:has-text('上传文件')",
            "button:has-text('上传')",
            "[role='button']:has-text('上传')",
            "button:has-text('附件')",
            "button:has-text('本地上传')",
            "li:has-text('本地上传')",
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

        String q = query.trim();
        String baselineReply = extractLatestAssistantText(page, null);
        String promptSignal = buildPromptSignal(query);
        int promptCountBefore = countPromptOccurrences(page, promptSignal);

        if (!fillAndSend(page, q)) {
            throw new RuntimeException("未找到输入框或发送失败");
        }

        return waitForAssistantReply(page, baselineReply, promptSignal, promptCountBefore, q);
    }

    private boolean fillAndSend(Page page, String text) {
        Locator input = findPrimaryInput(page);
        if (input == null) {
            return false;
        }
        try {
            input.click();
            page.waitForTimeout(200);
            AssistantReplyTextExtractor.fillComposerUtf8(input, text);
            page.waitForTimeout(200);

            String[] sendSelectors = {
                "button:has-text('发送')",
                "div[role='button']:has-text('发送')",
                "button[aria-label*='发送']",
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
            log.error("[秘塔] 发送消息失败: {}", e.getMessage());
            return false;
        }
    }

    private Locator findPrimaryInput(Page page) {
        String[] selectors = {
            "textarea[placeholder*='问题']",
            "textarea[placeholder*='输入']",
            "input[placeholder*='搜索']",
            "textarea[placeholder*='搜索']",
            "div[contenteditable='true']",
            "textarea",
            "input[type='text']"
        };
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible(new Locator.IsVisibleOptions().setTimeout(1500))) {
                    return loc;
                }
            } catch (Exception ignore) {
                // try next
            }
        }
        return null;
    }

    private String waitForAssistantReply(Page page, String baselineReply, String promptSignal, int promptCountBefore,
                                         String userQuery) {
        long start = System.currentTimeMillis();
        long maxWait = 150000;
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

            String text = extractLatestAssistantText(page, userQuery);
            if (text != null && !text.trim().isEmpty()) {
                boolean looksLikeBaseline = sameReply(text, baselineReply);
                int promptCountAfter = countPromptOccurrences(page, promptSignal);
                boolean promptMoved = promptCountAfter > promptCountBefore;
                if (!promptMoved) {
                    page.waitForTimeout(500);
                    continue;
                }
                if (looksLikeBaseline && !promptMoved) {
                    page.waitForTimeout(500);
                    continue;
                }
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

        String fallback = extractLatestAssistantText(page, userQuery);
        boolean promptMovedAtEnd = countPromptOccurrences(page, promptSignal) > promptCountBefore;
        if (fallback != null && !fallback.trim().isEmpty()
            && promptMovedAtEnd
            && (!sameReply(fallback, baselineReply) || promptMovedAtEnd)) {
            return fallback.trim();
        }
        return "秘塔超时未返回可读正文，请在浏览器中查看页面";
    }

    private String buildPromptSignal(String query) {
        if (query == null) {
            return "";
        }
        String compact = query.replaceAll("\\s+", " ").trim();
        if (compact.length() > 40) {
            return compact.substring(0, 40);
        }
        return compact;
    }

    private int countPromptOccurrences(Page page, String promptSignal) {
        if (promptSignal == null || promptSignal.isBlank()) {
            return 0;
        }
        try {
            Object o = page.evaluate("""
                (needle) => {
                  const t = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (!needle) return 0;
                  let from = 0;
                  let cnt = 0;
                  while (true) {
                    const idx = t.indexOf(needle, from);
                    if (idx < 0) break;
                    cnt += 1;
                    from = idx + needle.length;
                  }
                  return cnt;
                }
                """, promptSignal);
            if (o instanceof Number) {
                return ((Number) o).intValue();
            }
        } catch (Exception e) {
            log.debug("[秘塔] 统计 prompt 出现次数失败: {}", e.getMessage());
        }
        return 0;
    }

    private boolean sameReply(String current, String baseline) {
        if (current == null || baseline == null) {
            return false;
        }
        String a = current.trim();
        String b = baseline.trim();
        return !a.isEmpty() && a.equals(b);
    }

    private boolean isGenerating(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const t = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (t.includes('停止生成') || t.includes('生成中') || t.includes('思考中') || t.includes('正在搜索')) return true;
                  for (const b of document.querySelectorAll('button, [role="button"]')) {
                    const s = (b.innerText || '').trim();
                    if (s === '停止' || s.includes('停止')) return true;
                  }
                  return false;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private String extractLatestAssistantText(Page page, String userQuery) {
        try {
            String s = AssistantReplyTextExtractor.extractLatestAssistantPlainText(page, userQuery);
            return (s == null || s.isBlank()) ? null : s;
        } catch (Exception e) {
            log.debug("[秘塔] 提取正文失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean clickPrimaryLoginButton(Page page) {
        String[] selectors = {
            "button:has-text('登录')",
            "button:has-text('登入')",
            "[role='button']:has-text('登录')",
            "[role='button']:has-text('登入')",
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
                log.debug("[秘塔] 点击登录失败 {}: {}", sel, e.getMessage());
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
                  for (const el of document.querySelectorAll('button, a, [role="button"]')) {
                    const t = (el.innerText || '').trim();
                    if (t !== '登录' && t !== '登录/注册' && t !== '登入') continue;
                    const r = el.getBoundingClientRect();
                    const st = window.getComputedStyle(el);
                    if (r.width < 36 || r.height < 20 || st.display === 'none' || st.visibility === 'hidden') continue;
                    if (r.top < 220 && r.right > window.innerWidth * 0.62) return true;
                  }
                  return false;
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

    public boolean isLoginLayerPresent(Page page) {
        return isLoginLayerVisible(page);
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

    /**
     * 扫码场景兜底：工作台稳定可见且不再出现登录弹层/显式登录按钮。
     */
    public boolean hasStableWorkbenchReady(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (body.includes('扫码登录') || body.includes('手机号登录') || body.includes('验证码登录')) return false;
                  const hasEditor = !!document.querySelector('textarea, input[type="text"], div[contenteditable="true"]');
                  const hasWorkbenchText = body.includes('秘塔') || body.includes('历史记录') || body.includes('上传文件');
                  if (!hasEditor || !hasWorkbenchText) return false;
                  const loginBtns = Array.from(document.querySelectorAll('button, a, [role="button"]'))
                    .filter(el => {
                      const t = (el.innerText || '').trim();
                      if (t !== '登录' && t !== '登录/注册' && t !== '登入') return false;
                      const r = el.getBoundingClientRect();
                      const st = window.getComputedStyle(el);
                      if (r.width < 36 || r.height < 20 || st.display === 'none' || st.visibility === 'hidden') return false;
                      // 中央大按钮通常表示未登录引导，不算已登录
                      if (r.top > 220 && r.left > window.innerWidth * 0.25 && r.right < window.innerWidth * 0.75) return true;
                      return r.top < 220 && r.right > window.innerWidth * 0.62;
                    });
                  return loginBtns.length === 0;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasMainSearchUi(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const t = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (t.includes('秘塔') || t.includes('Metaso')) {
                    return !!(document.querySelector('textarea, input[type="text"], div[contenteditable="true"]'));
                  }
                  return false;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
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
