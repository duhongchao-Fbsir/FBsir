package com.wx.fbsir.engine.utils.ai;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * Gitee AI Chat 平台工具类
 * 
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 核心职责
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 
 * 1. 登录状态检测
 * 2. 导航到登录页
 * 3. 消息发送与响应监听
 * 4. 内容提取与清理
 * 
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 使用方式
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 
 * ```java
 * @Autowired
 * private GiteeAiUtil giteeAiUtil;
 * 
 * // 检查登录状态
 * String loginStatus = giteeAiUtil.checkLoginStatus(page, true);
 * 
 * // 导航到登录页
 * boolean success = giteeAiUtil.navigateToLoginPage(page);
 * 
 * // 发送消息并等待回复
 * String response = giteeAiUtil.sendMessageAndWaitResponse(page, "你好");
 * ```
 * 
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * ⚠️ 注意事项
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 
 * 1. 本工具类是原型实现，选择器（selector）需要根据实际页面结构调整
 * 2. Gitee AI Chat 的具体页面结构需要实际访问后确定
 * 3. 登录方式可能需要调整（OAuth、二维码、账号密码等）
 * 4. 本实现采用通用的 DOM 操作方式，实际可能需要针对性优化
 * 
 * @author wxfbsir
 * @date 2026-01-22
 * @version 1.0 (原型阶段)
 */
@Component
public class GiteeAiUtil {

    private static final Logger log = LoggerFactory.getLogger(GiteeAiUtil.class);

    public static class ModeApplyResult {
        private final String modeName;
        private final List<String> repositoryChoices;
        private final String selectedRepository;

        public ModeApplyResult(String modeName, List<String> repositoryChoices, String selectedRepository) {
            this.modeName = modeName;
            this.repositoryChoices = repositoryChoices != null ? repositoryChoices : new ArrayList<>();
            this.selectedRepository = selectedRepository;
        }

        public String getModeName() {
            return modeName;
        }

        public List<String> getRepositoryChoices() {
            return repositoryChoices;
        }

        public String getSelectedRepository() {
            return selectedRepository;
        }
    }

    private static class RepositoryDialogResult {
        private final List<String> repositoryChoices;
        private final String selectedRepository;

        private RepositoryDialogResult(List<String> repositoryChoices, String selectedRepository) {
            this.repositoryChoices = repositoryChoices != null ? repositoryChoices : new ArrayList<>();
            this.selectedRepository = selectedRepository;
        }
    }
    
    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 🔥 重要：以下URL需要根据实际的 Gitee AI Chat 地址调整
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     */
    
    /**
     * Gitee AI Chat 主页地址
     * 🔥 关键：登录和聊天必须在同一个域名，避免跨域Cookie问题
     */
    private static final String GITEE_AI_HOME_URL = "https://chat.gitee.com/";
    
    /**
     * Gitee AI Chat 登录页地址
     * 🔥 修改：不再跨域到 gitee.com，直接使用聊天页（chat.gitee.com）
     *    用户在浏览器中手动登录，Cookie会保存在 chat.gitee.com 域下
     */
    private static final String GITEE_AI_LOGIN_URL = "https://chat.gitee.com/";

    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 功能1：检查 Gitee AI Chat 登录状态
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 
     * @param page Playwright 页面对象
     * @param navigate 是否需要先导航到主页
     * @return 登录状态：已登录返回用户名，未登录返回 "false"
     */
    public String checkLoginStatus(Page page, boolean navigate) {
        if (navigate) {
            try {
                log.debug("📍 [Gitee AI] 开始导航到主页");
                page.navigate(GITEE_AI_HOME_URL, new Page.NavigateOptions().setTimeout(10000));
                page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(10000));
                page.waitForTimeout(1000);
                log.debug("✅ [Gitee AI] 页面加载完成");
            } catch (Exception e) {
                log.warn("❌ [Gitee AI] 导航失败: {}", e.getMessage());
                return "false";
            }
        }

        /**
         * 🔥 登录状态检测策略（原型实现）
         * 
         * 由于不确定 Gitee AI Chat 的具体页面结构，这里提供几种常见的检测方式：
         * 
         * 1. 检测登录按钮是否存在（未登录）
         * 2. 检测用户头像/用户名元素（已登录）
         * 3. 检测特定的登录表单（未登录）
         * 4. 通过 localStorage/Cookie 检测
         * 
         * ⚠️ 实际选择器需要根据真实页面调整
         */
        
