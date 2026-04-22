package com.wx.fbsir.engine.utils.qyweixin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 企业微信（QyWeixin）登录工具类
 *
 * 目标页面：https://work.weixin.qq.com/wework_admin/frame#/aiHelper/list?from=manage_tools
 * 登录入口：https://work.weixin.qq.com/wework_admin/loginpage_wx
 *
 * 功能说明：
 * 1. 登录状态检测 - 检查是否已进入企业微信管理后台
 * 2. 导航到登录页面
 * 3. 触发扫码登录（企业微信扫码）
 * 4. 登录状态轮询监测
 *
 * 登录流程说明：
 * - 企业微信管理后台使用独立域名 login.work.weixin.qq.com 进行扫码认证
 * - 扫码成功后自动跳转到 work.weixin.qq.com/wework_admin/frame
 * - 可通过页面URL或顶部导航栏判断是否已登录
 *
 * @author wxfbsir
 */
@Slf4j
@Component
public class QyWeixinLoginUtil {

    /** 企业微信管理后台登录页 */
    private static final String QYWEIXIN_LOGIN_URL = "https://work.weixin.qq.com/wework_admin/loginpage_wx";

    /** 企业微信AI助手页面 */
    private static final String QYWEIXIN_AI_HELPER_URL =
            "https://work.weixin.qq.com/wework_admin/frame#/aiHelper/list?from=manage_tools";

    /** 登录成功后的域名前缀 */
    private static final String QYWEIXIN_ADMIN_FRAME = "work.weixin.qq.com/wework_admin/frame";

    // =========================================================================
    // 公共方法
    // =========================================================================

    /**
     * 检查企业微信管理后台登录状态
     *
     * 检测逻辑（优先级从高到低）：
     * 1. URL 包含 /wework_admin/frame → 已登录
     * 2. 页面存在顶部企业名称区域 → 已登录
     * 3. 页面存在左侧菜单导航 → 已登录
     * 4. 页面仍在登录页（含二维码区域）→ 未登录
     *
     * @param page          Playwright页面对象
     * @param shouldNavigate 是否先导航到登录页（true=先导航，false=直接检测当前页面）
     * @return "false"表示未登录，否则返回企业名称或"已登录"
     */
    public String checkLoginStatus(Page page, boolean shouldNavigate) {
        try {
            if (shouldNavigate) {
                log.debug("[企业微信登录检测] 导航到登录页: {}", QYWEIXIN_LOGIN_URL);
                page.navigate(QYWEIXIN_LOGIN_URL);
                page.waitForLoadState();
                page.waitForTimeout(3000);
            }

            // ✅ 优先级1：URL判断 - 已登录时URL会跳转到 /wework_admin/frame
            String currentUrl = page.url();
            if (currentUrl != null && currentUrl.contains(QYWEIXIN_ADMIN_FRAME)) {
                log.info("[企业微信登录检测] 已登录（URL含 /wework_admin/frame）");
                String corpName = extractCorpName(page);
                return corpName != null ? corpName : "已登录";
            }

            // ✅ 优先级2：检查顶部企业名称区域（已登录后显示）
            // DOM: <div class="header_info"> 或 <span class="corp_name_text">
            Locator corpNameLocator = page.locator(".corp_name_text, .header_corp_name, [class*='corpName']");
            if (corpNameLocator.count() > 0) {
                try {
                    if (corpNameLocator.first().isVisible()) {
                        String corpName = corpNameLocator.first().textContent().trim();
                        log.info("[企业微信登录检测] 已登录（检测到企业名称：{}）", corpName);
                        return corpName.isEmpty() ? "已登录" : corpName;
                    }
                } catch (Exception e) {
                    log.debug("[企业微信登录检测] 企业名称检测失败，继续其他检测");
                }
            }

            // ✅ 优先级3：检查左侧导航菜单（已登录才有）
            // DOM: <div class="menu_list"> 或 <ul class="left_menu">
            Locator menuLocator = page.locator(".menu_list, .left_menu, [class*='mainMenu'], #menu_list");
            if (menuLocator.count() > 0) {
                try {
                    if (menuLocator.first().isVisible()) {
                        log.info("[企业微信登录检测] 已登录（检测到左侧导航菜单）");
                        String corpName = extractCorpName(page);
                        return corpName != null ? corpName : "已登录";
                    }
                } catch (Exception e) {
                    log.debug("[企业微信登录检测] 导航菜单检测失败，继续其他检测");
                }
            }

            // ❌ 检查是否还在登录页（含二维码）
            Locator qrCodeLocator = page.locator(".login_qrcode_img, .qr_code_img, [class*='qrCode'], .wx_login_qrcode");
            if (qrCodeLocator.count() > 0) {
                try {
                    if (qrCodeLocator.first().isVisible()) {
                        log.info("[企业微信登录检测] 未登录（检测到登录二维码）");
                        return "false";
                    }
                } catch (Exception e) {
                    log.debug("[企业微信登录检测] 二维码检测失败");
                }
            }

            // 都不满足，保守判定：检查URL是否在登录域名
            if (currentUrl != null && currentUrl.contains("loginpage_wx")) {
                log.info("[企业微信登录检测] 未登录（仍在登录页面）");
                return "false";
            }

            // 最终保守判定为已登录
            log.info("[企业微信登录检测] 已登录（未检测到未登录标志）");
            String corpName = extractCorpName(page);
            return corpName != null ? corpName : "已登录";

        } catch (Exception e) {
            log.error("[企业微信登录检测] 检测失败", e);
            return "false";
        }
    }

