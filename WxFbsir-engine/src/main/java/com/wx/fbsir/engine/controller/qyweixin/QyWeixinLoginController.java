package com.wx.fbsir.engine.controller.qyweixin;

import com.microsoft.playwright.Page;
import com.wx.fbsir.engine.capability.annotation.OnceCapability;
import com.wx.fbsir.engine.capability.annotation.StreamCapability;
import com.wx.fbsir.engine.capability.base.StreamTaskHelper;
import com.wx.fbsir.engine.playwright.pool.BrowserPoolManager;
import com.wx.fbsir.engine.playwright.session.BrowserSession;
import com.wx.fbsir.engine.utils.qyweixin.QyWeixinLoginUtil;
import com.wx.fbsir.engine.websocket.message.EngineMessage;
import com.wx.fbsir.engine.websocket.message.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 企业微信（QyWeixin）登录控制器
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 功能概述
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 1. 登录状态检测 - 检查用户是否已登录企业微信管理后台
 * 2. 二维码扫码登录 - 获取登录二维码，实时监测登录状态
 * 3. 测试功能 - 截图当前企业微信页面供开发者查看
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 消息类型
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * - QYWEIXIN_CHECK_LOGIN: 检查登录状态（单次返回）
 * - QYWEIXIN_SCAN_LOGIN:  扫码登录（流式返回，含二维码截图）
 * - QYWEIXIN_TEST_VIEW:   测试查看企业微信AI助手页面
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 Session标识
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * browserPool.acquirePersistent(userId, "qyweixin", false)
 * LoginStateManager: platform = "qyweixin"
 *
 * @author wxfbsir
 */
@Controller
public class QyWeixinLoginController extends StreamTaskHelper {

    /** 每个用户的企业微信固定浏览器实例 ID，对齐元宝 fixed-main 模式 */
    private static final String QYWEIXIN_FIXED_INSTANCE = "fixed-main";
    private static final ConcurrentHashMap<String, ReentrantLock> USER_SERIAL_LOCKS = new ConcurrentHashMap<>();

    @Autowired
    private QyWeixinLoginUtil loginUtil;

    @Autowired
    private BrowserPoolManager browserPool;

    @Autowired
    @Lazy
    private com.wx.fbsir.engine.websocket.client.WebSocketClientManager webSocketClientManager;

    @Autowired
    private com.wx.fbsir.engine.playwright.util.ScreenshotUploadClient uploadClient;

    // =========================================================================
    // QYWEIXIN_CHECK_LOGIN：检查登录状态（单次）
    // =========================================================================

