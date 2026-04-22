package com.wx.fbsir.engine.utils.ai;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
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
 * 腾讯元宝网页版自动化工具。
 */
@Component
public class YuanbaoUtil {

    private static final Logger log = LoggerFactory.getLogger(YuanbaoUtil.class);

    @Autowired
    private FileDownloadUtil fileDownloadUtil;

    public static final String YUANBAO_HOME_URL = "https://yuanbao.tencent.com/";

    private static final Pattern CHAT_ID_IN_URL = Pattern.compile(
        "yuanbao\\.tencent\\.com/(?:chat/|c/|conversation/)([A-Za-z0-9_-]{8,})");

    public String checkLoginStatus(Page page, boolean navigate) {
        String currentUrl = safeUrl(page);
        if (navigate) {
            try {
                page.navigate(YUANBAO_HOME_URL, new Page.NavigateOptions()
                    .setTimeout(25000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(15000));
                page.waitForTimeout(1200);
            } catch (Exception e) {
                log.warn("[Yuanbao] 导航失败: {}", e.getMessage());
                return "false";
            }
        }
        resolveAccountTypePopupIfPresent(page);

        boolean accountTypePopupVisible = isAccountTypePopupVisible(page);
        boolean explicitLoginPage = isExplicitLoginPage(page);
        boolean unauthUiVisible = hasUnauthedUiMarkers(page);
        boolean loginEntryVisible = isPrimaryLoginEntryVisible(page);
        boolean loginLayerVisible = isLoginLayerVisible(page);
        boolean authCookieReady = hasAuthCookies(page);

        // /scan 阶段仍在登录中，禁止提前判定成功（访客也可能看到工作台底图）。
        if (currentUrl.contains("/scan")) {
            log.debug("[Yuanbao登录判定] 未登录(scan流程中) - url={}", currentUrl);
            return "false";
        }

        String profileHint = tryExtractLoggedInProfileHint(page);
        if (profileHint != null && !profileHint.isEmpty()) {
            log.debug("[Yuanbao登录判定] 已登录(用户信息) - profileHint={}", profileHint);
            return profileHint;
        }

        boolean loggedInUi = hasLoggedInUiMarkers(page);

        // 访客也可打开 /chat/...，不能仅凭工作台形态判已登录。以下强未登录信号优先于 cookie/工作台。
        // 1) 右上角「登录」可见 → 未登录（与页面是否为 chat 无关）
        if (loginEntryVisible) {
            log.debug("[Yuanbao登录判定] 未登录(登录入口可见) - popup={}, explicit={}, unauthUi={}, loginLayer={}, authCookie={}, loggedInUi={}",
                accountTypePopupVisible, explicitLoginPage, unauthUiVisible, loginLayerVisible, authCookieReady, loggedInUi);
            return "false";
        }
        // 2) 页面含「未登录」等未认证文案且无已登录 UI 证据
        if (unauthUiVisible && !loggedInUi) {
            log.debug("[Yuanbao登录判定] 未登录(未认证文案) - popup={}, explicit={}, unauthUi={}, loginLayer={}, authCookie={}, loggedInUi={}",
                accountTypePopupVisible, explicitLoginPage, unauthUiVisible, loginLayerVisible, authCookieReady, loggedInUi);
            return "false";
        }
        // 3) 弹窗/显式登录页/登录浮层
        if ((accountTypePopupVisible || explicitLoginPage || loginLayerVisible) && !loggedInUi) {
            log.debug("[Yuanbao登录判定] 未登录(强未登录信号) - popup={}, explicit={}, unauthUi={}, loginLayer={}, authCookie={}, loggedInUi={}",
                accountTypePopupVisible, explicitLoginPage, unauthUiVisible, loginLayerVisible, authCookieReady, loggedInUi);
            return "false";
        }

        // cookie仅作为弱信号，必须同时具备工作台特征且无强未登录信号
        if (loggedInUi || (authCookieReady && looksLikeChatWorkbench(page) && !loginEntryVisible && !currentUrl.contains("/scan"))) {
            log.debug("[Yuanbao登录判定] 已登录(UI/工作台) - popup={}, explicit={}, unauthUi={}, loginLayer={}, authCookie={}, loggedInUi={}",
                accountTypePopupVisible, explicitLoginPage, unauthUiVisible, loginLayerVisible, authCookieReady, loggedInUi);
            return "已登录用户";
        }
        log.debug("[Yuanbao登录判定] 未登录(默认) - popup={}, explicit={}, unauthUi={}, loginLayer={}, authCookie={}, loggedInUi={}",
            accountTypePopupVisible, explicitLoginPage, unauthUiVisible, loginLayerVisible, authCookieReady, loggedInUi);
        return "false";
    }

    public boolean navigateToLoginPage(Page page) {
        try {
            page.navigate(YUANBAO_HOME_URL, new Page.NavigateOptions()
                .setTimeout(25000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(15000));
            page.waitForTimeout(1200);
            return true;
        } catch (Exception e) {
            log.error("[Yuanbao] 打开页面失败: {}", e.getMessage());
            return false;
        }
    }

    public boolean navigateToLoginPageAndOpenLoginLayer(Page page) {
        if (!navigateToLoginPage(page)) {
            return false;
        }
        resolveAccountTypePopupIfPresent(page);
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
            log.warn("[Yuanbao] 打开登录浮层异常: {}", e.getMessage());
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
                page.navigate("https://yuanbao.tencent.com/chat/" + chatId, new Page.NavigateOptions()
                    .setTimeout(30000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            }
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(15000));
            page.waitForTimeout(1200);
            resolveAccountTypePopupIfPresent(page);
            return true;
        } catch (Exception e) {
            log.error("[Yuanbao] 导航到会话失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 扫码轮询中的页面自愈：
     * - about:blank / 非元宝域 / 页面被清空时，回拉到元宝首页并重开登录层。
     */
    public boolean recoverScanPageIfDrifted(Page page) {
        try {
            String url = page.url();
            boolean maybeDrifted = (url == null || url.isEmpty() || "about:blank".equalsIgnoreCase(url) || !url.contains("yuanbao.tencent.com"));
            if (maybeDrifted) {
                // 扫码回调期间会短暂跳到空白页，先给一次过渡窗口，避免误回拉打断登录完成。
                page.waitForTimeout(2200);
                String urlAfterGrace = page.url();
                if (urlAfterGrace != null && urlAfterGrace.contains("yuanbao.tencent.com")) {
                    return false;
                }
                if (urlAfterGrace != null && !urlAfterGrace.isEmpty() && !"about:blank".equalsIgnoreCase(urlAfterGrace)) {
                    return false;
                }
                log.warn("[Yuanbao] 扫码页面漂移，开始回拉 - 当前URL: {} -> {}", url, urlAfterGrace);
                return navigateToLoginPageAndOpenLoginLayer(page);
            }
            Object isBlankBody = page.evaluate("""
                () => {
                  const t = (document.body && document.body.innerText) ? document.body.innerText.replace(/\\s+/g, '') : '';
                  const hasCore = t.includes('元宝') || t.includes('登录') || t.includes('扫码') || t.includes('账号类型');
                  return !hasCore && t.length < 8;
                }
                """);
            if (Boolean.TRUE.equals(isBlankBody)) {
                log.warn("[Yuanbao] 扫码页面疑似空白，开始回拉 - URL: {}", url);
                return navigateToLoginPageAndOpenLoginLayer(page);
            }
            return false;
        } catch (Exception e) {
            log.debug("[Yuanbao] 扫码页面自愈检查异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 扫码漂移后的软恢复：只回到元宝主页，不主动打开登录层。
     * 目的是在不打断回调流程的前提下，让页面脱离 about:blank 并触发登录态可见。
     */
    public boolean softRecoverScanToHome(Page page) {
        try {
            page.navigate(YUANBAO_HOME_URL, new Page.NavigateOptions()
                .setTimeout(20000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(12000));
            page.waitForTimeout(1000);
            resolveAccountTypePopupIfPresent(page);
            return true;
        } catch (Exception e) {
            log.warn("[Yuanbao] 扫码软恢复到主页失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 对外暴露：当前页面是否已是可对话工作台（不代表严格登录态）。
     */
    public boolean isChatWorkbenchReady(Page page) {
        return looksLikeChatWorkbench(page);
    }

    /**
     * 对外暴露：是否仍处于登录浮层态。
     */
    public boolean hasLoginLayer(Page page) {
        return isLoginLayerVisible(page);
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
            log.debug("[Yuanbao] 解析会话ID失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 上传文件到元宝输入区，优先使用平台对话区上传，再降级到DOM通用上传。
     */
    public boolean uploadFile(Page page, String localFilePath) {
        tryHandleAccountTypePopup(page);
        tryClickComposerPlus(page);
        if (fileDownloadUtil.uploadComposerAreaFile(page, localFilePath, "[元宝文件上传]")) {
            return true;
        }
        return fileDownloadUtil.uploadViaDOM(page, localFilePath, new String[]{
            "footer button:has-text('+')",
            "footer [role='button']:has-text('+')",
            "[class*='composer'] button:has-text('+')",
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

    /**
     * 元宝上传入口常表现为输入框左侧“+”图标按钮，先主动触发一次以暴露 file input。
     */
    private void tryClickComposerPlus(Page page) {
        String[] plusSelectors = {
            "footer button:has-text('+')",
            "footer [role='button']:has-text('+')",
            "[class*='composer'] button:has-text('+')",
            "footer button[aria-label*='添加']",
            "footer [role='button'][aria-label*='添加']",
            "button[aria-label*='添加']"
        };
        for (String sel : plusSelectors) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible(new Locator.IsVisibleOptions().setTimeout(900))) {
                    loc.click(new Locator.ClickOptions().setTimeout(4000));
                    page.waitForTimeout(450);
                    log.debug("[元宝文件上传] 已点击上传入口: {}", sel);
                    return;
                }
            } catch (Exception ignore) {
                // try next selector
            }
        }
        try {
            Object clicked = page.evaluate("""
                () => {
                  const footer = document.querySelector('footer') || document.body;
                  const candidates = [];
                  for (const el of footer.querySelectorAll('button,[role="button"]')) {
                    const r = el.getBoundingClientRect();
                    const st = window.getComputedStyle(el);
                    if (r.width < 20 || r.height < 20) continue;
                    if (st.display === 'none' || st.visibility === 'hidden') continue;
                    if (r.top < window.innerHeight * 0.55) continue;
                    const txt = (el.innerText || '').trim();
                    const aria = (el.getAttribute('aria-label') || '').trim();
                    if (txt === '+' || aria.includes('添加') || aria.includes('附件') || aria.includes('上传')) {
                      candidates.push({el, left: r.left, right: r.right});
                    }
                  }
                  if (candidates.length === 0) return false;
                  candidates.sort((a, b) => a.left - b.left);
                  candidates[0].el.click();
                  return true;
                }
                """);
            if (Boolean.TRUE.equals(clicked)) {
                page.waitForTimeout(450);
                log.debug("[元宝文件上传] 已通过JS点击左侧上传入口");
            }
        } catch (Exception e) {
            log.debug("[元宝文件上传] 点击上传入口失败: {}", e.getMessage());
        }
    }

    /**
     * 扫码后常见中间态：已扫到码，但仍需手机端点击确认登录。
     */
    public boolean isWaitingPhoneConfirm(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const t = (document.body && document.body.innerText) ? document.body.innerText.replace(/\\s+/g, '') : '';
                  return t.includes('请在手机确认')
                    || t.includes('请在微信中确认')
                    || t.includes('请在手机上确认')
                    || t.includes('请在微信确认')
                    || t.includes('确认登录')
                    || t.includes('扫码成功')
                    || t.includes('已扫码');
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 扫码流程中的“页面漂移”判断（仅判断，不做副作用）。
     */
    public boolean isLikelyDrifted(Page page) {
        try {
            String url = page.url();
            return url == null || url.isEmpty() || "about:blank".equalsIgnoreCase(url) || !url.contains("yuanbao.tencent.com");
        } catch (Exception e) {
            return true;
        }
    }

    public String safeUrl(Page page) {
        try {
            return page.url();
        } catch (Exception e) {
            return "unknown";
        }
    }

    public String sendMessageAndWaitResponse(Page page, String query) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.waitForTimeout(500);
        resolveAccountTypePopupIfPresent(page);
        if (!fillAndSend(page, query.trim())) {
            throw new RuntimeException("未找到输入框或发送失败");
        }
        return waitForAssistantReply(page);
    }

    /**
     * 对外暴露：在控制器中可主动调用，处理“选择账号类型”阻塞弹窗。
     */
    public boolean tryHandleAccountTypePopup(Page page) {
        return resolveAccountTypePopupIfPresent(page);
    }

    private boolean fillAndSend(Page page, String text) {
        if (isAccountTypePopupVisible(page) && !resolveAccountTypePopupIfPresent(page)) {
            throw new RuntimeException("检测到账号类型弹窗，但自动点击“个人账号”失败");
        }
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
            log.error("[Yuanbao] 发送消息失败: {}", e.getMessage());
            return false;
        }
    }

    private Locator findPrimaryInput(Page page) {
        String[] selectors = {
            "textarea[placeholder*='发']",
            "textarea[placeholder*='输入']",
            "textarea[placeholder*='问']",
            "div[contenteditable='true']",
            "textarea"
        };
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible(new Locator.IsVisibleOptions().setTimeout(1200))) {
                    return loc;
                }
            } catch (Exception ignore) {
                // try next
            }
        }
        return null;
    }

    private String waitForAssistantReply(Page page) {
        long start = System.currentTimeMillis();
        long maxWait = 300000;
        String last = "";
        int stable = 0;
        int popupStuckCount = 0;
        page.waitForTimeout(1500);

        while (System.currentTimeMillis() - start < maxWait) {
            if (page.isClosed()) {
                throw new RuntimeException("页面已关闭");
            }
            if (isAccountTypePopupVisible(page)) {
                if (resolveAccountTypePopupIfPresent(page)) {
                    popupStuckCount = 0;
                    page.waitForTimeout(500);
                    continue;
                }
                popupStuckCount++;
                if (popupStuckCount >= 6) {
                    throw new RuntimeException("检测到账号类型弹窗持续阻塞，自动点击“个人账号”失败");
                }
                page.waitForTimeout(500);
                continue;
            }
            popupStuckCount = 0;
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
        return "元宝超时未返回可读正文，请手动在浏览器中查看页面";
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
            log.debug("[Yuanbao] 提取正文失败: {}", e.getMessage());
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
                log.debug("[Yuanbao] 点击登录失败 {}: {}", sel, e.getMessage());
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
                    if (el.closest('[role="dialog"], [class*="modal"], [class*="Modal"], [class*="dialog"], [class*="Dialog"]')) continue;
                    const r = el.getBoundingClientRect();
                    const st = window.getComputedStyle(el);
                    if (r.width < 4 || r.height < 4 || st.display === 'none' || st.visibility === 'hidden') continue;
                    cands.push(r);
                  }
                  if (cands.length === 0) return false;
                  cands.sort((a,b) => (b.right - a.right) || (a.top - b.top));
                  const top = cands[0];
                  return top.top < 140 && top.right > window.innerWidth * 0.55;
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

    private boolean hasAuthCookies(Page page) {
        try {
            return page.context().cookies("https://yuanbao.tencent.com").stream()
                .map(c -> c.name)
                .anyMatch(name ->
                    "hy_token".equalsIgnoreCase(name)
                        || "hy_user".equalsIgnoreCase(name)
                        || "561553b295037d16".equalsIgnoreCase(name));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasLoggedInUiMarkers(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (body.includes('未登录')) return false;
                  if (body.includes('退出登录')) return true;
                  if (body.includes('个人中心') && !body.includes('登录')) return true;
                  for (const el of document.querySelectorAll('[class*="user"], [class*="User"], [class*="profile"], [class*="Profile"], [class*="avatar"], [class*="Avatar"]')) {
                    const t = (el.innerText || el.getAttribute('title') || '').trim();
                    if (!t || t.length > 40) continue;
                    const r = el.getBoundingClientRect();
                    const st = window.getComputedStyle(el);
                    if (r.width < 8 || r.height < 8 || st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) < 0.3) continue;
                    if (t.includes('未登录') || t.includes('登录') || t.includes('扫码')) continue;
                    return true;
                  }
                  return false;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            log.debug("[Yuanbao] 已登录UI检测异常: {}", e.getMessage());
            return false;
        }
    }

    private boolean hasUnauthedUiMarkers(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (body.includes('未登录')) return true;
                  if (body.includes('扫码登录')) return true;
                  if (body.includes('登录账号类型') || body.includes('选择账号类型') || body.includes('请选择账号类型')) return true;
                  const hasTopLogin = (() => {
                    for (const el of document.querySelectorAll('button, a, [role="button"]')) {
                      const t = (el.innerText || '').trim();
                      if (t !== '登录' && t !== '登录/注册') continue;
                      if (el.closest('[role="dialog"], [class*="modal"], [class*="Modal"], [class*="dialog"], [class*="Dialog"]')) continue;
                      const r = el.getBoundingClientRect();
                      const st = window.getComputedStyle(el);
                      if (r.width < 4 || r.height < 4 || st.display === 'none' || st.visibility === 'hidden') continue;
                      if (r.top < 140 && r.right > window.innerWidth * 0.55) return true;
                    }
                    return false;
                  })();
                  return hasTopLogin;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean looksLikeChatWorkbench(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const url = window.location.href || '';
                  if (url.includes('/scan')) return false;
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (body.includes('未登录')) return false;
                  const hasInput = !!document.querySelector(
                    'textarea, div[contenteditable="true"], [class*="editor"], [class*="input"], [placeholder*="问"], [placeholder*="发消息"]'
                  );
                  if (!hasInput) return false;
                  return body.includes('新建对话')
                    || body.includes('深度思考')
                    || body.includes('联网搜索')
                    || body.includes('上传文件')
                    || body.includes('停止生成')
                    || !!document.querySelector('[class*="chat"], [class*="Chat"], [data-testid*="message"]');
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            log.debug("[Yuanbao] 工作台检测异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 元宝偶发弹出“登录账号类型”选择层（个人账号/团队账号），会挡住输入区。
     * 该弹层并非每次出现，若检测到则优先自动点击“个人账号”。
     */
    private boolean resolveAccountTypePopupIfPresent(Page page) {
        try {
            try {
                page.bringToFront();
            } catch (Exception ignore) {
                // ignore
            }
            // /scan 等页弹层可能在 iframe 内，主文档检测不到完整文案
            if (tryClickPersonalAccountInFrames(page)) {
                return true;
            }
            if (!isAccountTypePopupVisible(page)) {
                return false;
            }
            Locator personalAccount = page.locator(
                "[role='dialog'] button:has-text('个人账号'), " +
                    "[role='dialog'] [role='button']:has-text('个人账号'), " +
                    "[role='dialog'] li:has-text('个人账号'), " +
                    "[role='dialog'] div:has-text('个人账号'), " +
                    "button:has-text('个人账号'), [role='button']:has-text('个人账号'), li:has-text('个人账号'), div:has-text('个人账号')"
            ).first();
            if (personalAccount.count() > 0
                && personalAccount.isVisible(new Locator.IsVisibleOptions().setTimeout(300))) {
                personalAccount.click(new Locator.ClickOptions().setTimeout(3000).setForce(true));
                page.waitForTimeout(600);
                if (!isAccountTypePopupVisible(page)) {
                    log.info("[Yuanbao] 检测到账号类型弹窗，已自动点击“个人账号”");
                    return true;
                }
            }

            Object clickedByDom = page.evaluate("""
                () => {
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  const hasTypeKeyword = body.includes('账号类型') || body.includes('选择账号类型') || body.includes('请选择账号类型');
                  const hasOptions = body.includes('个人账号') && body.includes('团队账号');
                  if (!(hasTypeKeyword && hasOptions)) return false;

                  const visible = (el) => {
                    if (!el) return false;
                    const r = el.getBoundingClientRect();
                    const s = window.getComputedStyle(el);
                    return r.width > 6 && r.height > 6 && s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';
                  };

                  const candidates = [];
                  const nodes = document.querySelectorAll('button, [role="button"], li, div, span, a, p');
                  for (const n of nodes) {
                    const t = (n.innerText || '').replace(/\s+/g, '');
                    if (!t.includes('个人账号')) continue;
                    const dialog = n.closest('[role="dialog"], [class*="modal"], [class*="Modal"], [class*="dialog"], [class*="Dialog"]');
                    const target = (dialog ? n.closest('button, [role="button"], li, a, div') : null) || n.closest('button, [role="button"], li, a, div') || n;
                    if (!visible(target)) continue;
                    const r = target.getBoundingClientRect();
                    const score = r.width * r.height;
                    candidates.push({ target, score });
                  }
                  if (!candidates.length) return false;
                  candidates.sort((a, b) => b.score - a.score);
                  candidates[0].target.click();
                  return true;
                }
                """);
            if (Boolean.TRUE.equals(clickedByDom)) {
                page.waitForTimeout(700);
                if (!isAccountTypePopupVisible(page)) {
                    log.info("[Yuanbao] 检测到账号类型弹窗，已通过DOM路径点击“个人账号”");
                    return true;
                }
            }

            // 兜底：在可见弹窗内做多轮“文本中心点 + 右侧箭头点”原生事件点击
            Object clickedByHardMode = page.evaluate("""
                () => {
                  const wait = (ms) => new Promise(r => setTimeout(r, ms));
                  const visible = (el) => {
                    if (!el) return false;
                    const r = el.getBoundingClientRect();
                    const s = window.getComputedStyle(el);
                    return r.width > 12 && r.height > 12 && s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';
                  };
                  const isPopupVisible = () => {
                    const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                    const hasType = body.includes('账号类型') || body.includes('选择账号类型') || body.includes('请选择账号类型');
                    const hasOptions = body.includes('个人账号') && body.includes('团队账号');
                    if (!(hasType && hasOptions)) return false;
                    const modal = document.querySelector('[role="dialog"], [class*="modal"], [class*="Modal"], [class*="dialog"], [class*="Dialog"]');
                    if (!modal) return true;
                    const r = modal.getBoundingClientRect();
                    const st = window.getComputedStyle(modal);
                    return r.width > 120 && r.height > 80 && st.display !== 'none' && st.visibility !== 'hidden' && st.opacity !== '0';
                  };
                  const dispatchMouse = (el, x, y) => {
                    if (!el) return;
                    const evOpt = { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, button: 0 };
                    ['pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(type => {
                      try { el.dispatchEvent(new MouseEvent(type, evOpt)); } catch (_) {}
                    });
                  };
                  const fireClick = (el) => {
                    if (!el) return false;
                    try {
                      el.click();
                    } catch (_) {}
                    try {
                      const r = el.getBoundingClientRect();
                      const x = r.left + r.width / 2;
                      const y = r.top + r.height / 2;
                      dispatchMouse(el, x, y);
                    } catch (_) {}
                    return true;
                  };
                  const tryOneRound = () => {
                    const modal = document.querySelector('[role="dialog"], [class*="modal"], [class*="Modal"], [class*="dialog"], [class*="Dialog"]') || document.body;
                    const rows = [];
                    for (const el of modal.querySelectorAll('li, button, [role="button"], div, a, p, span')) {
                      const t = (el.innerText || '').replace(/\\s+/g, '');
                      if (!t.includes('个人账号')) continue;
                      const row = el.closest('li, button, [role="button"], div, a') || el;
                      if (!visible(row)) continue;
                      rows.push(row);
                    }
                    if (!rows.length) return false;
                    const row = rows[0];
                    const r = row.getBoundingClientRect();
                    const centerX = r.left + r.width * 0.5;
                    const centerY = r.top + r.height * 0.5;
                    const arrowX = Math.min(r.right - 10, r.left + r.width * 0.9);
                    const arrowY = centerY;
                    fireClick(row);
                    const cHit = document.elementFromPoint(centerX, centerY);
                    if (cHit) dispatchMouse(cHit, centerX, centerY);
                    const aHit = document.elementFromPoint(arrowX, arrowY);
                    if (aHit) dispatchMouse(aHit, arrowX, arrowY);
                    const arrow = row.querySelector('svg, [class*="arrow"], [class*="Arrow"], i, span:last-child');
                    if (visible(arrow)) fireClick(arrow);
                    return true;
                  };
                  if (!isPopupVisible()) return false;
                  let acted = false;
                  for (let i = 0; i < 4; i++) {
                    acted = tryOneRound() || acted;
                    // 同步等待由Java侧完成，这里仅多轮触发
                  }
                  return acted;
                }
                """);
            if (Boolean.TRUE.equals(clickedByHardMode)) {
                page.waitForTimeout(1000);
                if (!isAccountTypePopupVisible(page)) {
                    log.info("[Yuanbao] 检测到账号类型弹窗，已通过强制点击“个人账号”行+箭头完成处理");
                    return true;
                }
            }
            log.warn("[Yuanbao] 账号类型弹窗出现，但未找到可点击的“个人账号”元素");
            return false;
        } catch (Exception e) {
            log.debug("[Yuanbao] 处理账号类型弹窗异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 在子 frame（含 /scan 内嵌页）中检测并点击「个人账号」。
     */
    private boolean tryClickPersonalAccountInFrames(Page page) {
        for (Frame frame : page.frames()) {
            try {
                Object o = frame.evaluate("""
                    () => {
                      const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                      const hasTitle = body.includes('登录账号类型')
                        || body.includes('选择账号类型')
                        || body.includes('请选择账号类型')
                        || body.includes('账号类型');
                      const hasOptions = body.includes('个人账号') && body.includes('团队账号');
                      return hasTitle && hasOptions;
                    }
                    """);
                if (!Boolean.TRUE.equals(o)) {
                    continue;
                }
                Locator loc = frame.locator("text=个人账号").first();
                if (loc.count() == 0) {
                    continue;
                }
                if (!loc.isVisible(new Locator.IsVisibleOptions().setTimeout(800))) {
                    continue;
                }
                loc.click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                page.waitForTimeout(700);
                log.info("[Yuanbao] 已在 iframe 内自动点击「个人账号」（账号类型选择）");
                return true;
            } catch (Exception ex) {
                log.debug("[Yuanbao] iframe 内账号类型点击跳过: {}", ex.getMessage());
            }
        }
        return false;
    }

    private boolean isAccountTypePopupVisible(Page page) {
        try {
            Object popupVisible = page.evaluate("""
                () => {
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  const hasTitle = body.includes('登录账号类型')
                    || body.includes('选择账号类型')
                    || body.includes('请选择账号类型')
                    || body.includes('账号类型');
                  const hasOptions = body.includes('个人账号') && body.includes('团队账号');
                  if (!(hasTitle && hasOptions)) return false;
                  const modal = document.querySelector('[role="dialog"], [class*="modal"], [class*="Modal"], [class*="popup"], [class*="Popup"], [class*="Drawer"], [class*="drawer"]');
                  if (!modal) return true;
                  const r = modal.getBoundingClientRect();
                  const st = window.getComputedStyle(modal);
                  if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) < 0.01) return true;
                  return r.width > 40 && r.height > 40;
                }
                """);
            if (Boolean.TRUE.equals(popupVisible)) {
                return true;
            }
        } catch (Exception e) {
            // ignore and fallback to frame-level probing
        }
        for (Frame frame : page.frames()) {
            try {
                Object o = frame.evaluate("""
                    () => {
                      const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                      const hasTitle = body.includes('登录账号类型')
                        || body.includes('选择账号类型')
                        || body.includes('请选择账号类型')
                        || body.includes('账号类型');
                      const hasOptions = body.includes('个人账号') && body.includes('团队账号');
                      return hasTitle && hasOptions;
                    }
                    """);
                if (Boolean.TRUE.equals(o)) {
                    return true;
                }
            } catch (Exception ignore) {
                // frame 可能切换/销毁，继续尝试其他 frame
            }
        }
        return false;
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