        try {
            String currentUrl = page.url();
            if (currentUrl.contains("login") || currentUrl.contains("signin") || currentUrl.contains("sign_in")) {
                log.debug("🔍 [Gitee AI] 当前在登录页，用户未登录");
                return "false";
            }

            // 强登录态优先：编辑区可用时直接判登录，避免被页面中无关提示文案误伤。
            if (hasMainComposerReady(page)) {
                return "Gitee用户";
            }

            // 明确的扫码登录面板可见，判未登录。
            if (isWechatLoginPanelVisible(page)) {
                log.debug("🔍 [Gitee AI] 检测到扫码登录面板，用户未登录");
                return "false";
            }

            // 仅把“顶部主登录入口”作为未登录依据，避免弹层/客服提示里的“登录”文案造成误判。
            if (isPrimaryLoginButtonVisible(page)) {
                log.debug("🔍 [Gitee AI] 检测到顶部登录入口，用户未登录");
                return "false";
            }
            
            // 策略3：检测用户头像或用户名（已登录的标志）
            try {
                // 尝试获取用户名（如果能获取到用户名，说明已登录）
                Locator userNameElement = page.locator(".user-name, .username, [class*='username'], [class*='user-info']").first();
                if (userNameElement.count() > 0 && userNameElement.isVisible()) {
                    String userName = userNameElement.textContent().trim();
                    if (!userName.isEmpty() && !userName.equals("未登录") && !userName.equals("未登陆")) {
                        log.debug("✅ [Gitee AI] 已登录1，用户: {}", userName);
                        return userName;
                    }
                }
            } catch (Exception e) {
                log.trace("获取用户名异常: {}", e.getMessage());
            }
            
            // 策略4：检测用户头像（已登录的标志）
            try {
                Locator userAvatar = page.locator(".user-avatar, .avatar, [class*='avatar']").first();
                if (userAvatar.count() > 0 && userAvatar.isVisible()) {
                    log.debug("✅ [Gitee AI] 已登录（检测到头像）2");
                    return "Gitee用户";
                }
            } catch (Exception e) {
                log.trace("检测用户头像异常: {}", e.getMessage());
            }
            
            // 再次兜底：避免首次渲染抖动导致误判。
            if (hasMainComposerReady(page)) {
                return "Gitee用户";
            }
            log.warn("⚠️ [Gitee AI] 无法确定登录状态，默认返回未登录（URL: {}）", currentUrl);
            return "false";
            
        } catch (Exception e) {
            log.error("❌ [Gitee AI] 登录状态检测失败: {}", e.getMessage());
            return "false";
        }
    }

    private boolean hasMainComposerReady(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const isVisible = (el) => {
                    if (!el) return false;
                    const st = window.getComputedStyle(el);
                    const r = el.getBoundingClientRect();
                    return r.width > 40 && r.height > 20 && st.display !== 'none' && st.visibility !== 'hidden';
                  };
                  const input = document.querySelector('textarea, input[type="text"], div[contenteditable="true"]');
                  if (!isVisible(input)) return false;
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  if (body.includes('扫码登录') || body.includes('微信登录')) return false;
                  return true;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isWechatLoginPanelVisible(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const isVisible = (el) => {
                    if (!el) return false;
                    const st = window.getComputedStyle(el);
                    const r = el.getBoundingClientRect();
                    return r.width > 20 && r.height > 20 && st.display !== 'none' && st.visibility !== 'hidden';
                  };
                  const qrSel = [
                    '.js_qrcode_img.web_qrcode_img',
                    '.web_qrcode_img_wrap',
                    '.js_normal_login.web_qrcode_img_area',
                    'iframe[src*="open.weixin.qq.com"]',
                    'img[src*="qrcode"], img[class*="qrcode"], canvas'
                  ];
                  for (const sel of qrSel) {
                    const el = document.querySelector(sel);
                    if (isVisible(el)) return true;
                  }
                  const body = (document.body && document.body.innerText) ? document.body.innerText : '';
                  return body.includes('扫码登录') || body.includes('微信登录');
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isPrimaryLoginButtonVisible(Page page) {
        try {
            Object o = page.evaluate("""
                () => {
                  const labels = ['登录', '立即登录', '去登录', '登录/注册'];
                  const nodes = document.querySelectorAll('button, a, [role="button"]');
                  for (const el of nodes) {
                    const txt = (el.innerText || '').trim();
                    if (!labels.includes(txt)) continue;
                    const st = window.getComputedStyle(el);
                    const r = el.getBoundingClientRect();
                    if (r.width < 36 || r.height < 20 || st.display === 'none' || st.visibility === 'hidden') continue;
                    // 仅视为“主登录入口”：顶部区域，避免对话/弹层中无关提示文案干扰。
                    if (r.top < 260) return true;
                  }
                  return false;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean locatorLooksLikeQrContainer(Locator locator) {
        try {
            Object o = locator.evaluate("""
                (el) => {
                  const st = window.getComputedStyle(el);
                  const rect = el.getBoundingClientRect();
                  if (st.display === 'none' || st.visibility === 'hidden') return false;
                  if (rect.width < 80 || rect.height < 80) return false;
                  if (el.tagName === 'IMG' || el.tagName === 'CANVAS') return true;
                  const childQr = el.querySelector('img[src*="qr"], img[class*="qr"], img[src*="qrcode"], canvas, iframe[src*="open.weixin.qq.com"]');
                  return !!childQr;
                }
                """);
            return Boolean.TRUE.equals(o);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 功能2：导航到 Gitee AI Chat 登录页
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 
     * @param page Playwright 页面对象
     * @return 是否导航成功
     */
    public boolean navigateToLoginPage(Page page) {
        try {
            log.info("📍 [Gitee AI] 开始导航到登录页");

            page.navigate(GITEE_AI_LOGIN_URL, new Page.NavigateOptions().setTimeout(15000));
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(15000));
            page.waitForTimeout(2000);
            
            log.info("✅ [Gitee AI] Gitee AI主页加载完成，开始寻找登录入口");

            boolean loginEntryClicked = false;

            // 方案1：兼容旧版页面的「未登录/未登陆」入口文案
            try {
                Locator loginEntry = page.locator("text=未登录, text=未登陆").first();
                if (loginEntry.count() > 0 && loginEntry.isVisible()) {
                    loginEntry.click();
                    loginEntryClicked = true;
                    log.info("✅ [Gitee AI] 已点击「未登录」入口，等待登录界面加载");
                }
            } catch (Exception e) {
                log.debug("[Gitee AI] 点击「未登录」入口失败: {}", e.getMessage());
            }

            // 方案2：当前页面直接显示登录按钮
            if (!loginEntryClicked) {
                try {
                    Locator directLoginButton = page.locator(
                        "button:has-text('登录'), a:has-text('登录'), button:has-text('立即登录'), [role='button']:has-text('登录')"
                    ).first();
                    if (directLoginButton.count() > 0 && directLoginButton.isVisible()) {
                        log.info("🔍 [Gitee AI] 找到直接登录按钮，尝试点击");
                        try {
                            directLoginButton.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                        } catch (Exception clickError) {
                            log.debug("[Gitee AI] 常规点击登录按钮失败，改用JavaScript点击: {}", clickError.getMessage());
                            directLoginButton.evaluate("el => el.click()");
                        }
                        loginEntryClicked = true;
                        log.info("✅ [Gitee AI] 已点击直接登录按钮，等待登录界面加载");
                    }
                } catch (Exception e) {
                    log.debug("[Gitee AI] 点击直接登录按钮失败: {}", e.getMessage());
                }
            }

            if (loginEntryClicked) {
                // 等待页面加载和可能的重定向
                page.waitForTimeout(3000);

                // 检查是否重定向到 gitee.com 首页（说明已登录gitee.com）
                String currentUrl = page.url();
                if (currentUrl.equals("https://gitee.com/")) {
                    log.info("🔍 [Gitee AI] 检测到重定向到 gitee.com 首页，说明已登录 gitee.com");
                    log.info("📍 [Gitee AI] 重新导航到 chat.gitee.com");

                    // 重新导航到 chat.gitee.com
                    page.navigate(GITEE_AI_HOME_URL, new Page.NavigateOptions().setTimeout(15000));
                    page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(15000));
                    page.waitForTimeout(2000);

                    // 检查是否已经登录 chat.gitee.com
                    String loginStatus = checkLoginStatus(page, false);
                    if (!"false".equals(loginStatus)) {
                        log.info("✅ [Gitee AI] 已自动登录 chat.gitee.com，用户: {}", loginStatus);
                        return true;
                    } else {
                        log.warn("⚠️ [Gitee AI] 已登录 gitee.com，但 chat.gitee.com 仍未登录");
                    }
                }

                // 点击微信登录按钮
                try {
                    log.info("📍 [Gitee AI] 开始寻找并点击微信登录按钮");

                    // 步骤1：点击三点菜单按钮展开更多登录选项
                    try {
                        Locator moreButton = page.locator(".git-other-login-icon").first();
                        if (moreButton.count() > 0) {
                            log.info("🔍 [Gitee AI] 找到三点菜单按钮，尝试悬浮展开");
                            moreButton.hover();
                            log.info("✅ [Gitee AI] 已悬浮在三点菜单按钮上");
                            page.waitForTimeout(1000);
                        } else {
                            log.warn("⚠️ [Gitee AI] 未找到三点菜单按钮");
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ [Gitee AI] 悬浮三点菜单按钮失败: {}", e.getMessage());
                    }

                    // 步骤2：定位并点击微信登录链接
                    boolean wechatLinkClicked = false;

                    try {
                        Locator wechatIcon = page.locator(".icon-logo_wechat.iconfont.wechat").first();
                        if (wechatIcon.count() > 0) {
                            log.info("🔍 [Gitee AI] 找到微信登录图标按钮，尝试通过JavaScript点击");
                            wechatIcon.evaluate("el => el.click()");
                            log.info("✅ [Gitee AI] 已通过JavaScript点击微信登录图标按钮");
                            wechatLinkClicked = true;
                        } else {
                            log.warn("⚠️ [Gitee AI] 未找到微信登录图标按钮");
                        }
                    } catch (Exception e) {
                        log.warn("⚠️ [Gitee AI] 通过JavaScript点击微信登录图标按钮失败: {}", e.getMessage());
                    }

                    // 方案3：直接点击包含“微信”的登录入口
                    if (!wechatLinkClicked) {
                        try {
                            Locator wechatLoginEntry = page.locator("text=微信, button:has-text('微信'), a:has-text('微信')").first();
                            if (wechatLoginEntry.count() > 0 && wechatLoginEntry.isVisible()) {
                                log.info("🔍 [Gitee AI] 找到包含“微信”的登录入口，尝试点击");
                                try {
                                    wechatLoginEntry.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                                } catch (Exception clickError) {
                                    log.debug("[Gitee AI] 常规点击微信入口失败，改用JavaScript点击: {}", clickError.getMessage());
                                    wechatLoginEntry.evaluate("el => el.click()");
                                }
                                wechatLinkClicked = true;
                                log.info("✅ [Gitee AI] 已点击包含“微信”的登录入口");
                            }
                        } catch (Exception e) {
                            log.warn("⚠️ [Gitee AI] 点击包含“微信”的登录入口失败: {}", e.getMessage());
                        }
                    }

                    if (!wechatLinkClicked) {
                        log.warn("⚠️ [Gitee AI] 无法点击微信登录链接");
                    }

                    log.info("⏳ [Gitee AI] 等待微信登录页面加载");
                    page.waitForTimeout(3000);

                } catch (Exception e) {
                    log.warn("⚠️ [Gitee AI] 点击微信登录按钮失败: {}", e.getMessage());
                }
            } else {
                log.info("⚠️ [Gitee AI] 未找到明确登录入口，尝试检查是否已经登录");
                String loginStatus = checkLoginStatus(page, false);
                if (!"false".equals(loginStatus)) {
                    log.info("✅ [Gitee AI] 检测到已登录，用户: {}", loginStatus);
                    return true;
                }
                log.warn("⚠️ [Gitee AI] 当前仍未登录，后续将尝试直接获取二维码区域");
            }
            
            return true;

        } catch (Exception e) {
            log.error("❌ [Gitee AI] 导航到登录页失败: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 切换Gitee对话模式（开源探索 / 仓库问答 / 帮助中心）
     */
    public ModeApplyResult applyConversationMode(Page page,
                                                 boolean enableOpenSourceExploration,
                                                 boolean enableRepositoryQA,
                                                 boolean enableHelpCenter,
                                                 String repositoryName) {
        int selectedModeCount = (enableOpenSourceExploration ? 1 : 0)
            + (enableRepositoryQA ? 1 : 0)
            + (enableHelpCenter ? 1 : 0);

        if (selectedModeCount == 0) {
            return new ModeApplyResult("normal", new ArrayList<>(), null);
        }
        if (selectedModeCount > 1) {
            throw new RuntimeException("Gitee模式互斥：开源探索/仓库问答/帮助中心只能选择一个");
        }

        if (enableRepositoryQA) {
            return activateRepositoryQAMode(page, repositoryName);
        }

        String modeText = enableOpenSourceExploration ? "开源探索" : "帮助中心";
        activateSimpleMode(page, modeText);
        return new ModeApplyResult(modeText, new ArrayList<>(), null);
    }

    /**
     * 探测「仓库问答 -> 选择仓库」弹窗中的仓库列表（用于前端下拉动态映射）
     */
    public List<String> detectRepositoryChoices(Page page) {
        Set<String> options = new LinkedHashSet<>();
        try {
            // 先尽量进入“仓库问答”态，再触发“选择仓库”弹窗
            boolean repoQaClicked = clickRepositoryQaEntry(page);
            page.waitForTimeout(600);

            // 若尚未出现弹窗，尝试点击“选择仓库”入口
            if (!isTextVisible(page, "选择仓库")) {
                clickRepositoryPickerEntry(page);
                page.waitForTimeout(500);
            }

            if (!isTextVisible(page, "选择仓库")) {
                log.warn("[Gitee AI] 探测仓库列表失败：未能打开“选择仓库”弹窗，repoQaClicked={}", repoQaClicked);
                return new ArrayList<>(options);
            }

            // 先读取当前已选仓库（弹窗里的选择框文本）
            String[] selectedRepoSelectors = {
                "[role='combobox']",
                ".ant-select-selection-item",
                ".el-select .el-input__inner",
                "input[placeholder*='选择仓库']"
            };
            for (String selector : selectedRepoSelectors) {
                try {
                    Locator selected = page.locator(selector).first();
                    if (selected.count() > 0 && selected.isVisible()) {
                        String text = selected.textContent();
                        String normalized = normalizeRepositoryText(text);
                        if (normalized != null) {
                            options.add(normalized);
                        }
                    }
                } catch (Exception ignore) {
                    // 忽略当前选择器
                }
            }

            // 点击选择框展开候选仓库列表
            String[] dropdownTriggers = {
                "[role='combobox']",
                ".ant-select-selector",
                "input[placeholder*='选择仓库']",
                "span:has-text('选择仓库')"
            };
            for (String selector : dropdownTriggers) {
                try {
                    Locator trigger = page.locator(selector).first();
                    if (trigger.count() > 0 && trigger.isVisible()) {
                        trigger.click(new Locator.ClickOptions().setTimeout(2500).setForce(true));
                        page.waitForTimeout(500);
                        break;
                    }
                } catch (Exception ignore) {
                    // 尝试下一个触发器
                }
            }

            // 抓取下拉仓库项文本
            String[] optionSelectors = {
                "[role='option']",
                ".ant-select-item-option-content",
                ".el-select-dropdown__item",
                "li",
                "div[class*='option']"
            };
            for (String selector : optionSelectors) {
                try {
                    Locator items = page.locator(selector);
                    int count = Math.min(items.count(), 40);
                    for (int i = 0; i < count; i++) {
                        Locator item = items.nth(i);
                        if (!item.isVisible()) {
                            continue;
                        }
                        String text = item.textContent();
                        String normalized = normalizeRepositoryText(text);
                        if (normalized == null) {
                            continue;
                        }
                        options.add(normalized);
                    }
                } catch (Exception ignore) {
                    // 尝试下一个选择器
                }
            }

            // 关闭弹窗，避免影响后续对话
            closeRepositoryDialog(page);

        } catch (Exception e) {
            log.warn("[Gitee AI] 探测仓库列表失败: {}", e.getMessage());
        }

        // 兜底默认值
        if (options.isEmpty()) {
            options.add("U3W-AI/U3W-AI");
        }
        return new ArrayList<>(options);
    }

    // 兼容旧调用名
    public List<String> detectRepositorySubOptions(Page page) {
        return detectRepositoryChoices(page);
    }

    /**
     * 上传文件到Gitee AI
     */
    public boolean uploadFile(Page page, String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!path.toFile().exists()) {
                log.error("[Gitee文件上传] 文件不存在: {}", filePath);
                return false;
            }

            // 优先使用 input[type=file] 直接上传
            try {
                Locator fileInput = page.locator("input[type='file']");
                if (fileInput.count() > 0) {
                    fileInput.first().setInputFiles(path);
                    page.waitForTimeout(1200);
                    log.info("[Gitee文件上传] 已通过 input[type=file] 上传");
                    return true;
                }
            } catch (Exception e) {
                log.debug("[Gitee文件上传] input[type=file] 上传失败: {}", e.getMessage());
            }

            // 兜底：点击上传入口触发 file chooser
            String[] uploadTriggers = {
                "button:has-text('上传')",
                "[role='button']:has-text('上传')",
                "button[aria-label*='上传']",
                "[class*='upload']",
                "[class*='attach']",
                "button:has-text('+')"
            };
            for (String selector : uploadTriggers) {
                try {
                    Locator trigger = page.locator(selector).first();
                    if (trigger.count() > 0 && trigger.isVisible()) {
                        page.waitForFileChooser(() -> trigger.click(
                            new Locator.ClickOptions().setTimeout(5000).setForce(true)
                        )).setFiles(path);
                        page.waitForTimeout(1200);
                        log.info("[Gitee文件上传] 已通过触发器上传: {}", selector);
                        return true;
                    }
                } catch (Exception e) {
                    log.debug("[Gitee文件上传] 触发器失败 {}: {}", selector, e.getMessage());
                }
            }

            log.warn("[Gitee文件上传] 未找到可用上传入口");
            return false;
        } catch (Exception e) {
            log.error("[Gitee文件上传] 上传异常: {}", e.getMessage(), e);
            return false;
        }
    }


    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 功能3：发送消息并等待 AI 回复
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 
     * @param page Playwright 页面对象
     * @param query 用户问题
     * @return AI 回复内容
     */
    // 方法重载：保持向后兼容性
    public String sendMessageAndWaitResponse(Page page, String query) {
        return sendMessageAndWaitResponse(page, query, false, false);
    }
    
    public String sendMessageAndWaitResponse(Page page, String query, boolean enableOpenSourceExploration, boolean enableHelpCenter) {
        return sendMessageAndWaitResponse(page, query, enableOpenSourceExploration, false, enableHelpCenter, null);
    }

    public String sendMessageAndWaitResponse(Page page, String query, boolean enableOpenSourceExploration,
                                             boolean enableRepositoryQA, boolean enableHelpCenter,
                                             String repositoryName) {
        try {
            log.info("💬 [Gitee AI] 开始发送消息: {}", query);
            
            // 等待页面稳定
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);
            page.waitForTimeout(1000);
            log.debug("✅ [Gitee AI] 页面已稳定");
            
            applyConversationMode(page, enableOpenSourceExploration, enableRepositoryQA, enableHelpCenter, repositoryName);
            
            boolean inputSuccess = fillAndSendMessage(page, query);
            if (!inputSuccess) {
                log.error("❌ [Gitee AI] 发送消息失败：未找到输入框或发送失败");
                return null;
            }
            
            log.info("⏳ [Gitee AI] 开始监听回复");
            String content = waitForResponse(page);
            
            if (content != null && !content.isEmpty()) {
                log.info("✅ [Gitee AI] 回复接收完成，内容长度: {}", content.length());
                return content;
            } else {
                log.error("❌ [Gitee AI] 未能获取有效回复");
                return null;
            }
            
        } catch (Exception e) {
            log.error("❌ [Gitee AI] 发送消息失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private void activateSimpleMode(Page page, String modeText) {
        for (int i = 0; i < 3; i++) {
            if (clickVisibleTextOption(page, modeText)) {
                page.waitForTimeout(600);
                log.info("[Gitee AI] 模式切换成功: {}", modeText);
                return;
            }
            page.waitForTimeout(400);
        }
        throw new RuntimeException("Gitee模式切换失败，未找到可点击项: " + modeText);
    }

    private ModeApplyResult activateRepositoryQAMode(Page page, String repositoryName) {
        if (!clickRepositoryQaEntry(page)) {
            log.warn("[Gitee AI] 当前页面未找到“仓库问答”入口，尝试跳转首页后重试");
            try {
                page.navigate(GITEE_AI_HOME_URL, new Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(12000));
                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(8000));
                page.waitForTimeout(800);
            } catch (Exception e) {
                log.warn("[Gitee AI] 跳转首页重试仓库问答入口失败: {}", e.getMessage());
            }
            if (!clickRepositoryQaEntry(page)) {
                throw new RuntimeException("未找到“仓库问答”模式入口");
            }
        }
        page.waitForTimeout(600);

        RepositoryDialogResult dialogResult = handleRepositorySelectionDialog(page, repositoryName);
        if (dialogResult == null) {
            log.info("[Gitee AI] 仓库问答模式无仓库选择弹窗，按直接切换处理");
            return new ModeApplyResult("仓库问答", new ArrayList<>(), null);
        }

        // 校验页面上至少仍可见仓库问答文本，作为最小切换确认
        if (!isTextVisible(page, "仓库问答")) {
            throw new RuntimeException("仓库问答模式切换后未检测到模式标识");
        }
        return new ModeApplyResult("仓库问答", dialogResult.repositoryChoices, dialogResult.selectedRepository);
    }

    private RepositoryDialogResult handleRepositorySelectionDialog(Page page, String repositoryName) {
        if (!isTextVisible(page, "选择仓库")) {
            return null;
        }

        log.info("[Gitee AI] 检测到仓库选择弹窗，开始处理");
        page.waitForTimeout(400);
        List<String> detectedChoices = collectRepositoryChoicesFromDialog(page);
        String selectedRepository = extractCurrentRepositoryFromDialog(page);

        // 可选：按名称选择仓库
        if (repositoryName != null && !repositoryName.trim().isEmpty()) {
            boolean selected = trySelectRepository(page, repositoryName.trim());
            if (selected) {
                selectedRepository = repositoryName.trim();
            }
        }

        String[] confirmSelectors = {
            "button:has-text('确定')",
            "[role='button']:has-text('确定')",
            "button:has-text('确认')",
            ".ant-modal-footer .ant-btn-primary",
            ".ant-modal .ant-btn-primary",
            "button.ant-btn-primary",
            ".ant-btn-primary"
        };

        for (String selector : confirmSelectors) {
            try {
                Locator confirmBtn = page.locator(selector).first();
                if (confirmBtn.count() > 0 && confirmBtn.isVisible() && !confirmBtn.isDisabled()) {
                    confirmBtn.click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                    page.waitForTimeout(800);
                    if (!isTextVisible(page, "选择仓库")) {
                        log.info("[Gitee AI] 仓库选择已确认");
                        return new RepositoryDialogResult(detectedChoices, selectedRepository);
                    }
                    log.debug("[Gitee AI] 确认按钮点击后弹窗仍存在，继续尝试其他确认路径");
                }
            } catch (Exception e) {
                log.debug("[Gitee AI] 点击确认按钮失败 {}: {}", selector, e.getMessage());
            }
        }

        // 兜底1：对输入框回车提交（部分页面无明确“确定”按钮）
        String[] submitInputs = {
            "input[placeholder*='选择仓库']",
            "[role='combobox'] input",
            ".ant-select-selection-search-input"
        };
        for (String selector : submitInputs) {
            try {
                Locator input = page.locator(selector).first();
                if (input.count() > 0 && input.isVisible()) {
                    input.press("Enter", new Locator.PressOptions().setTimeout(2000));
                    page.waitForTimeout(700);
                    if (!isTextVisible(page, "选择仓库")) {
                        log.info("[Gitee AI] 仓库选择已通过回车确认");
                        return new RepositoryDialogResult(detectedChoices, selectedRepository);
                    }
                }
            } catch (Exception e) {
                log.debug("[Gitee AI] 回车确认仓库失败 {}: {}", selector, e.getMessage());
            }
        }

        // 兜底2：无法确认时尝试关闭弹窗并继续（保持当前默认仓库），避免整条链路失败
        closeRepositoryDialog(page);
        page.waitForTimeout(500);
        if (!isTextVisible(page, "选择仓库")) {
            log.warn("[Gitee AI] 未找到可点击确认按钮，已关闭弹窗并继续使用当前仓库");
            return new RepositoryDialogResult(detectedChoices, selectedRepository);
        }

        try {
            page.keyboard().press("Escape");
            page.waitForTimeout(500);
            if (!isTextVisible(page, "选择仓库")) {
                log.warn("[Gitee AI] 未找到确认按钮，已通过Esc关闭弹窗并继续");
                return new RepositoryDialogResult(detectedChoices, selectedRepository);
            }
        } catch (Exception e) {
            log.debug("[Gitee AI] Esc关闭弹窗失败: {}", e.getMessage());
        }

        // 最后降级：不再抛错中断，保留默认仓库继续发问
        log.warn("[Gitee AI] 仓库问答弹窗存在但无法确认，将继续使用当前仓库进行对话");
        return new RepositoryDialogResult(detectedChoices, selectedRepository);
    }

    private boolean trySelectRepository(Page page, String repositoryName) {
        String[] selectTriggers = {
            "[class*='select']:has-text('选择仓库')",
            "[class*='selector']",
            "[role='combobox']",
            "input[placeholder*='选择仓库']"
        };

        for (String triggerSelector : selectTriggers) {
            try {
                Locator trigger = page.locator(triggerSelector).first();
                if (trigger.count() > 0 && trigger.isVisible()) {
                    trigger.click(new Locator.ClickOptions().setTimeout(4000).setForce(true));
                    page.waitForTimeout(300);

                    String[] optionSelectors = {
                        "li:has-text('" + repositoryName + "')",
                        "[role='option']:has-text('" + repositoryName + "')",
                        "div:has-text('" + repositoryName + "')"
                    };

                    for (String optionSelector : optionSelectors) {
                        Locator option = page.locator(optionSelector).first();
                        if (option.count() > 0 && option.isVisible()) {
                            option.click(new Locator.ClickOptions().setTimeout(4000).setForce(true));
                            page.waitForTimeout(300);
                            log.info("[Gitee AI] 仓库已选择: {}", repositoryName);
                            return true;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[Gitee AI] 仓库选择触发器失败 {}: {}", triggerSelector, e.getMessage());
            }
        }

        log.warn("[Gitee AI] 未成功按名称选择仓库，继续使用默认仓库: {}", repositoryName);
        return false;
    }

    private List<String> collectRepositoryChoicesFromDialog(Page page) {
        Set<String> options = new LinkedHashSet<>();
        String currentSelected = extractCurrentRepositoryFromDialog(page);
        if (currentSelected != null && !currentSelected.isEmpty()) {
            options.add(currentSelected);
        }

        String[] dropdownTriggers = {
            "[role='combobox']",
            ".ant-select-selector",
            "input[placeholder*='选择仓库']",
            "span:has-text('选择仓库')"
        };
        for (String selector : dropdownTriggers) {
            try {
                Locator trigger = page.locator(selector).first();
                if (trigger.count() > 0 && trigger.isVisible()) {
                    trigger.click(new Locator.ClickOptions().setTimeout(2500).setForce(true));
                    page.waitForTimeout(400);
                    break;
                }
            } catch (Exception ignore) {
                // 尝试下一个触发器
            }
        }

        String[] optionSelectors = {
            "[role='option']",
            ".ant-select-item-option-content",
            ".el-select-dropdown__item",
            "li",
            "div[class*='option']"
        };
        for (String selector : optionSelectors) {
            try {
                Locator items = page.locator(selector);
                int count = Math.min(items.count(), 40);
                for (int i = 0; i < count; i++) {
                    Locator item = items.nth(i);
                    if (!item.isVisible()) {
                        continue;
                    }
                    String normalized = normalizeRepositoryText(item.textContent());
                    if (normalized != null) {
                        options.add(normalized);
                    }
                }
            } catch (Exception ignore) {
                // 忽略该选择器
            }
        }
        return new ArrayList<>(options);
    }

    private String extractCurrentRepositoryFromDialog(Page page) {
        String[] selectedRepoSelectors = {
            ".ant-select-selection-item",
            "[role='combobox']",
            ".el-select .el-input__inner",
            "input[placeholder*='选择仓库']"
        };
        for (String selector : selectedRepoSelectors) {
            try {
                Locator selected = page.locator(selector).first();
                if (selected.count() > 0 && selected.isVisible()) {
                    String normalized = normalizeRepositoryText(selected.textContent());
                    if (normalized != null) {
                        return normalized;
                    }
                }
            } catch (Exception ignore) {
                // 尝试下一个
            }
        }
        return null;
    }

    private boolean clickVisibleTextOption(Page page, String text) {
        String[] selectors = {
            "button:has-text('" + text + "')",
            "[role='button']:has-text('" + text + "')",
            "span:has-text('" + text + "')",
            "div:has-text('" + text + "')",
            "a:has-text('" + text + "')"
        };

        for (String selector : selectors) {
            try {
                Locator locator = page.locator(selector).first();
                if (locator.count() > 0 && locator.isVisible(new Locator.IsVisibleOptions().setTimeout(1200))) {
                    locator.click(new Locator.ClickOptions().setTimeout(4000).setForce(true));
                    return true;
                }
            } catch (Exception ignore) {
                // 尝试下一个选择器
            }
        }

        return false;
    }

    private boolean isTextVisible(Page page, String text) {
        try {
            Locator locator = page.locator("text=" + text).first();
            return locator.count() > 0 && locator.isVisible(new Locator.IsVisibleOptions().setTimeout(1200));
        } catch (Exception e) {
            return false;
        }
    }

    private String normalizeRepositoryText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 80) {
            return null;
        }
        if (normalized.contains("仓库问答") || normalized.contains("切换人设")
            || normalized.contains("选择仓库") || normalized.contains("确定")
            || normalized.contains("取消") || normalized.contains("默认")) {
            return null;
        }
        if (!normalized.contains("/") && !normalized.contains("-") && normalized.length() < 3) {
            return null;
        }
        return normalized;
    }

    private void closeRepositoryDialog(Page page) {
        String[] closeSelectors = {
            "button:has-text('取消')",
            "[role='button']:has-text('取消')",
            "button[aria-label='Close']",
            ".ant-modal-close"
        };
        for (String selector : closeSelectors) {
            try {
                Locator close = page.locator(selector).first();
                if (close.count() > 0 && close.isVisible()) {
                    close.click(new Locator.ClickOptions().setTimeout(1500).setForce(true));
                    page.waitForTimeout(300);
                    return;
                }
            } catch (Exception ignore) {
                // 尝试下一个关闭按钮
            }
        }
    }

    private boolean clickRepositoryQaEntry(Page page) {
        // 路径1：直接可见“仓库问答”
        if (clickVisibleTextOption(page, "仓库问答")) {
            return true;
        }

        // 路径2：先展开“切换人设”下拉，再点“仓库问答”
        String[] personaTriggers = {
            ".ant-dropdown-trigger:has-text('切换人设')",
            "button:has-text('切换人设')",
            "[role='button']:has-text('切换人设')",
            "span:has-text('切换人设')"
        };
        for (String selector : personaTriggers) {
            try {
                Locator trigger = page.locator(selector).first();
                if (trigger.count() > 0 && trigger.isVisible()) {
                    trigger.click(new Locator.ClickOptions().setTimeout(3500).setForce(true));
                    page.waitForTimeout(400);
                    if (clickVisibleTextOption(page, "仓库问答")) {
                        return true;
                    }
                }
            } catch (Exception ignore) {
                // 尝试下一个入口
            }
        }
        return false;
    }

    private void clickRepositoryPickerEntry(Page page) {
        String[] selectors = {
            "button:has-text('选择仓库')",
            "[role='button']:has-text('选择仓库')",
            "span:has-text('选择仓库')",
            "input[placeholder*='选择仓库']"
        };
        for (String selector : selectors) {
            try {
                Locator entry = page.locator(selector).first();
                if (entry.count() > 0 && entry.isVisible()) {
                    entry.click(new Locator.ClickOptions().setTimeout(2500).setForce(true));
                    return;
                }
            } catch (Exception ignore) {
                // 尝试下一个入口
            }
        }
    }
    
    /**
     * 填充并发送消息
     * 
     * @param page Playwright页面对象
     * @param query 用户问题
     * @return 是否发送成功
     */
    private boolean fillAndSendMessage(Page page, String query) {
        try {
            // 步骤1：定位输入框
            Locator inputBox = null;
            boolean inputFound = false;
            
            // 尝试多种选择器
            String[] inputSelectors = {
                "textarea",
                "input[type='text']",
                "textarea[placeholder*='问题']",
                "textarea[placeholder*='消息']",
                "textarea[placeholder*='Ask']",
                "textarea[placeholder*='Message']"
            };
            
            for (String selector : inputSelectors) {
                try {
                    inputBox = page.locator(selector).first();
                    if (inputBox.count() > 0 && inputBox.isVisible()) {
                        inputFound = true;
                        log.debug("✅ [Gitee AI] 使用选择器找到输入框: {}", selector);
                        break;
                    }
                } catch (Exception e) {
                    // 继续尝试下一个选择器
                }
            }
            
            if (!inputFound) {
                try {
                    Object jsResult = page.evaluate("""
                        () => {
                            const textareas = document.querySelectorAll('textarea');
                            for (const textarea of textareas) {
                                if (textarea.placeholder && 
                                    (textarea.placeholder.includes('问题') || 
                                     textarea.placeholder.includes('消息') ||
                                     textarea.placeholder.includes('Ask') ||
                                     textarea.placeholder.includes('Message'))) {
                                    textarea.setAttribute('data-ai-input', 'true');
                                    return true;
                                }
                            }
                            return false;
                        }
                    """);
                    
                    if (Boolean.TRUE.equals(jsResult)) {
                        inputBox = page.locator("textarea[data-ai-input='true']").first();
                        if (inputBox.count() > 0 && inputBox.isVisible()) {
                            inputFound = true;
                            log.debug("✅ [Gitee AI] 通过JavaScript找到输入框");
                        }
                    }
                } catch (Exception e) {
                    log.error("❌ [Gitee AI] JavaScript查找输入框失败", e);
                }
            }
            
            if (inputFound && inputBox != null) {
                inputBox.click();
                page.waitForTimeout(500);
                
                inputBox.fill("");
                page.waitForTimeout(200);
                
                inputBox.fill(query);
                log.debug("✅ [Gitee AI] 问题已填入输入框");
                
                // 点击发送按钮
                try {
                    Locator sendButton = page.locator("button:has-text('发送'), button[type='submit'], button:has-text('Send')").first();
                    if (sendButton.count() > 0 && sendButton.isVisible()) {
                        sendButton.click();
                        log.debug("✅ [Gitee AI] 发送按钮已点击");
                    } else {
                        inputBox.press("Enter");
                        log.debug("✅ [Gitee AI] 已按 Enter 键发送");
                    }
                } catch (Exception e) {
                    log.warn("点击发送按钮失败，尝试按 Enter: {}", e.getMessage());
                    inputBox.press("Enter");
                }
                
                page.waitForTimeout(2000);
                log.info("✅ [Gitee AI] 消息发送成功");
                return true;
            } else {
                log.error("❌ [Gitee AI] 未找到输入框");
                return false;
            }
        } catch (Exception e) {
            log.error("❌ [Gitee AI] 填充或发送消息失败", e);
            return false;
        }
    }
    
    /**
     * 定位微信二维码区域
     * 
     * @param page Playwright页面对象
     * @return 微信二维码区域的Locator，如果未找到返回null
     */
    public com.microsoft.playwright.Locator locateWechatQrCode(Page page) {
        try {
            log.info("🔍 [Gitee AI] 开始定位微信二维码区域");
            
            // 策略1：根据用户提供的实际页面结构，直接定位二维码图片
            try {
                com.microsoft.playwright.Locator qrCodeImg = page.locator(".js_qrcode_img.web_qrcode_img").first();
                if (qrCodeImg.count() > 0 && qrCodeImg.isVisible()) {
                    log.info("✅ [Gitee AI] 找到微信二维码图片: .js_qrcode_img.web_qrcode_img");
                    return qrCodeImg;
                }
            } catch (Exception e) {
                log.debug("⚠️ [Gitee AI] 未找到微信二维码图片: {}", e.getMessage());
            }
            
            // 策略2：定位二维码图片的直接父容器
            try {
                com.microsoft.playwright.Locator imgWrap = page.locator(".web_qrcode_img_wrap").first();
                if (imgWrap.count() > 0 && imgWrap.isVisible()) {
                    log.info("✅ [Gitee AI] 找到微信二维码图片容器: .web_qrcode_img_wrap");
                    return imgWrap;
                }
            } catch (Exception e) {
                log.debug("⚠️ [Gitee AI] 未找到微信二维码图片容器: {}", e.getMessage());
            }
            
            // 策略3：定位二维码区域的更大容器
            try {
                com.microsoft.playwright.Locator imgArea = page.locator(".js_normal_login.web_qrcode_img_area").first();
                if (imgArea.count() > 0 && imgArea.isVisible()) {
                    log.info("✅ [Gitee AI] 找到微信二维码区域: .js_normal_login.web_qrcode_img_area");
                    return imgArea;
                }
            } catch (Exception e) {
                log.debug("⚠️ [Gitee AI] 未找到微信二维码区域: {}", e.getMessage());
            }
            
            // 策略4：查找微信登录iframe
            try {
                com.microsoft.playwright.Locator iframeLocator = page.locator("iframe[src*='open.weixin.qq.com']").first();
                if (iframeLocator.count() > 0 && iframeLocator.isVisible()) {
                    log.info("✅ [Gitee AI] 找到微信登录iframe");
                    return iframeLocator;
                }
            } catch (Exception e) {
                log.debug("⚠️ [Gitee AI] 未找到微信登录iframe: {}", e.getMessage());
            }
            
            // 策略5：查找包含"微信"文字的区域
            try {
                com.microsoft.playwright.Locator wechatTextArea = page.locator("text=微信").first();
                if (wechatTextArea.count() > 0 && wechatTextArea.isVisible()) {
                    // 尝试找到包含该文字的父容器
                    com.microsoft.playwright.Locator parentContainer = wechatTextArea.locator("..").first();
                    if (parentContainer.count() > 0 && locatorLooksLikeQrContainer(parentContainer)) {
                        log.info("✅ [Gitee AI] 找到包含'微信'且含二维码元素的父容器");
                        return parentContainer;
                    }
                }
            } catch (Exception e) {
                log.debug("⚠️ [Gitee AI] 未找到包含'微信'文字的区域: {}", e.getMessage());
            }
            
            // 策略6：查找通用的登录二维码容器
            try {
                String[] qrCodeSelectors = {
                    ".qrcode",
                    ".qr-code",
                    "[class*='qrcode']",
                    "[class*='qr-code']",
                    ".login-qrcode",
                    ".wechat-qrcode"
                };
                
                for (String selector : qrCodeSelectors) {
                    com.microsoft.playwright.Locator qrCodeLocator = page.locator(selector).first();
                    if (qrCodeLocator.count() > 0 && qrCodeLocator.isVisible()
                        && locatorLooksLikeQrContainer(qrCodeLocator)) {
                        log.info("✅ [Gitee AI] 找到二维码容器: {}", selector);
                        return qrCodeLocator;
                    }
                }
            } catch (Exception e) {
                log.debug("⚠️ [Gitee AI] 未找到通用二维码容器: {}", e.getMessage());
            }
            
            log.warn("❌ [Gitee AI] 未找到微信二维码区域");
            return null;
            
        } catch (Exception e) {
            log.error("❌ [Gitee AI] 定位微信二维码区域失败: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * 等待Gitee AI回答完成并提取内容
     * 
     * @param page Playwright页面实例
     * @return 获取的回答内容
     */
    private String waitForResponse(Page page) {
        try {
            // 🔥 改进的等待逻辑：持续检测内容稳定性
            String currentContent = "";
            String lastContent = "";
            int stableCount = 0;
            int noChangeCount = 0;
            int[] contentLengthHistory = new int[3];
            boolean hasEverHadContent = false;
            
            long startTime = System.currentTimeMillis();
            long maxTimeout = 240000; // 4分钟超时
            int requiredStableCount = 3; // 需要连续3次检测到内容不变
            int checkInterval = 500; // 每500ms检查一次
            
            page.waitForTimeout(3000); // 等待AI开始生成
            log.info("[Gitee AI] 开始检测回复完成状态");
            
            while (true) {
                if (page.isClosed()) {
                    log.error("[Gitee AI] 页面已关闭，停止监听");
                    throw new RuntimeException("页面在监控过程中被关闭");
                }
                
                long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime > maxTimeout) {
                    log.warn("[Gitee AI] 超时，AI未完成回答或回答时间过长");
                    break;
                }
                
                try {
                    // 提取当前内容
                    Locator proseContainer = page.locator(".n-prose, .prose-borderless, [class*='prose']").last();
                    if (proseContainer.count() > 0) {
                        currentContent = proseContainer.textContent().trim();
                    }
                    
                    int contentLength = currentContent.length();
                    
                    if (contentLength > 0) {
                        hasEverHadContent = true;
                        
                        // 记录内容长度历史
                        for (int i = contentLengthHistory.length - 1; i > 0; i--) {
                            contentLengthHistory[i] = contentLengthHistory[i-1];
                        }
                        contentLengthHistory[0] = contentLength;
                        
                        // 检测内容是否稳定
                        if (currentContent.equals(lastContent)) {
                            stableCount++;
                            noChangeCount++;
                            
                            // 检查是否还在生成（通过检测是否有加载指示器）
                            boolean isGenerating = checkIfGenerating(page);
                            
                            if (!isGenerating && stableCount >= requiredStableCount) {
                                log.info("[Gitee AI] 内容已稳定{}次，回复已完成", stableCount);
                                break;
                            } else if (isGenerating) {
                                log.debug("[Gitee AI] 检测到正在生成，继续等待... (稳定次数: {})", stableCount);
                            }
                        } else {
                            // 内容发生变化，重置计数器
                            if (lastContent.length() > 0) {
                                log.debug("[Gitee AI] 内容发生变化，长度: {} -> {}", lastContent.length(), contentLength);
                            }
                            stableCount = 0;
                            noChangeCount = 0;
                        }
                        
                        lastContent = currentContent;
                    } else {
                        if (hasEverHadContent) {
                            log.debug("[Gitee AI] 内容为空，但之前有内容，继续等待...");
                        }
                    }
                    
                    // 每10秒输出一次进度
                    if (elapsedTime % 10000 < checkInterval) {
                        log.info("[Gitee AI] 等待中... 已等待{}秒，当前内容长度: {}", elapsedTime / 1000, contentLength);
                    }
                    
                } catch (Exception e) {
                    log.trace("[Gitee AI] 检测回复状态异常（忽略）: {}", e.getMessage());
                }
                
                page.waitForTimeout(checkInterval);
            }
            
            log.info("[Gitee AI] AI回复检测完成，准备提取内容");
            page.waitForTimeout(1000); // 等待内容稳定
            
            // 提取 AI 回复内容
            log.info("📝 [Gitee AI] 开始提取回复内容");
            String aiResponse = extractGiteeResponse(page);
            
            if (aiResponse != null && !aiResponse.isEmpty()) {
                log.info("✅ [Gitee AI] 成功获取 AI 回复，长度: {}", aiResponse.length());
                return aiResponse;
            } else {
                log.error("❌ [Gitee AI] 未能提取到有效回复");
                return null;
            }
            
        } catch (Exception e) {
            log.error("❌ [Gitee AI] 等待响应失败", e);
            return null;
        }
    }

    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 功能：提取 Gitee AI 回复内容（参考 DeepSeek 的格式化处理）
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 
     * @param page Playwright页面对象
     * @return 格式化后的回复内容
     */
    private String extractGiteeResponse(Page page) {
        try {
            log.debug("[Gitee AI] 开始提取格式化的回复内容");
            
            Object jsResult = page.evaluate("""
            () => {
                try {
                    // 策略1：通过 prose 容器提取（优先）
                    const proseContainers = document.querySelectorAll('.n-prose, .prose-borderless, [class*="prose"]');
                    if (proseContainers.length === 0) {
                        return { content: '', source: 'no-prose-containers' };
                    }
                    
                    const latestContainer = proseContainers[proseContainers.length - 1];
                    const containerClone = latestContainer.cloneNode(true);
                    
                    // 移除不需要的元素
                    const elementsToRemove = containerClone.querySelectorAll(
                        'svg, button, [role="button"], ' +
                        '[class*="loading"], [class*="typing"], [class*="cursor"], ' +
                        '[class*="spinner"], [class*="icon"], ' +
                        '.n-spin, .n-loading, .loading-indicator'
                    );
                    elementsToRemove.forEach(el => el.remove());
                    
                    // 移除空的 div
                    const emptyDivs = containerClone.querySelectorAll('div:empty, span:empty, p:empty');
                    emptyDivs.forEach(div => div.remove());
                    
                    // 清理多余的空白
                    const cleanedContent = containerClone.innerHTML
                        .replace(/\\s+/g, ' ')
                        .replace(/<p>\\s*<\\/p>/g, '')
                        .replace(/<div>\\s*<\\/div>/g, '')
                        .trim();
                    
                    return {
                        content: cleanedContent,
                        source: 'prose-container-cleaned',
                        timestamp: Date.now()
                    };
                } catch (e) {
                    return {
                        content: '',
                        source: 'error',
                        error: e.toString()
                    };
                }
            }
            """);

            if (jsResult instanceof Map) {
                Map<String, Object> result = (Map<String, Object>) jsResult;
                String content = (String) result.getOrDefault("content", "");
                String source = (String) result.getOrDefault("source", "");
                
                if (!content.trim().isEmpty()) {
                    log.debug("[Gitee AI] 成功提取格式化内容，来源: {}", source);
                    return content;
                }
            }
            
            log.warn("[Gitee AI] 策略1失败，尝试备用策略");
            
            // 策略2：通过 content-wrapper 提取
            Object fallbackResult = page.evaluate("""
            () => {
                try {
                    const wrappers = document.querySelectorAll('.sipplebar-content-wrapper');
                    if (wrappers.length === 0) {
                        return { content: '', source: 'no-wrappers' };
                    }
                    
                    const latestWrapper = wrappers[wrappers.length - 1];
                    const wrapperClone = latestWrapper.cloneNode(true);
                    
                    // 移除不需要的元素
                    const elementsToRemove = wrapperClone.querySelectorAll(
                        'svg, button, [role="button"], ' +
                        '[class*="loading"], [class*="typing"], [class*="cursor"]'
                    );
                    elementsToRemove.forEach(el => el.remove());
                    
                    const cleanedContent = wrapperClone.innerHTML
                        .replace(/\\s+/g, ' ')
                        .trim();
                    
                    return {
                        content: cleanedContent,
                        source: 'wrapper-cleaned'
                    };
                } catch (e) {
                    return {
                        content: '',
                        source: 'error',
                        error: e.toString()
                    };
                }
            }
            """);
            
            if (fallbackResult instanceof Map) {
                Map<String, Object> result = (Map<String, Object>) fallbackResult;
                String content = (String) result.getOrDefault("content", "");
                String source = (String) result.getOrDefault("source", "");
                
                if (!content.trim().isEmpty()) {
                    log.debug("[Gitee AI] 备用策略成功，来源: {}", source);
                    return content;
                }
            }
            
            return "";
            
        } catch (Exception e) {
            log.error("[Gitee AI] 提取回复内容失败", e);
            return "";
        }
    }

    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 辅助方法：切换Gitee AI Chat模式（开源探索/帮助中心）
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     */
    private void toggleGiteeMode(Page page, String modeText, boolean shouldActive) {
        try {
            log.debug("[Gitee AI] 开始切换{}模式，目标状态: {}", modeText, shouldActive);
            
            // 第一步：找到"切换人设"按钮
            // 使用更精确的选择器，基于实际的class
            String toggleButtonSelector = ".ant-dropdown-trigger:has-text('切换人设')";
            Locator toggleButton = page.locator(toggleButtonSelector).first();
            
            try {
                // 等待"切换人设"按钮出现，最多等待5秒
                toggleButton.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                log.debug("[Gitee AI] 找到切换人设按钮");
            } catch (Exception e) {
                log.debug("[Gitee AI] 切换人设按钮未找到: {}", e.getMessage());
                return;
            }

            if (!toggleButton.isVisible()) {
                log.debug("[Gitee AI] 切换人设按钮不可见，跳过{}模式切换", modeText);
                return;
            }

            // 第二步：点击"切换人设"按钮展开下拉菜单
            // 先获取按钮的class，检查点击前状态
            String buttonClassBefore = (String) toggleButton.evaluate("el => el.className");
            log.debug("[Gitee AI] 切换人设按钮点击前class: {}", buttonClassBefore);
            
            // 尝试多种方式触发下拉菜单
            try {
                // 方法1：尝试点击div内部的文本元素
                try {
                    Locator textElement = toggleButton.locator("text='切换人设'");
                    if (textElement.count() > 0) {
                        log.debug("[Gitee AI] 找到div内部的文本元素，尝试点击");
                        textElement.first().click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                    } else {
                        // 方法2：直接点击div
                        log.debug("[Gitee AI] 未找到内部文本元素，直接点击div");
                        toggleButton.click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                    }
                } catch (Exception e) {
                    log.debug("[Gitee AI] 点击异常: {}", e.getMessage());
                    // 方法3：使用JavaScript点击
                    toggleButton.evaluate("el => el.click()");
                }
            } catch (Exception e) {
                log.debug("[Gitee AI] 触发下拉菜单异常: {}", e.getMessage());
            }
            
            log.debug("[Gitee AI] 等待下拉菜单展开");
            page.waitForTimeout(2000); // 增加等待时间，确保菜单完全展开
            
            // 检查按钮点击后的状态
            String buttonClassAfter = (String) toggleButton.evaluate("el => el.className");
            log.debug("[Gitee AI] 切换人设按钮点击后class: {}", buttonClassAfter);

            // 第三步：在下拉菜单中找到目标模式选项
            // 先检查下拉菜单是否真的展开了
            Locator menuItem = null;
            boolean found = false;
            
            try {
                // 查找所有下拉菜单容器
                Locator dropdownMenus = page.locator(".ant-dropdown, [class*='dropdown-menu'], [role='menu']");
                int menuCount = dropdownMenus.count();

                
                if (menuCount == 0) {
                    log.warn("[Gitee AI] 未找到下拉菜单容器，可能菜单未展开");
                    // 尝试再次点击按钮
                    toggleButton.click(new Locator.ClickOptions().setTimeout(2000).setForce(true));
                    page.waitForTimeout(1000);
                }
                
                // 查找所有菜单项
                Locator allMenuItems = page.locator(".ant-dropdown-menu-item, [class*='dropdown-menu-item'], [class*='menu-item'], [role='menuitem']");
                int count = allMenuItems.count();
                
                // 如果还是没找到，尝试查找所有可见的元素
                if (count == 0) {
                    Locator allVisibleElements = page.locator("*:visible");
                    int visibleCount = allVisibleElements.count();
                    
                    // 查找包含目标文本的元素
                    for (int i = 0; i < visibleCount; i++) {
                        try {
                            Locator element = allVisibleElements.nth(i);
                            String text = element.textContent();
                            if (text != null && text.contains(modeText)) {
                                break;
                            }
                        } catch (Exception e) {
                            // 忽略异常
                        }
                    }
                }
                
                // 遍历菜单项，查找包含目标文本的项
                Locator targetMenuItem = null;
                boolean isCurrentMode = false;
                for (int i = 0; i < count; i++) {
                    try {
                        Locator currentMenuItem = allMenuItems.nth(i);
                        String textContent = currentMenuItem.textContent();
                        String className = (String) currentMenuItem.evaluate("el => el.className");
                        
                        if (textContent.contains(modeText)) {
                            targetMenuItem = currentMenuItem;
                            
                            // 检查是否是当前激活的模式（通过class或其他属性）
                            if (className.contains("active") || className.contains("selected") || className.contains("current") || className.contains("checked")) {
                                isCurrentMode = true;
                                log.debug("[Gitee AI] {}已经是当前激活的模式，无需切换", modeText);
                            }
                            break;
                        }
                    } catch (Exception e) {
                        // 忽略异常
                    }
                }
                
                // 如果已经是当前模式，直接关闭菜单并返回
                if (isCurrentMode) {
                    log.debug("[Gitee AI] 无需切换模式，关闭下拉菜单");
                    // 关闭下拉菜单
                    try {
                        toggleButton.click(new Locator.ClickOptions().setTimeout(2000).setForce(true));
                        page.waitForTimeout(500);
                    } catch (Exception e) {
                        // 忽略关闭失败的异常
                    }
                    return;
                }
                
                if (targetMenuItem != null && targetMenuItem.isVisible()) {
                    // 找到目标菜单项，使用与切换人设按钮相同的多策略点击方法
                    menuItem = targetMenuItem;
                    found = true;
                    
                    // 使用与切换人设按钮相同的多策略点击方法
                    try {
                        // 方法1：尝试点击菜单项内的文本元素
                        Locator menuItemText = menuItem.locator(String.format("text='%s'", modeText));
                        if (menuItemText.count() > 0) {
                            log.debug("[Gitee AI] 找到{}菜单项文本元素，点击文本", modeText);
                            menuItemText.first().click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                        } else {
                            // 方法2：直接点击菜单项
                            log.debug("[Gitee AI] 未找到{}菜单项文本元素，直接点击菜单项", modeText);
                            menuItem.click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                        }
                    } catch (Exception e) {
                        log.debug("[Gitee AI] 点击{}菜单项异常: {}", modeText, e.getMessage());
                        // 方法3：使用JavaScript点击
                        menuItem.evaluate("el => el.click()");
                    }
                    
                    log.info("[Gitee AI] 已点击{}模式菜单项", modeText);
                    page.waitForTimeout(1000); // 增加等待时间，确保模式切换完成
                } else {
                    // 如果没找到，尝试其他选择器策略
                    log.debug("[Gitee AI] 未在菜单项中找到{}，尝试其他选择器", modeText);
                }
            } catch (Exception e) {
                log.debug("[Gitee AI] 查找菜单项失败: {}", e.getMessage());
            }
            
            // 如果上面的方法没找到，尝试使用选择器
            if (!found) {
                String[] menuItemSelectors = {
                    String.format(".ant-dropdown-trigger:has-text('%s')", modeText),
                    String.format(".ant-dropdown-menu-item:has-text('%s')", modeText),
                    String.format(".ant-dropdown-menu > *:has-text('%s')", modeText),
                    String.format("li:has-text('%s')", modeText),
                    String.format("span:has-text('%s')", modeText),
                    String.format("div:has-text('%s')", modeText),
                    String.format("[class*='dropdown'] *:has-text('%s')", modeText),
                    String.format("*:has-text('%s')", modeText)
                };
                
                for (String selector : menuItemSelectors) {
                    try {
                        log.debug("[Gitee AI] 尝试选择器: {}", selector);
                        Locator currentLocator = page.locator(selector).first();
                        
                        // 快速检查元素是否存在，不使用waitFor以节省时间
                        if (currentLocator.count() > 0) {
                            log.debug("[Gitee AI] 找到元素，检查可见性");
                            if (currentLocator.isVisible()) {
                                log.debug("[Gitee AI] 元素可见，使用此选择器");
                                menuItem = currentLocator;
                                found = true;
                                break;
                            }
                        }
                    } catch (Exception ex) {
                        log.debug("[Gitee AI] 选择器{}尝试失败: {}", selector, ex.getMessage());
                    }
                }
            }
            
            // 如果通过其他选择器策略找到了元素，执行点击
            if (found && menuItem != null && menuItem.isVisible()) {
                // 使用与切换人设按钮相同的多策略点击方法
                try {
                    // 方法1：尝试点击菜单项内的文本元素
                    Locator menuItemText = menuItem.locator(String.format("text='%s'", modeText));
                    if (menuItemText.count() > 0) {
                        log.debug("[Gitee AI] 找到{}菜单项文本元素，点击文本", modeText);
                        menuItemText.first().click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                    } else {
                        // 方法2：直接点击菜单项
                        log.debug("[Gitee AI] 未找到{}菜单项文本元素，直接点击菜单项", modeText);
                        menuItem.click(new Locator.ClickOptions().setTimeout(5000).setForce(true));
                    }
                } catch (Exception e) {
                    log.debug("[Gitee AI] 点击{}菜单项异常: {}", modeText, e.getMessage());
                    // 方法3：使用JavaScript点击
                    menuItem.evaluate("el => el.click()");
                }
                
                log.info("[Gitee AI] 已通过其他选择器点击{}模式菜单项", modeText);
                page.waitForTimeout(1000); // 增加等待时间，确保模式切换完成
            }
            
            // 关闭下拉菜单
            try {
                if (toggleButton != null && toggleButton.isVisible()) {
                    toggleButton.click(new Locator.ClickOptions().setTimeout(2000).setForce(true));
                    log.debug("[Gitee AI] 已关闭下拉菜单");
                    page.waitForTimeout(500); // 等待菜单关闭
                }
            } catch (Exception e) {
                // 忽略关闭失败的异常
                log.debug("[Gitee AI] 关闭下拉菜单失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("[Gitee AI] 切换{}模式失败: {}", modeText, e.getMessage());
        }
    }

    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 辅助方法：清理文本内容
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     */
    private String cleanText(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 功能：检查是否仍在生成内容
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 
     * @param page Playwright页面对象
     * @return true表示正在生成，false表示已停止
     */
    private boolean checkIfGenerating(Page page) {
        try {
            Object generatingStatus = page.evaluate("""
            () => {
                try {
                    // 检测Gitee AI特有的加载指示器
                    const thinkingIndicators = document.querySelectorAll(
                        '.n-spin, .n-loading, .loading-indicator, .thinking-indicator, ' +
                        '[class*="loading"], [class*="typing"], [class*="generating"], ' +
                        '.cursor-blink, .typing-indicator'
                    );
                    
                    for (const indicator of thinkingIndicators) {
                        if (indicator && 
                            window.getComputedStyle(indicator).display !== 'none' && 
                            window.getComputedStyle(indicator).visibility !== 'hidden') {
                            return true;
                        }
                    }
                    
                    // 检测停止生成按钮
                    const stopButtons = document.querySelectorAll(
                        'button:has-text("停止"), button:has-text("Stop"), ' +
                        '[title*="停止"], [title*="Stop"], ' +
                        '.stop-generating-button, [class*="stop"]'
                    );
                    
                    for (const btn of stopButtons) {
                        if (btn && 
                            window.getComputedStyle(btn).display !== 'none' && 
                            window.getComputedStyle(btn).visibility !== 'hidden') {
                            return true;
                        }
                    }
                    
                    // 检测光标闪烁（正在输入的标志）
                    const cursors = document.querySelectorAll(
                        '.cursor, [class*="cursor"], .typing-cursor'
                    );
                    
                    for (const cursor of cursors) {
                        if (cursor && 
                            window.getComputedStyle(cursor).display !== 'none' && 
                            window.getComputedStyle(cursor).visibility !== 'hidden') {
                            return true;
                        }
                    }
                    
                    return false;
                } catch (e) {
                    console.error('检查生成状态时出错:', e);
                    return false;
                }
            }
            """);

            return generatingStatus instanceof Boolean ? (Boolean) generatingStatus : false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 功能：提取当前会话ID
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 
     * @param page Playwright页面对象
     * @return 会话ID，如果无法提取则返回null
     */
    public String extractChatId(Page page) {
        try {
            log.info("[Gitee AI] ========== 开始提取会话ID ==========");
            
            // 🔥 关键：等待 URL 更新（发送消息后，URL 可能需要时间更新）
            log.info("[Gitee AI] 等待 URL 更新...");
            page.waitForTimeout(2000);
            
            // 步骤1：从URL中提取会话ID（最快、最可靠）
            try {
                String currentUrl = page.url();
                log.info("[Gitee AI] 当前URL: {}", currentUrl);
                
                // Gitee AI Chat 的实际会话URL格式：https://chat.gitee.com/c/{chatId}
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("/c/([^/?#]+)");
                java.util.regex.Matcher matcher = pattern.matcher(currentUrl);
                if (matcher.find()) {
                    String chatId = matcher.group(1);
                    log.info("[Gitee AI] ✅ 从URL提取到会话ID: {}", chatId);
                    return chatId;
                } else {
                    log.warn("[Gitee AI] ⚠️ URL中未找到 /c/ 格式的会话ID");
                }
            } catch (Exception e) {
                log.error("[Gitee AI] ❌ 从URL提取会话ID失败: {}", e.getMessage(), e);
            }
            
            // 步骤2：尝试使用evaluate方法提取（轻量级）
            try {
                log.info("[Gitee AI] 尝试通过JavaScript获取URL...");
                String currentUrl = (String) page.evaluate("() => window.location.href");
                log.info("[Gitee AI] JavaScript获取的URL: {}", currentUrl);
                
                if (currentUrl != null) {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("/c/([^/?#]+)");
                    java.util.regex.Matcher matcher = pattern.matcher(currentUrl);
                    if (matcher.find()) {
                        String chatId = matcher.group(1);
                        log.info("[Gitee AI] ✅ 从JavaScript提取到会话ID: {}", chatId);
                        return chatId;
                    } else {
                        log.warn("[Gitee AI] ⚠️ JavaScript获取的URL中未找到 /c/ 格式的会话ID");
                    }
                }
            } catch (Exception e) {
                log.error("[Gitee AI] ❌ 从JavaScript提取会话ID失败: {}", e.getMessage(), e);
            }
            
            // 步骤3：尝试从localStorage提取（备选方案）
            try {
                log.info("[Gitee AI] 尝试从localStorage提取...");
                String chatId = (String) page.evaluate("() => localStorage.getItem('chatId')");
                if (chatId != null && !chatId.isEmpty()) {
                    log.info("[Gitee AI] ✅ 从localStorage提取到会话ID: {}", chatId);
                    return chatId;
                } else {
                    log.warn("[Gitee AI] ⚠️ localStorage中没有chatId");
                }
            } catch (Exception e) {
                log.error("[Gitee AI] ❌ 从localStorage提取会话ID失败: {}", e.getMessage(), e);
            }
            
            // 步骤4：尝试从页面中查找会话ID（作为最后的备选方案）
            try {
                log.info("[Gitee AI] 尝试从页面元素中查找...");
                String chatId = (String) page.evaluate("function() { try { const links = document.querySelectorAll('a'); for (let link of links) { const href = link.href; if (href && href.includes('chat.gitee.com/c/')) { const match = href.match(/chat\\.gitee\\.com\\/c\\/([^/?#]+)/); if (match) return match[1]; } } return null; } catch(e) { return null; } }");
                if (chatId != null && !chatId.isEmpty()) {
                    log.info("[Gitee AI] ✅ 从页面元素提取到会话ID: {}", chatId);
                    return chatId;
                } else {
                    log.warn("[Gitee AI] ⚠️ 页面元素中未找到会话ID");
                }
            } catch (Exception e) {
                log.error("[Gitee AI] ❌ 从页面元素提取会话ID失败: {}", e.getMessage(), e);
            }
            
            // 🔥 调试：输出页面结构帮助定位问题
            try {
                log.info("[Gitee AI] ========== 调试信息 ==========");
                log.info("[Gitee AI] 页面标题: {}", page.title());
                
                // 输出所有链接
                String allLinks = (String) page.evaluate("() => Array.from(document.querySelectorAll('a[href*=\"chat.gitee.com/c/\"]')).map(a => a.href).join('\\n')");
                if (allLinks != null && !allLinks.isEmpty()) {
                    log.info("[Gitee AI] 找到的会话链接:\\n{}", allLinks);
                } else {
                    log.warn("[Gitee AI] 未找到任何 chat.gitee.com/c/ 格式的链接");
                }
            } catch (Exception e) {
                log.warn("[Gitee AI] 获取调试信息失败: {}", e.getMessage());
            }
            
            log.error("[Gitee AI] ❌ 所有提取方法都失败，未能提取到会话ID");
            log.info("[Gitee AI] ========== 提取会话ID结束 ==========");
            return null;
            
        } catch (Exception e) {
            log.error("[Gitee AI] ❌ 提取会话ID过程中发生异常", e);
            return null;
        }
    }

    /**
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 功能：导航到指定会话
     * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
     * 
     * @param page Playwright页面对象
     * @param chatId 会话ID
     * @return 是否导航成功
     */
    public boolean navigateToChat(Page page, String chatId) {
        if (chatId == null || chatId.isEmpty()) {
            log.warn("[Gitee AI] 会话ID为空，导航到首页");
            page.navigate(GITEE_AI_HOME_URL, new Page.NavigateOptions().setTimeout(10000));
            return true;
        }

        // Gitee AI Chat 会话 URL：https://chat.gitee.com/c/{chatId}
        final String chatUrl = GITEE_AI_HOME_URL + "c/" + chatId;
        final String pathMarker = "/c/" + chatId;

        try {
            String current = page.url();
            if (current != null && current.contains(pathMarker)) {
                log.info("[Gitee AI] 已在目标会话页，跳过重复导航: {}", chatId);
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(10000));
                page.waitForTimeout(600);
                return true;
            }
        } catch (Exception e) {
            log.debug("[Gitee AI] 检查当前会话 URL 时: {}", e.getMessage());
        }

        // SPA 长连接/轮询会导致 NETWORKIDLE 长期无法满足，改用 DOM 就绪并带重试
        final int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("[Gitee AI] 导航到会话: {} (第{}/{}次)", chatId, attempt, maxAttempts);
                page.navigate(chatUrl, new Page.NavigateOptions()
                    .setTimeout(30000)
                    .setWaitUntil(WaitUntilState.DOMCONTENTLOADED));

                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(15000));
                page.waitForTimeout(1500);

                String after = page.url();
                if (after != null && after.contains(pathMarker)) {
                    log.info("[Gitee AI] 成功导航到会话，等待页面稳定");
                    return true;
                }
                log.warn("[Gitee AI] 导航后 URL 未包含会话路径，将重试。当前: {}", after);
            } catch (Exception e) {
                log.warn("[Gitee AI] 导航到会话失败 (第{}/{}次): {}", attempt, maxAttempts, e.getMessage());
                if (attempt == maxAttempts) {
                    log.error("[Gitee AI] 导航到会话最终失败", e);
                    return false;
                }
            }
            try {
                page.waitForTimeout(800L * attempt);
            } catch (Exception ignored) {
                // ignore
            }
        }
        return false;
    }
}