    /**
     * 导航到企业微信管理后台登录页
     *
     * @param page Playwright页面对象
     * @return 导航是否成功
     */
    public boolean navigateToLoginPage(Page page) {
        try {
            log.debug("[企业微信导航] 访问登录页: {}", QYWEIXIN_LOGIN_URL);
            page.navigate(QYWEIXIN_LOGIN_URL);
            page.waitForLoadState();
            page.waitForTimeout(3000);
            log.info("[企业微信导航] 登录页加载完成，当前URL: {}", page.url());
            return true;
        } catch (Exception e) {
            log.error("[企业微信导航] 导航失败", e);
            return false;
        }
    }

    /**
     * 导航到企业微信AI助手页面
     *
     * @param page Playwright页面对象
     * @return 导航是否成功
     */
    public boolean navigateToAiHelperPage(Page page) {
        try {
            log.debug("[企业微信导航] 访问AI助手页: {}", QYWEIXIN_AI_HELPER_URL);
            page.navigate(QYWEIXIN_AI_HELPER_URL);
            page.waitForLoadState();
            page.waitForTimeout(3000);
            log.info("[企业微信导航] AI助手页加载完成，当前URL: {}", page.url());
            return true;
        } catch (Exception e) {
            log.error("[企业微信导航] 导航到AI助手页失败", e);
            return false;
        }
    }

    /**
     * 触发企业微信扫码登录
     *
     * 企业微信管理后台的登录页面默认就展示二维码，无需额外点击触发。
     * 此方法确保页面已加载二维码并稳定显示。
     *
     * @param page Playwright页面对象
     * @return 是否成功触发登录流程（二维码已显示）
     */
    public boolean triggerScanLogin(Page page) {
        try {
            // 企业微信登录页打开即显示二维码，等待加载完成
            page.waitForTimeout(2000);

            // 检查是否有二维码容器
            // DOM: .login_qrcode_img 或 #qrcode 或 .wx_login_qrcode
            Locator qrCode = page.locator(
                ".login_qrcode_img, #qrcode, .wx_login_qrcode, [class*='qrCode'], .scan_qrcode_img"
            );

            if (qrCode.count() > 0) {
                log.info("[企业微信扫码] 检测到登录二维码，等待用户扫码");
                return true;
            }

            // 如果没找到二维码，可能需要点击"扫码登录"切换方式
            Locator scanLoginTab = page.locator(
                "a:has-text('扫码登录'), .tab_item:has-text('扫码'), [class*='scanLogin']"
            );
            if (scanLoginTab.count() > 0) {
                log.debug("[企业微信扫码] 点击扫码登录标签");
                scanLoginTab.first().click();
                page.waitForTimeout(2000);
            }

            // 再次检查二维码
            if (qrCode.count() > 0) {
                log.info("[企业微信扫码] 切换后检测到二维码");
                return true;
            }

            // 保守返回true（让截图说话）
            log.warn("[企业微信扫码] 未确认找到二维码，但继续执行（将通过截图展示）");
            return true;

        } catch (Exception e) {
            log.error("[企业微信扫码] 触发登录失败", e);
            return false;
        }
    }