    /**
     * 检查企业微信管理后台登录状态（单次返回）
     *
     * 请求示例：
     * {"type": "QYWEIXIN_CHECK_LOGIN", "engineId": "engine-001"}
     *
     * 返回数据：
     * - isLoggedIn: 是否已登录（boolean）
     * - corpName:   企业名称（如果已登录）
     * - platform:   平台名称（QyWeixin）
     */
    @OnceCapability(
        type = "QYWEIXIN_CHECK_LOGIN",
        description = "检查企业微信登录状态",
        timeout = 30000L
    )
    public void handleCheckLogin(EngineMessage message) {
        String userId    = message.getUserId();
        String requestId = extractTopLevel(message, "requestId");

        log.info("[企业微信登录检测] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session;
            Page page = sessionPage.page;
            session.touch();

            // 尝试恢复已保存的登录状态
            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                    .hasLoginState("qyweixin", userId)) {
                try {
                    boolean restored = com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                            .restoreLoginState(session, "qyweixin", userId);
                    if (restored) {
                        log.info("[企业微信登录检测] ✅ 登录状态已恢复 - 用户: {}", userId);
                    } else {
                        log.warn("[企业微信登录检测] ⚠️ 登录状态恢复失败 - 用户: {}", userId);
                    }
                } catch (Exception e) {
                    log.warn("[企业微信登录检测] ⚠️ 登录状态恢复异常: {}", e.getMessage());
                }
            }

            String loginStatus = loginUtil.checkLoginStatus(page, true);
            boolean isLoggedIn = !"false".equals(loginStatus);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("isLoggedIn", isLoggedIn);
            resultData.put("corpName", isLoggedIn ? loginStatus : null);
            resultData.put("platform", "QyWeixin");
            resultData.put("aiType", "qyweixin");
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(message, resultData);
            log.info("[企业微信登录检测] 完成 - 用户: {}, 已登录: {}", userId, isLoggedIn);

        } catch (Exception e) {
            log.error("[企业微信登录检测] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "登录检测失败: " + e.getMessage());
        } finally {
            // 企业微信登录态依赖浏览器进程存活，检测完成后仅归还（release），不销毁实例
            if (session != null) {
                try {
                    session.touch();
                    browserPool.release(session);
                } catch (Exception e) {
                    log.warn("[企业微信登录检测] 归还会话失败: {}", e.getMessage());
                }
            }
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_SCAN_LOGIN：扫码登录（流式）
    // =========================================================================

    /**
     * 企业微信扫码登录（流式返回）
     *
     * 进度推送：
     * - qrCodeUrl: 二维码截图URL（每30秒刷新）
     * - status:    当前状态（waiting / success / timeout）
     */
    @StreamCapability(
        type = "QYWEIXIN_SCAN_LOGIN",
        description = "企业微信扫码登录",
        progressInterval = 2000
    )
    public void handleScanLogin(EngineMessage message) {
        String userId    = message.getUserId();
        String requestId = extractTopLevel(message, "requestId");

        log.info("[企业微信扫码登录] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        StreamTask task = startStreamTask(message, requestId, 2000);
        BrowserSession session = null;

        try {
            task.sendLog("正在打开企业微信登录页面...");

            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session;
            Page page = sessionPage.page;
            session.touch();

            // 导航到登录页
            task.sendLog("正在加载企业微信登录页面...");
            boolean navSuccess = loginUtil.navigateToLoginPage(page);

            if (!navSuccess) {
                task.sendError("无法加载企业微信登录页面，请检查网络连接");
                return;
            }

            // 检查是否已登录
            String loginStatus = loginUtil.checkLoginStatus(page, false);
            if (!"false".equals(loginStatus)) {
                task.sendLog("检测到已登录状态，无需扫码");
                Map<String, Object> alreadyLoggedIn = new HashMap<>();
                alreadyLoggedIn.put("success", true);
                alreadyLoggedIn.put("corpName", loginStatus);
                alreadyLoggedIn.put("alreadyLoggedIn", true);
                task.sendSuccess("已登录，企业: " + loginStatus, alreadyLoggedIn);
                return;
            }

            // 触发扫码登录（确保二维码已显示）
            task.sendLog("正在触发扫码登录...");
            boolean triggerSuccess = loginUtil.triggerScanLogin(page);

            if (!triggerSuccess) {
                task.sendError("无法触发登录流程，请检查页面状态");
                return;
            }

            page.waitForTimeout(2000);

            // 首次截图二维码
            String qrCodeUrl = captureAndUpload(page, userId, "qyweixin_qrcode_initial");
            String lastQrCodeUrl = qrCodeUrl;

            if (qrCodeUrl != null) {
                task.sendLog("请使用企业微信App扫码登录");
                task.sendScreenshot(qrCodeUrl);
                log.info("[企业微信扫码登录] 二维码已生成 - 用户: {}", userId);
            }

            long startTime = System.currentTimeMillis();
            long maxWaitTime = 300000; // 5分钟超时
            long lastScreenshotTime = System.currentTimeMillis();
            int screenshotCount = 1;

            // 轮询检测登录状态
            while (true) {
                long elapsedTime = System.currentTimeMillis() - startTime;

                if (elapsedTime > maxWaitTime) {
                    Map<String, Object> timeoutData = new HashMap<>();
                    timeoutData.put("success", false);
                    timeoutData.put("timeout", true);
                    timeoutData.put("qrCodeUrl", lastQrCodeUrl);
                    task.sendSuccess("扫码登录超时", timeoutData);
                    log.warn("[企业微信扫码登录] 超时 - 用户: {}", userId);
                    return;
                }

                session.touch();

                // 每30秒刷新一次二维码截图
                if (System.currentTimeMillis() - lastScreenshotTime >= 30000) {
                    try {
                        screenshotCount++;
                        String newQrCodeUrl = captureAndUpload(page, userId,
                                "qyweixin_qrcode_" + screenshotCount);
                        if (newQrCodeUrl != null) {
                            lastQrCodeUrl = newQrCodeUrl;
                            task.sendLog("二维码已更新，请继续扫码（已等待" + (elapsedTime / 1000) + "秒）");
                            task.sendScreenshot(newQrCodeUrl);
                        }
                        lastScreenshotTime = System.currentTimeMillis();
                    } catch (Exception ex) {
                        log.warn("[企业微信扫码登录] 截图更新失败 - 用户: {}", userId, ex);
                    }
                }

                // 检查是否已离开登录页
                boolean stillOnLogin = loginUtil.isStillOnLoginPage(page);

                if (!stillOnLogin) {
                    page.waitForTimeout(2000);

                    String finalStatus = loginUtil.checkLoginStatus(page, false);

                    if (!"false".equals(finalStatus)) {
                        Map<String, Object> successData = new HashMap<>();
                        successData.put("success", true);
                        successData.put("isLoggedIn", true);
                        successData.put("corpName", finalStatus);
                        successData.put("platform", "QyWeixin");
                        successData.put("qrCodeUrl", lastQrCodeUrl);
                        successData.put("loginTime", elapsedTime / 1000);
                        successData.put("timestamp", System.currentTimeMillis());

                        task.sendSuccess("登录成功！企业: " + finalStatus, successData);
                        log.info("[企业微信扫码登录] 成功 - 用户: {}, 企业: {}", userId, finalStatus);

                        // 保存登录状态
                        task.sendLog("正在保存登录状态...");
                        try {
                            Thread.sleep(2000);
                            boolean saved = com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                                    .saveLoginState(session, "qyweixin", userId, finalStatus);
                            if (saved) {
                                log.info("[企业微信扫码登录] ✅ 登录状态已保存 - 用户: {}", userId);
                            } else {
                                log.warn("[企业微信扫码登录] ⚠️ 登录状态保存失败 - 用户: {}", userId);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            log.error("[企业微信扫码登录] 保存登录状态异常: {}", e.getMessage(), e);
                        }

                        // 企业微信登录态依赖浏览器进程存活，登录成功后归还（release）保留实例，不销毁
                        try {
                            session.touch();
                            browserPool.release(session);
                            log.info("[企业微信扫码登录] ✅ 持久化会话已归还保留 - 用户: {}", userId);
                        } catch (Exception e) {
                            log.warn("[企业微信扫码登录] 归还会话失败: {}", e.getMessage());
                        }
                        session = null; // 标记已处理，防止 finally 重复操作
                        return;
                    }
                }

                page.waitForTimeout(2000);
            }

        } catch (Exception e) {
            log.error("[企业微信扫码登录] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            task.sendError("扫码登录失败: " + e.getMessage());
        } finally {
            task.stop();
            // 若 session 未被成功分支处理（超时、失败等），归还到池
            if (session != null) {
                try {
                    session.touch();
                    browserPool.release(session);
                } catch (Exception ex) {
                    log.warn("[企业微信扫码登录] 归还会话失败: {}", ex.getMessage());
                }
            }
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_TEST_VIEW：测试查看页面
    // =========================================================================

    /**
     * 测试功能：截图企业微信AI助手页面供开发者查看DOM结构
     *
     * 此功能用于阶段2（DOM结构探索），截图AI助手页面，
     * 为后续工作流操作的Playwright选择器调试提供依据。
     */
    @StreamCapability(
        type = "QYWEIXIN_TEST_VIEW",
        description = "测试查看企业微信AI助手页面",
        progressInterval = 1000
    )
    public void handleTestView(EngineMessage message) {
        String userId    = message.getUserId();
        String requestId = extractTopLevel(message, "requestId");

        log.info("[企业微信测试查看] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        StreamTask task = startStreamTask(message, requestId, 1000);
        BrowserSession session = null;

        try {
            task.sendLog("正在打开企业微信AI助手页面...");

            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session;
            Page page = sessionPage.page;
            session.touch();

            // 尝试恢复登录状态
            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                    .hasLoginState("qyweixin", userId)) {
                com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "qyweixin", userId);
            }

            // 导航到AI助手页
            task.sendLog("正在加载企业微信AI助手页面...");
            boolean navSuccess = loginUtil.navigateToAiHelperPage(page);

            if (!navSuccess) {
                task.sendError("无法加载企业微信AI助手页面，请检查网络或登录状态");
                return;
            }

            // 检查登录状态
            String loginStatus = loginUtil.checkLoginStatus(page, false);
            boolean isLoggedIn = !"false".equals(loginStatus);

            if (!isLoggedIn) {
                task.sendLog("⚠️ 未登录，将截图展示登录页面");
            }

            // 连续截图5张
            java.util.List<String> screenshots = new java.util.ArrayList<>();

            for (int i = 1; i <= 5; i++) {
                task.sendLog("测试查看中... (" + i + "/5秒)");
                session.touch();

                String screenshotUrl = captureAndUpload(page, userId, "qyweixin_test_" + i);
                if (screenshotUrl != null) {
                    screenshots.add(screenshotUrl);
                    task.sendScreenshot(screenshotUrl);
                }

                if (i < 5) {
                    page.waitForTimeout(1000);
                }
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("screenshots", screenshots);
            resultData.put("loginStatus", isLoggedIn ? loginStatus : "未登录");
            resultData.put("isLoggedIn", isLoggedIn);
            resultData.put("currentUrl", page.url());
            resultData.put("testDuration", 5);
            resultData.put("platform", "QyWeixin");

            task.sendSuccess("测试完成，共截图" + screenshots.size() + "张", resultData);
            log.info("[企业微信测试查看] 完成 - 用户: {}, 登录: {}, 截图: {}张",
                    userId, isLoggedIn, screenshots.size());

        } catch (Exception e) {
            log.error("[企业微信测试查看] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            task.sendError("测试查看失败: " + e.getMessage());
        } finally {
            task.stop();
            // 企业微信登录态依赖浏览器进程存活，归还（release）保留实例，不销毁
            if (session != null) {
                try {
                    session.touch();
                    browserPool.release(session);
                } catch (Exception e) {
                    log.warn("[企业微信测试查看] 归还会话失败: {}", e.getMessage());
                }
            }
            userLock.unlock();
        }
    }

    // =========================================================================
    // 私有辅助方法
    // =========================================================================

    /**
     * 获取/复用当前用户的企业微信固定浏览器实例（有头、持久化）
     * 对齐元宝 acquireFixedYuanbaoSession 模式
     */
    private BrowserSession acquireFixedQyWeixinSession(String userId) {
        return browserPool.acquire(userId, "qyweixin", QYWEIXIN_FIXED_INSTANCE, true, false);
    }

    /**
     * 获取用户串行操作锁，同一用户的企业微信操作串行执行，防止并发冲突
     */
    private ReentrantLock getUserSerialLock(String userId) {
        return USER_SERIAL_LOCKS.computeIfAbsent(userId, k -> new ReentrantLock());
    }

    /**
     * 带健康恢复的会话获取：复用固定实例，失效时自动销毁并重建
     * 对齐元宝 acquireSessionPageWithRecovery 模式
     */
    private SessionPage acquireSessionPageWithRecovery(String userId) {
        BrowserSession session = acquireFixedQyWeixinSession(userId);
        try {
            Page page = session.getOrCreatePage();
            page.url(); // 健康探测，触发 TargetClosedError 则说明实例已失效
            return new SessionPage(session, page);
        } catch (Exception firstEx) {
            if (!isTargetClosed(firstEx)) {
                throw firstEx;
            }
            log.warn("[企业微信会话] 检测到固定实例失效，准备重建 - 用户: {}, 错误: {}", userId, firstEx.getMessage());
            try {
                browserPool.destroy(session);
            } catch (Exception destroyEx) {
                log.warn("[企业微信会话] 销毁失效会话异常 - 用户: {}, 错误: {}", userId, destroyEx.getMessage());
            }
            BrowserSession rebuilt = acquireFixedQyWeixinSession(userId);
            Page rebuiltPage = rebuilt.getOrCreatePage();
            rebuiltPage.url();
            return new SessionPage(rebuilt, rebuiltPage);
        }
    }

    private boolean isTargetClosed(Exception ex) {
        if (ex == null || ex.getMessage() == null) {
            return false;
        }
        String msg = ex.getMessage().toLowerCase();
        return msg.contains("target page, context or browser has been closed")
            || msg.contains("targetclosederror")
            || msg.contains("browser has been closed");
    }

    private record SessionPage(BrowserSession session, Page page) {
    }


    private void sendResult(EngineMessage message, Map<String, Object> data) {
        String userId    = message.getUserId();
        String requestId = extractTopLevel(message, "requestId");
        String sourceType      = extractTopLevel(message, "sourceType");
        String sourceClientId  = extractTopLevel(message, "sourceClientId");

        EngineMessage.Builder builder = EngineMessage.builder()
                .type(MessageType.TASK_RESULT.getCode())
                .userId(userId)
                .payload("requestId", requestId)
                .payload("success", true)
                .payload("data", data)
                .payload("timestamp", System.currentTimeMillis());

        if (sourceType     != null) builder.payload("sourceType", sourceType);
        if (sourceClientId != null) builder.payload("sourceClientId", sourceClientId);

        webSocketClientManager.sendMessage(builder.build());
    }

    /**
     * 发送单次错误结果（透传 sourceType / sourceClientId）
     */
    private void sendErrorResult(EngineMessage message, String errorMessage) {
        String userId    = message.getUserId();
        String requestId = extractTopLevel(message, "requestId");
        String sourceType      = extractTopLevel(message, "sourceType");
        String sourceClientId  = extractTopLevel(message, "sourceClientId");

        EngineMessage.Builder builder = EngineMessage.builder()
                .type(MessageType.TASK_RESULT.getCode())
                .userId(userId)
                .payload("requestId", requestId)
                .payload("success", false)
                .payload("errorCode", "TASK_ERROR")
                .payload("errorMessage", errorMessage)
                .payload("timestamp", System.currentTimeMillis());

        if (sourceType     != null) builder.payload("sourceType", sourceType);
        if (sourceClientId != null) builder.payload("sourceClientId", sourceClientId);

        webSocketClientManager.sendMessage(builder.build());
        log.error("[企业微信登录] 发送错误 - 用户: {}, 请求: {}, 错误: {}", userId, requestId, errorMessage);
    }

    /**
     * 从 rawJson 顶层提取字段（Admin 路由注入的 requestId / sourceType / sourceClientId 在顶层，不在 payload）
     */
    private String extractTopLevel(EngineMessage message, String key) {
        try {
            String raw = message.getRawJson();
            if (raw == null || raw.isBlank()) return null;
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(raw);
            return root != null ? root.getString(key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String captureAndUpload(Page page, String userId, String fileName) {
        try {
            byte[] screenshotBytes = page.screenshot();
            com.wx.fbsir.engine.playwright.util.ScreenshotUploadClient.UploadResult result =
                    uploadClient.uploadScreenshot(userId, fileName, screenshotBytes);
            if (result.isSuccess()) {
                log.info("[企业微信截图] 上传成功 - URL: {}", result.getUrl());
                return result.getUrl();
            } else {
                log.error("[企业微信截图] 上传失败 - 错误: {}", result.getErrorMessage());
                return null;
            }
        } catch (Exception e) {
            log.error("[企业微信截图] 截图失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
