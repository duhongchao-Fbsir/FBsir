package com.wx.fbsir.engine.utils.ai;

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
 * 豆包（Doubao）网页版自动化工具。
 * <p>
 * 页面结构可能随产品迭代变化，选择器采用多路兜底；若失效请根据实际 DOM 微调。
 */
@Component
public class DoubaoUtil {

    private static final Logger log = LoggerFactory.getLogger(DoubaoUtil.class);

    @Autowired
    private FileDownloadUtil fileDownloadUtil;

    /** 主对话入口（与公开入口一致） */
    public static final String DOUBAO_CHAT_HOME = "https://www.doubao.com/chat";

    private static final Pattern CHAT_ID_IN_PATH = Pattern.compile("doubao\\.com/chat/([^/?#]+)");

    /**
     * 检测是否已登录。
     * <p>
     * 豆包未登录时主界面仍可能有「发消息」输入框，不能仅凭输入框判断已登录。
     * 可靠顺序：独立登录页 / 右上角「登录」/ 登录弹层 → 未登录；否则再结合用户区弱信号。
     *
     * @param navigate 为 true 时先打开豆包对话页
     * @return 已登录返回展示名或「已登录用户」；未登录返回 "false"
     */
    public String checkLoginStatus(Page page, boolean navigate) {
        if (navigate) {
            try {
                page.navigate(DOUBAO_CHAT_HOME, new Page.NavigateOptions()
                    .setTimeout(25000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(15000));
                page.waitForTimeout(1200);
            } catch (Exception e) {
                log.warn("[Doubao] 导航失败: {}", e.getMessage());
                return "false";
            }
        }

        try {
            if (isExplicitLoginPage(page)) {
                log.info("[Doubao] 当前为独立登录页或未登录态");
                return "false";
            }
        } catch (Exception e) {
            log.debug("[Doubao] 登录页检测: {}", e.getMessage());
        }

        // 未登录强信号：主站右上角「登录」仍可见（与「发消息」输入框可并存，故不能再用输入框判断）
        if (isPrimaryLoginEntryVisible(page)) {
            log.info("[Doubao] 主站「登录」入口可见，判定未登录");
            return "false";
        }

        String profileHint = tryExtractLoggedInProfileHint(page);
        if (profileHint != null && !profileHint.isEmpty()) {
            log.info("[Doubao] 检测到已登录用户展示信息");
            return profileHint;
        }

        log.debug("[Doubao] 主站「登录」已消失，判定为已登录");
        return "已登录用户";
    }

    /**
     * 打开对话页并点击「登录」，使扫码/手机号登录浮层出现，便于截图与人工扫码。
     *
     * @return 是否已打开页面（点击失败时仍可能已弹出层，不强制 false）
     */
    public boolean navigateToLoginPageAndOpenLoginLayer(Page page) {
        if (!navigateToLoginPage(page)) {
            return false;
        }
        return openLoginLayerForQrOrPhone(page);
    }

    /**
     * 若存在「登录」按钮则点击，等待登录浮层（含二维码 iframe/画布等）。
     */
    public boolean openLoginLayerForQrOrPhone(Page page) {
        try {
            page.waitForTimeout(400);
            boolean clicked = clickPrimaryLoginButton(page);
            if (clicked) {
                log.info("[Doubao] 已点击「登录」，等待弹层渲染");
            } else {
                log.info("[Doubao] 未找到可点击的「登录」按钮（可能已弹出层或已登录）");
            }
            page.waitForTimeout(800);
            waitForLoginLayerContent(page, 8000);
            return true;
        } catch (Exception e) {
            log.warn("[Doubao] 打开登录浮层异常: {}", e.getMessage());
            return true;
        }
    }

    private boolean clickPrimaryLoginButton(Page page) {
        String[] selectors = {
            "header button:has-text('登录')",
            "nav button:has-text('登录')",
            "[class*='header'] button:has-text('登录')",
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
                log.debug("[Doubao] 点击登录选择器 {} 失败: {}", sel, e.getMessage());
            }
        }
        return tryClickLoginViaScript(page);
    }