    /**
     * 检查是否仍在登录页面（用于轮询监测登录状态）
     *
     * 检测逻辑：
     * ✅ 已登录：URL跳转到 /wework_admin/frame，或检测到后台管理元素
     * ❌ 仍在登录：URL含 loginpage_wx，或仍显示二维码
     *
     * @param page Playwright页面对象
     * @return true=仍在等待登录，false=已登录成功
     */
    public boolean isStillOnLoginPage(Page page) {
        try {
            String currentUrl = page.url();

            // ✅ 已登录：URL已跳转到管理后台
            if (currentUrl != null && currentUrl.contains(QYWEIXIN_ADMIN_FRAME)) {
                log.info("[企业微信登录状态] URL已跳转到管理后台，登录成功");
                return false;
            }

            // ✅ 检查顶部企业名称或导航菜单（已登录标志）
            Locator adminContent = page.locator(
                ".corp_name_text, .menu_list, .left_menu, [class*='corpName'], [class*='mainMenu']"
            );
            if (adminContent.count() > 0) {
                try {
                    if (adminContent.first().isVisible()) {
                        log.info("[企业微信登录状态] 检测到管理后台内容，登录成功");
                        return false;
                    }
                } catch (Exception e) {
                    log.debug("[企业微信登录状态] 管理后台内容检测失败，继续");
                }
            }

            // ❌ 仍在登录页：URL含 loginpage_wx
            if (currentUrl != null && currentUrl.contains("loginpage_wx")) {
                log.debug("[企业微信登录状态] 仍在登录页，等待扫码");
                return true;
            }

            // ❌ 检查二维码是否仍可见
            Locator qrCode = page.locator(
                ".login_qrcode_img, #qrcode, .wx_login_qrcode, [class*='qrCode'], .scan_qrcode_img"
            );
            if (qrCode.count() > 0 && qrCode.first().isVisible()) {
                log.debug("[企业微信登录状态] 二维码仍可见，等待扫码");
                return true;
            }

            // 保守判定：认为已登录
            log.info("[企业微信登录状态] 无明显登录页标志，判定为已登录");
            return false;

        } catch (Exception e) {
            log.debug("[企业微信登录状态] 检测失败，保守认为已登录", e);
            return false;
        }
    }

    /**
     * 是否出现登录/续期用的二维码浮层（会话中途过期时可能盖住工作流编辑器，导致后续点击无响应）。
     */
    public boolean isLoginQrLayerVisible(Page page) {
        try {
            String url = page.url();
            if (url != null && url.contains("loginpage")) {
                return true;
            }
            Locator qr = page.locator(
                    ".login_qrcode_img, .qr_code_img, #qrcode, .wx_login_qrcode, "
                            + "[class*='qrcode'], [class*='qrCode'], .scan_qrcode_img"
            );
            if (qr.count() > 0) {
                try {
                    if (qr.first().isVisible()) {
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
            Object js = page.evaluate(
                    "() => {\n"
                            + "  const ifr = document.querySelector('iframe[src*=\"login\"], iframe[src*=\"qr\"], iframe[src*=\"passport\"]');\n"
                            + "  if (ifr && ifr.getBoundingClientRect().width > 80) return true;\n"
                            + "  const c = document.querySelector('canvas');\n"
                            + "  if (c && c.getBoundingClientRect().width > 80 && c.getBoundingClientRect().height > 80) {\n"
                            + "    const t = (document.body && document.body.innerText) || '';\n"
                            + "    if (t.includes('扫码') || t.includes('二维码') || t.includes('登录')) return true;\n"
                            + "  }\n"
                            + "  const txt = (document.body && document.body.innerText) || '';\n"
                            + "  if (txt.includes('请使用') && txt.includes('扫码') && document.querySelector('[role=\"dialog\"], .t-dialog, [class*=\"mask\"]')) {\n"
                            + "    return true;\n"
                            + "  }\n"
                            + "  return false;\n"
                            + "}"
            );
            return Boolean.TRUE.equals(js);
        } catch (Exception e) {
            log.debug("[企业微信] 二维码浮层检测异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 若存在登录二维码浮层，则轮询等待其消失（用户完成扫码/确认）。超时后仍盖住则返回 false。
     */
    public boolean waitUntilLoginQrLayerAbsent(Page page, long maxWaitMs) {
        long start = System.currentTimeMillis();
        long deadline = start + maxWaitMs;
        long lastLog = 0;
        while (System.currentTimeMillis() < deadline) {
            if (!isLoginQrLayerVisible(page)) {
                if (System.currentTimeMillis() > start + 500) {
                    log.info("[企业微信] 登录二维码浮层已消失，继续自动化");
                }
                return true;
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed - lastLog >= 10000) {
                lastLog = elapsed;
                log.warn("[企业微信] 检测到登录二维码浮层，请用手机扫码/确认登录（已等待 {} ms / 最长 {} ms）",
                        elapsed, maxWaitMs);
            }
            page.waitForTimeout(2000);
        }
        boolean gone = !isLoginQrLayerVisible(page);
        if (!gone) {
            log.error("[企业微信] 登录二维码浮层在 {} ms 内未消失，请重新扫码或执行 QYWEIXIN_SCAN_LOGIN", maxWaitMs);
        }
        return gone;
    }

    // =========================================================================
    // 私有方法
    // =========================================================================

    /**
     * 提取企业名称（登录后顶部显示）
     *
     * @param page Playwright页面对象
     * @return 企业名称，无法提取则返回null
     */
    private String extractCorpName(Page page) {
        try {
            Locator corpLocator = page.locator(
                ".corp_name_text, .header_corp_name, [class*='corpName'], .main_header_corp"
            );
            if (corpLocator.count() > 0) {
                String name = corpLocator.first().textContent().trim();
                if (!name.isEmpty()) {
                    log.debug("[企业微信] 提取企业名称: {}", name);
                    return name;
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信] 提取企业名称失败", e);
        }
        return null;
    }
}