    private boolean tryClickLoginViaScript(Page page) {
        try {
            Object clicked = page.evaluate("""
                () => {
                  const nodes = Array.from(document.querySelectorAll('button, a, [role="button"]'));
                  for (const el of nodes) {
                    const t = (el.innerText || '').trim();
                    if (t !== '登录' && t !== '登录/注册') continue;
                    const r = el.getBoundingClientRect();
                    const st = window.getComputedStyle(el);
                    if (r.width < 8 || r.height < 8 || st.display === 'none' || st.visibility === 'hidden') continue;
                    el.click();
                    return true;
                  }
                  return false;
                }
                """);
            return Boolean.TRUE.equals(clicked);
        } catch (Exception e) {
            return false;
        }
    }

    private void waitForLoginLayerContent(Page page, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isLoginLayerVisible(page)) {
                log.info("[Doubao] 登录浮层内容已出现");
                return;
            }
            try {
                page.waitForTimeout(300);
            } catch (Exception e) {
                return;
            }
        }
        log.debug("[Doubao] 等待登录浮层超时，仍尝试截图");
    }

    /** 右上角主 CTA「登录」（未登录时常驻；与页面内「立即登录」等区分，优先取靠右上角的按钮） */
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
                    cands.push({ r });
                  }
                  if (cands.length === 0) return false;
                  cands.sort((a, b) => (b.r.right - a.r.right) || (a.r.top - b.r.top));
                  const top = cands[0].r;
                  return top.top < 220 && top.right > window.innerWidth * 0.4;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    /** 扫码弹窗、手机号登录浮层等 */
    private boolean isLoginLayerVisible(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (body.includes('扫码登录') || (body.includes('请使用') && body.includes('扫码'))) return true;
                  if (body.includes('手机号登录') && body.includes('验证码')) return true;
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

    /**
     * 打开对话页，用于扫码/手机号登录前的界面展示。
     */
    public boolean navigateToLoginPage(Page page) {
        try {
            log.info("[Doubao] 打开对话页以展示登录界面");
            page.navigate(DOUBAO_CHAT_HOME, new Page.NavigateOptions()
                .setTimeout(25000)
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                new Page.WaitForLoadStateOptions().setTimeout(15000));
            page.waitForTimeout(1500);
            return true;
        } catch (Exception e) {
            log.error("[Doubao] 打开登录页失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 导航到已有会话（路径段为会话 id）。
     */
    public boolean navigateToChat(Page page, String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            try {
                page.navigate(DOUBAO_CHAT_HOME, new Page.NavigateOptions()
                    .setTimeout(25000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(800);
                return true;
            } catch (Exception e) {
                log.error("[Doubao] 进入首页失败: {}", e.getMessage());
                return false;
            }
        }

        if (chatId.startsWith("http://") || chatId.startsWith("https://")) {
            try {
                page.navigate(chatId, new Page.NavigateOptions()
                    .setTimeout(30000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(15000));
                page.waitForTimeout(1200);
                return true;
            } catch (Exception e) {
                log.error("[Doubao] 按完整 URL 导航失败: {}", e.getMessage());
                return false;
            }
        }

        try {
            String current = page.url();
            String pathMarker = "/chat/" + chatId;
            if (current != null && current.contains(pathMarker)) {
                log.info("[Doubao] 已在目标会话 URL，跳过导航");
                page.waitForTimeout(600);
                return true;
            }
        } catch (Exception e) {
            log.debug("[Doubao] 检查 URL: {}", e.getMessage());
        }

        final String targetUrl = "https://www.doubao.com/chat/" + chatId;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                log.info("[Doubao] 导航到会话 {} (第{}/3次)", chatId, attempt);
                page.navigate(targetUrl, new Page.NavigateOptions()
                    .setTimeout(30000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(15000));
                page.waitForTimeout(1200);
                String after = page.url();
                if (after != null && after.contains("/chat/")) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("[Doubao] 导航失败 (第{}次): {}", attempt, e.getMessage());
                if (attempt == 3) {
                    return false;
                }
            }
            try {
                page.waitForTimeout(800L * attempt);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * 从地址栏解析会话 id。
     */
    public String extractChatId(Page page) {
        try {
            String url = page.url();
            if (url == null) {
                return null;
            }
            Matcher m = CHAT_ID_IN_PATH.matcher(url);
            if (m.find()) {
                String id = m.group(1);
                if (id != null && !id.isEmpty() && !"chat".equalsIgnoreCase(id)) {
                    return id;
                }
            }
        } catch (Exception e) {
            log.debug("[Doubao] 从 URL 解析会话 id 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将本地文件送入豆包对话输入区（复用 {@link FileDownloadUtil#uploadComposerAreaFile}，并先滚动豆包输入框）。
     */
    public boolean uploadFile(Page page, String localFilePath) {
        try {
            scrollComposerIntoView(page);
            page.waitForTimeout(400);
        } catch (Exception e) {
            log.debug("[Doubao文件上传] 滚动输入区: {}", e.getMessage());
        }
        return fileDownloadUtil.uploadComposerAreaFile(page, localFilePath, "[豆包文件上传]");
    }

    private void scrollComposerIntoView(Page page) {
        try {
            Locator input = findPrimaryInput(page);
            if (input != null) {
                input.scrollIntoViewIfNeeded();
            }
        } catch (Exception e) {
            log.debug("[Doubao] scrollComposerIntoView: {}", e.getMessage());
        }
    }

    /**
     * 输入问题并等待模型回复（单轮）。
     */
    public String sendMessageAndWaitResponse(Page page, String query) {
        return sendMessageAndWaitResponse(page, query, false);
    }

    /**
     * 输入问题并等待模型回复（支持思考开关）。
     */
    public String sendMessageAndWaitResponse(Page page, String query, boolean enableDeepThinking) {
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("问题不能为空");
        }
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.waitForTimeout(500);

        toggleThinkingIfNeeded(page, enableDeepThinking);

        if (!fillAndSend(page, query.trim())) {
            throw new RuntimeException("未找到输入框或发送失败");
        }

        return waitForAssistantReply(page);
    }

    /**
     * 豆包模式切换（快速/专家）。
     */
    public void applyConversationMode(Page page, boolean enableFastMode, boolean enableExpertMode) {
        if (!enableFastMode && !enableExpertMode) {
            return;
        }
        if (enableFastMode && enableExpertMode) {
            throw new RuntimeException("快速与专家模式不能同时开启");
        }
        String target = enableExpertMode ? "专家" : "快速";
        if (tryActivateMode(page, target)) {
            log.info("[Doubao] 模式切换成功: {}", target);
            return;
        }
        throw new RuntimeException("豆包模式切换失败: " + target);
    }

    private void toggleThinkingIfNeeded(Page page, boolean shouldEnable) {
        try {
            Boolean active = evalButtonActive(page, "思考");
            if (active == null) {
                active = evalButtonActive(page, "深度思考");
            }
            if (active == null) {
                return;
            }
            if (active == shouldEnable) {
                return;
            }
            if (tryClickByText(page, "思考") || tryClickByText(page, "深度思考")) {
                page.waitForTimeout(500);
                log.info("[Doubao] 思考开关已切换为: {}", shouldEnable);
            }
        } catch (Exception e) {
            log.debug("[Doubao] 思考开关切换失败: {}", e.getMessage());
        }
    }

    private boolean tryActivateMode(Page page, String modeText) {
        for (int i = 0; i < 3; i++) {
            if (isModeActive(page, modeText)) {
                return true;
            }
            if (!tryClickByText(page, modeText)) {
                if ("快速".equals(modeText) && !tryClickByText(page, "快速模式")) {
                    return false;
                }
                if ("专家".equals(modeText) && !tryClickByText(page, "专家模式")) {
                    return false;
                }
            }
            page.waitForTimeout(600);
            if (isModeActive(page, modeText)) {
                return true;
            }
        }
        return false;
    }

    private boolean isModeActive(Page page, String text) {
        Boolean active = evalButtonActive(page, text);
        if (active == null && "快速".equals(text)) {
            active = evalButtonActive(page, "快速模式");
        } else if (active == null && "专家".equals(text)) {
            active = evalButtonActive(page, "专家模式");
        }
        return Boolean.TRUE.equals(active);
    }

    private Boolean evalButtonActive(Page page, String text) {
        try {
            Object result = page.evaluate("""
                (label) => {
                  const activeStyle = (el) => {
                    const cls = (el.className || '').toString().toLowerCase();
                    if (cls.includes('active') || cls.includes('selected') || cls.includes('checked')) return true;
                    const aria = el.getAttribute('aria-pressed');
                    if (aria === 'true') return true;
                    const attr = el.getAttribute('data-active');
                    if (attr === 'true' || attr === '1') return true;
                    return false;
                  };
                  const nodes = Array.from(document.querySelectorAll('button, [role="button"], div, span'));
                  for (const el of nodes) {
                    const t = (el.innerText || '').trim();
                    if (t !== label) continue;
                    const r = el.getBoundingClientRect();
                    const st = window.getComputedStyle(el);
                    if (r.width < 12 || r.height < 12 || st.display === 'none' || st.visibility === 'hidden') continue;
                    return activeStyle(el);
                  }
                  return null;
                }
                """, text);
            if (result == null) {
                return null;
            }
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return Boolean.parseBoolean(result.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean tryClickByText(Page page, String text) {
        String[] selectors = {
            "button:has-text('" + text + "')",
            "[role='button']:has-text('" + text + "')",
            "div:has-text('" + text + "')",
            "span:has-text('" + text + "')"
        };
        for (String selector : selectors) {
            try {
                Locator option = page.locator(selector).first();
                if (option.count() > 0 && option.isVisible(new Locator.IsVisibleOptions().setTimeout(800))) {
                    option.click(new Locator.ClickOptions().setTimeout(3000).setForce(true));
                    return true;
                }
            } catch (Exception ignore) {
                // next selector
            }
        }
        return false;
    }

    private boolean fillAndSend(Page page, String text) {
        Locator input = findPrimaryInput(page);
        if (input == null) {
            return false;
        }
        try {
            input.click();
            page.waitForTimeout(300);
            input.fill("");
            input.fill(text);
            page.waitForTimeout(200);

            String[] sendSelectors = {
                "button:has-text('发送')",
                "div[role='button']:has-text('发送')",
                "[class*='send']:not([disabled])",
                "button[type='submit']"
            };
            for (String sel : sendSelectors) {
                try {
                    Locator btn = page.locator(sel).first();
                    if (btn.count() > 0 && btn.isVisible(new Locator.IsVisibleOptions().setTimeout(600))) {
                        btn.click(new Locator.ClickOptions().setTimeout(4000));
                        page.waitForTimeout(500);
                        return true;
                    }
                } catch (Exception ignore) {
                }
            }
            input.press("Enter");
            page.waitForTimeout(600);
            return true;
        } catch (Exception e) {
            log.error("[Doubao] 填充发送失败: {}", e.getMessage());
            return false;
        }
    }

    private Locator findPrimaryInput(Page page) {
        String[] selectors = {
            "textarea[placeholder*='输入']",
            "textarea[placeholder*='问题']",
            "textarea[placeholder*='发消息']",
            "div[contenteditable='true']",
            "textarea"
        };
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel).first();
                if (loc.count() > 0 && loc.isVisible(new Locator.IsVisibleOptions().setTimeout(1500))) {
                    return loc;
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private String waitForAssistantReply(Page page) {
        long start = System.currentTimeMillis();
        long maxWait = 300_000;
        String lastText = "";
        int stable = 0;

        page.waitForTimeout(1500);

        while (System.currentTimeMillis() - start < maxWait) {
            if (page.isClosed()) {
                throw new RuntimeException("页面已关闭");
            }

            if (isGenerating(page)) {
                stable = 0;
                page.waitForTimeout(400);
                continue;
            }

            String text = extractLatestAssistantText(page);
            if (text != null && !text.trim().isEmpty()) {
                if (text.equals(lastText)) {
                    stable++;
                    if (stable >= 3) {
                        return text.trim();
                    }
                } else {
                    lastText = text;
                    stable = 0;
                }
            }

            page.waitForTimeout(500);
        }

        String fallback = extractLatestAssistantText(page);
        if (fallback != null && !fallback.trim().isEmpty()) {
            log.warn("[Doubao] 等待超时，返回最后一次抓取的正文");
            return fallback.trim();
        }
        return "豆包超时未返回可读正文，请手动在浏览器中查看页面";
    }

    private boolean isGenerating(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const body = document.body.innerText || '';
                  if (body.includes('停止生成') || body.includes('停止回答')) return true;
                  const stop = document.querySelector('button');
                  const buttons = document.querySelectorAll('button, [role="button"]');
                  for (const b of buttons) {
                    const t = (b.innerText || '').trim();
                    if (t === '停止' || t.includes('停止生成')) return true;
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
                  const articles = document.querySelectorAll('article, [class*="markdown"], [class*="Markdown"]');
                  let best = '';
                  articles.forEach(el => {
                    const t = (el.innerText || '').trim();
                    if (t.length > best.length) best = t;
                  });
                  return best || '';
                }
                """);
            return o != null ? o.toString() : null;
        } catch (Exception e) {
            log.debug("[Doubao] 提取正文失败: {}", e.getMessage());
            return null;
        }
    }
}
