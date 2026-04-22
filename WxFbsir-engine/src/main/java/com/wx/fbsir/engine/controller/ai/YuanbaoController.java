package com.wx.fbsir.engine.controller.ai;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;
import com.wx.fbsir.engine.capability.CapabilityNormalizer;
import com.wx.fbsir.engine.capability.annotation.OnceCapability;
import com.wx.fbsir.engine.capability.annotation.StreamCapability;
import com.wx.fbsir.engine.capability.base.StreamTaskHelper;
import com.wx.fbsir.engine.playwright.pool.BrowserPoolManager;
import com.wx.fbsir.engine.playwright.session.BrowserSession;
import com.wx.fbsir.engine.utils.ai.YuanbaoUtil;
import com.wx.fbsir.engine.utils.common.FileDownloadUtil;
import com.wx.fbsir.engine.websocket.message.EngineMessage;
import com.wx.fbsir.engine.websocket.message.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 腾讯元宝网页版 WebSocket 控制器。
 */
@Controller
public class YuanbaoController extends StreamTaskHelper {
    private static final String YUANBAO_FIXED_INSTANCE = "fixed-main";
    private static final ConcurrentHashMap<String, ReentrantLock> USER_SERIAL_LOCKS = new ConcurrentHashMap<>();

    @Autowired
    private YuanbaoUtil yuanbaoUtil;

    @Autowired
    private BrowserPoolManager browserPool;

    @Autowired
    @Lazy
    private com.wx.fbsir.engine.websocket.client.WebSocketClientManager webSocketClientManager;

    @Autowired
    private com.wx.fbsir.engine.playwright.util.ScreenshotUploadClient uploadClient;

    @Autowired
    private FileDownloadUtil fileDownloadUtil;

    @OnceCapability(
        type = "YUANBAO_CHECK_LOGIN",
        description = "检查元宝登录状态",
        timeout = 30000L
    )
    public void handleCheckLogin(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);
        String aiType = extractAiType(message);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;
        try {
            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session;
            Page page = sessionPage.page;
            session.touch();

            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager.hasLoginState("yuanbao", userId)) {
                try {
                    com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "yuanbao", userId);
                } catch (Exception e) {
                    log.warn("[Yuanbao登录检测] 恢复登录状态异常: {}", e.getMessage());
                }
            }

            String loginStatus = yuanbaoUtil.checkLoginStatus(page, true);
            boolean isLoggedIn = !"false".equals(loginStatus);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("isLoggedIn", isLoggedIn);
            resultData.put("userName", isLoggedIn ? loginStatus : null);
            resultData.put("platform", "Yuanbao");
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(userId, sessionId, aiType, resultData);
        } catch (Exception e) {
            log.error("[Yuanbao登录检测] 失败", e);
            sendErrorResult(userId, sessionId, aiType, "登录检测失败: " + e.getMessage());
        } finally {
            if (session != null) {
                try {
                    session.touch();
                    browserPool.release(session);
                } catch (Exception e) {
                    log.warn("[Yuanbao登录检测] 释放会话失败: {}", e.getMessage());
                }
            }
            userLock.unlock();
        }
    }

    @StreamCapability(
        type = "YUANBAO_SCAN_LOGIN",
        description = "元宝扫码/页面登录（轮询检测）",
        progressInterval = 2000
    )
    public void handleScanLogin(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        StreamTask task = startStreamTask(userId, sessionId, extractAiType(message), 2000);
        BrowserSession session = null;
        try {
            task.sendLog("正在打开元宝并唤起登录浮层...");
            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session;
            session.touch();
            Page page = sessionPage.page;

            if (!yuanbaoUtil.navigateToLoginPageAndOpenLoginLayer(page)) {
                task.sendError("无法打开元宝页面，请检查网络");
                return;
            }

            task.sendLog("请使用手机扫码或验证码完成登录");
            String qrCodeUrl = captureAndUpload(page, userId, "yuanbao_login_initial");
            if (qrCodeUrl != null) {
                task.sendScreenshot(qrCodeUrl);
            }

            long startTime = System.currentTimeMillis();
            long maxWaitTime = 180000;
            long lastScreenshotTime = System.currentTimeMillis();
            int screenshotCount = 1;
            String lastQr = qrCodeUrl;
            boolean waitPhoneConfirmNotified = false;
            long driftStartAt = -1L;
            boolean driftWaitNotified = false;
            boolean driftRecoveryTried = false;
            int workbenchStableCount = 0;

            while (true) {
                long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed > maxWaitTime) {
                    Map<String, Object> timeoutData = new HashMap<>();
                    timeoutData.put("success", false);
                    timeoutData.put("timeout", true);
                    timeoutData.put("qrCodeUrl", lastQr);
                    task.sendSuccess("登录超时", timeoutData);
                    return;
                }
                session.touch();

                // /scan 等页「选择账号类型」可能在 iframe 内，需每轮主动处理，否则会一直卡住
                try {
                    if (yuanbaoUtil.tryHandleAccountTypePopup(page)) {
                        task.sendLog("已自动选择「个人账号」，正在继续登录流程…");
                    }
                } catch (Exception popupEx) {
                    log.debug("[Yuanbao扫码登录] 账号类型弹窗处理: {}", popupEx.getMessage());
                }

                // 扫码回调期容忍：about:blank/非元宝域先等待，持续异常才回拉，避免打断登录完成
                boolean drifted = yuanbaoUtil.isLikelyDrifted(page);
                if (drifted) {
                    Page switched = trySwitchToActivePage(session, page);
                    if (switched != page) {
                        page = switched;
                        driftStartAt = -1L;
                        driftWaitNotified = false;
                        driftRecoveryTried = false;
                        task.sendLog("检测到登录回调新页面，已自动切换继续确认");
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    if (driftStartAt < 0) {
                        driftStartAt = now;
                        if (!driftWaitNotified) {
                            task.sendLog("检测到登录回调过渡，正在等待页面恢复...");
                            driftWaitNotified = true;
                        }
                    }
                    long driftDuration = now - driftStartAt;
                    // 回调漂移阶段不再主动导航，避免把已完成回调再次打回登录页
                    if (!driftRecoveryTried && driftDuration >= 20000) {
                        driftRecoveryTried = true;
                        task.sendLog("登录回调处理中，页面未自动恢复，正在尝试主动恢复到元宝主页...");
                        Page recovered = tryRecoverFromDrift(session, page);
                        if (recovered != null && !yuanbaoUtil.isLikelyDrifted(recovered)) {
                            page = recovered;
                            driftStartAt = -1L;
                            driftWaitNotified = false;
                            driftRecoveryTried = false;
                            task.sendLog("页面已恢复，继续确认登录状态...");
                            continue;
                        }
                    }
                    try {
                        page.waitForTimeout(1500);
                    } catch (Exception closedErr) {
                        log.warn("[Yuanbao扫码登录] 漂移等待中页面中断: {}", closedErr.getMessage());
                        task.sendLog("检测到浏览器页面中断，请保持Engine侧浏览器窗口开启后重试");
                        return;
                    }
                    continue;
                } else {
                    driftStartAt = -1L;
                    driftWaitNotified = false;
                    driftRecoveryTried = false;
                }

                if (!waitPhoneConfirmNotified && System.currentTimeMillis() - lastScreenshotTime >= 30000) {
                    screenshotCount++;
                    String newUrl = captureAndUpload(page, userId, "yuanbao_login_" + screenshotCount);
                    if (newUrl != null) {
                        lastQr = newUrl;
                        task.sendLog("页面截图已刷新，请继续完成登录（已等待 " + (elapsed / 1000) + " 秒）");
                        task.sendScreenshot(newUrl);
                    }
                    lastScreenshotTime = System.currentTimeMillis();
                }

                if (yuanbaoUtil.isWaitingPhoneConfirm(page)) {
                    if (!waitPhoneConfirmNotified) {
                        task.sendLog("已检测到扫码成功，请在手机微信侧点击确认登录");
                        waitPhoneConfirmNotified = true;
                    }
                } else {
                    waitPhoneConfirmNotified = false;
                }

                String loginStatus = yuanbaoUtil.checkLoginStatus(page, false);
                if (!"false".equals(loginStatus)) {
                    Map<String, Object> successData = new HashMap<>();
                    successData.put("success", true);
                    successData.put("userName", loginStatus);
                    successData.put("qrCodeUrl", lastQr);
                    successData.put("loginTime", elapsed / 1000);
                    task.sendSuccess("登录成功", successData);

                    try {
                        Thread.sleep(1500);
                        boolean saved = com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                            .saveLoginState(session, "yuanbao", userId, loginStatus);
                        log.info("[Yuanbao扫码登录] 登录状态保存: {}", saved);
                    } catch (Exception e) {
                        log.warn("[Yuanbao扫码登录] 保存登录状态失败: {}", e.getMessage());
                    }
                    return;
                }

                // 兜底：若已进入可对话工作台且登录浮层已消失，判定流程完成（避免长期卡在扫码态）
                if (yuanbaoUtil.isChatWorkbenchReady(page) && !yuanbaoUtil.hasLoginLayer(page)) {
                    workbenchStableCount++;
                    if (workbenchStableCount >= 3) {
                        Map<String, Object> successData = new HashMap<>();
                        successData.put("success", true);
                        successData.put("userName", "元宝会话可用");
                        successData.put("qrCodeUrl", lastQr);
                        successData.put("loginTime", elapsed / 1000);
                        successData.put("fallbackMode", "workbench_ready");
                        task.sendSuccess("登录流程完成（会话可用）", successData);
                        try {
                            Thread.sleep(1200);
                            boolean saved = com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                                .saveLoginState(session, "yuanbao", userId, "元宝会话可用");
                            log.info("[Yuanbao扫码登录] fallback登录状态保存: {}", saved);
                        } catch (Exception saveErr) {
                            log.warn("[Yuanbao扫码登录] fallback保存登录状态失败: {}", saveErr.getMessage());
                        }
                        return;
                    }
                } else {
                    workbenchStableCount = 0;
                }
                try {
                    page.waitForTimeout(2000);
                } catch (Exception closedErr) {
                    log.warn("[Yuanbao扫码登录] 页面等待被中断（可能窗口被关闭）: {}", closedErr.getMessage());
                    task.sendLog("检测到浏览器页面中断，请保持Engine侧浏览器窗口开启后重试");
                    return;
                }
            }
        } catch (Exception e) {
            log.error("[Yuanbao扫码登录] 失败", e);
            task.sendError("扫码登录失败: " + e.getMessage());
        } finally {
            task.stop();
            if (session != null) {
                try {
                    session.touch();
                    browserPool.release(session);
                } catch (Exception e) {
                    log.warn("[Yuanbao扫码登录] 释放会话失败: {}", e.getMessage());
                }
            }
            userLock.unlock();
        }
    }

    @StreamCapability(
        type = "AI_YUANBAO_QUERY",
        description = "元宝 AI 咨询",
        progressInterval = 6000
    )
    public void handleQuery(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);
        String aiType = extractAiType(message);

        String rawJson = message.getRawJson();
        if (rawJson == null || rawJson.isEmpty()) {
            log.error("[Yuanbao咨询] 原始JSON为空");
            return;
        }

        JSONObject rootJson = com.alibaba.fastjson2.JSON.parseObject(rawJson);
        if (rootJson == null) {
            log.error("[Yuanbao咨询] JSON解析失败");
            return;
        }

        JSONObject payload = rootJson.getJSONObject("payload");
        if (payload == null) {
            log.error("[Yuanbao咨询] payload 不存在");
            return;
        }

        String query = payload.getString("query");
        String ybChatId = payload.getString("ybChatId");
        if (ybChatId == null || ybChatId.isEmpty()) {
            ybChatId = payload.getString("yuanbaoChatId");
        }
        CapabilityNormalizer.NormalizedCapabilities normalized = CapabilityNormalizer.normalize(aiType, payload);
        boolean enableFileUpload = normalized.isFileUploadEnabled();
        String uploadedFileUrl = normalized.getFileUploadUrl();

        StreamTask task = startAiStreamTask(userId, sessionId, aiType, 6000);
        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            if (!normalized.getUnsupportedOptionIds().isEmpty()) {
                task.sendLog("检测到不支持能力，已自动忽略: " + String.join(", ", normalized.getUnsupportedOptionIds()));
            }
            if (query == null || query.trim().isEmpty()) {
                task.sendError("问题内容不能为空");
                return;
            }

            task.sendLog("正在连接元宝...");
            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session;
            session.touch();

            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager.hasLoginState("yuanbao", userId)) {
                try {
                    com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "yuanbao", userId);
                } catch (Exception e) {
                    log.warn("[Yuanbao咨询] 恢复登录状态异常: {}", e.getMessage());
                }
            }

            Page page = sessionPage.page;

            if (ybChatId != null && !ybChatId.isEmpty()) {
                task.sendLog("正在恢复元宝会话: " + ybChatId);
                if (!yuanbaoUtil.navigateToChat(page, ybChatId)) {
                    task.sendError("导航到元宝会话失败: " + ybChatId);
                    return;
                }
                // 元宝在仅恢复Cookie时可能触发账号类型弹窗，补充页面级Storage恢复
                com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                    .restoreStorageToPage(page, "yuanbao", userId);
                yuanbaoUtil.tryHandleAccountTypePopup(page);
                task.sendLog("正在检查登录状态...");
                if ("false".equals(yuanbaoUtil.checkLoginStatus(page, false))) {
                    task.sendError("未登录，请先在登录管理器中完成元宝登录");
                    return;
                }
            } else {
                task.sendLog("正在检查登录状态...");
                if ("false".equals(yuanbaoUtil.checkLoginStatus(page, true))) {
                    task.sendError("未登录，请先在登录管理器中完成元宝登录");
                    return;
                }
                com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                    .restoreStorageToPage(page, "yuanbao", userId);
                yuanbaoUtil.tryHandleAccountTypePopup(page);
            }

            boolean uploadAttempted = false;
            boolean uploadEffective = true;
            if (enableFileUpload && uploadedFileUrl != null && !uploadedFileUrl.isEmpty()) {
                uploadAttempted = true;
                task.sendLog("检测到文件，正在上传到元宝...");
                FileDownloadUtil.UploadResult fileResult = fileDownloadUtil.downloadAndUploadWithFallback(
                    uploadedFileUrl,
                    page,
                    (p, localFilePath) -> yuanbaoUtil.uploadFile(p, localFilePath),
                    () -> {
                        try {
                            page.locator("textarea, div[contenteditable='true']").first()
                                .scrollIntoViewIfNeeded();
                            page.waitForTimeout(400);
                        } catch (Exception ignore) {
                            // ignore
                        }
                    },
                    new String[]{
                        "button:has-text('上传文件')",
                        "button:has-text('上传')",
                        "[role='button']:has-text('上传')",
                        "button:has-text('附件')",
                        "[aria-label*='上传']",
                        "[class*='upload']"
                    }
                );
                uploadEffective = fileResult.isSuccess();
                if (!fileResult.isSuccess()) {
                    task.sendLog("文件处理失败(" + fileResult.getErrorMessage() + ")，将继续发送文本消息");
                    log.warn("[Yuanbao咨询] 文件上传失败: {}", fileResult.getErrorMessage());
                } else {
                    task.sendLog("文件已上传，等待平台解析...");
                    page.waitForTimeout(1200);
                }
            }

            task.sendLog("登录验证通过，准备发送问题...");
            long startTime = System.currentTimeMillis();
            final BrowserSession sessionRef = session;

            task.startAutoProgress(count -> {
                try {
                    sessionRef.touch();
                    long sec = (System.currentTimeMillis() - startTime) / 1000;
                    String logMessage = "元宝正在生成回复（已等待 " + sec + " 秒）...";
                    task.sendLog(logMessage);
                    String shot = captureAndUpload(page, userId, "yuanbao_progress_" + count);
                    if (shot != null) {
                        task.sendScreenshot(shot);
                    }
                    return logMessage;
                } catch (Exception e) {
                    log.warn("[Yuanbao咨询] 进度更新失败", e);
                    return "元宝处理中...";
                }
            });

            String answer = yuanbaoUtil.sendMessageAndWaitResponse(page, query);
            task.stop();

            page.waitForTimeout(1500);
            task.sendLog("正在提取会话信息...");

            String newChatId = yuanbaoUtil.extractChatId(page);
            String shareUrl = null;
            if (newChatId != null && !newChatId.isEmpty()) {
                shareUrl = "https://yuanbao.tencent.com/chat/" + newChatId;
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("query", query);
            resultData.put("chatId", newChatId);
            resultData.put("shareUrl", shareUrl);
            resultData.put("mode", "normal");
            resultData.put("elapsedTime", (int) ((System.currentTimeMillis() - startTime) / 1000));
            resultData.put("textContent", answer != null ? answer : "");
            resultData.put("answer", answer != null ? answer : "元宝回复完成，但获取内容失败");
            resultData.put("hasScreenshot", false);
            Map<String, Object> qualityGate = com.wx.fbsir.engine.utils.ai.ResponseQualityGate.evaluate(
                query, answer, uploadAttempted, uploadEffective, uploadedFileUrl
            );
            resultData.put("qualityGate", qualityGate);
            if ("suspect".equals(String.valueOf(qualityGate.get("status")))) {
                task.sendLog("结果门禁提示：" + qualityGate.get("summary"));
            }

            task.sendSuccess("元宝回复完成", resultData);
        } catch (Exception e) {
            log.error("[Yuanbao咨询] 失败", e);
            task.sendError("咨询失败: " + e.getMessage());
        } finally {
            task.stop();
            if (session != null) {
                try {
                    session.touch();
                    browserPool.release(session);
                } catch (Exception e) {
                    log.warn("[Yuanbao咨询] 释放会话失败: {}", e.getMessage());
                }
            }
            userLock.unlock();
        }
    }

    private String extractSessionId(EngineMessage message) {
        Object sessionId = message.getPayloadValue("sessionId");
        return sessionId != null ? sessionId.toString() : "unknown";
    }

    private String extractAiType(EngineMessage message) {
        Object aiType = message.getPayloadValue("aiType");
        return aiType != null ? aiType.toString() : "yuanbao";
    }

    private void sendResult(String userId, String sessionId, String aiType, Map<String, Object> data) {
        EngineMessage result = EngineMessage.builder()
            .type(MessageType.TASK_RESULT.getCode())
            .userId(userId)
            .payload("sessionId", sessionId)
            .payload("aiType", aiType)
            .payload("success", true)
            .payload("data", data)
            .payload("timestamp", System.currentTimeMillis())
            .build();
        webSocketClientManager.sendMessage(result);
    }

    private void sendErrorResult(String userId, String sessionId, String aiType, String errorMessage) {
        EngineMessage result = EngineMessage.builder()
            .type(MessageType.AI_TASK_ERROR.getCode())
            .userId(userId)
            .payload("sessionId", sessionId)
            .payload("aiType", aiType)
            .payload("success", false)
            .payload("errorCode", "TASK_ERROR")
            .payload("errorMessage", errorMessage)
            .payload("timestamp", System.currentTimeMillis())
            .build();
        webSocketClientManager.sendMessage(result);
    }

    private String captureAndUpload(Page page, String userId, String fileName) {
        try {
            byte[] screenshotBytes = page.screenshot();
            com.wx.fbsir.engine.playwright.util.ScreenshotUploadClient.UploadResult result =
                uploadClient.uploadScreenshot(userId, fileName, screenshotBytes);
            return result.isSuccess() ? result.getUrl() : null;
        } catch (Exception e) {
            log.debug("[Yuanbao截图] 失败: {}", e.getMessage());
            return null;
        }
    }

    private BrowserSession acquireFixedYuanbaoSession(String userId) {
        return browserPool.acquire(userId, "yuanbao", YUANBAO_FIXED_INSTANCE, true, false);
    }

    private ReentrantLock getUserSerialLock(String userId) {
        return USER_SERIAL_LOCKS.computeIfAbsent(userId, k -> new ReentrantLock());
    }

    private SessionPage acquireSessionPageWithRecovery(String userId) {
        BrowserSession session = acquireFixedYuanbaoSession(userId);
        try {
            Page page = session.getOrCreatePage();
            // 复用会话后做一次健康探测，避免拿到已失效但对象仍存在的 Page。
            page.url();
            return new SessionPage(session, page);
        } catch (Exception firstEx) {
            if (!isTargetClosed(firstEx)) {
                throw firstEx;
            }
            log.warn("[Yuanbao会话] 检测到固定实例失效，准备重建 - 用户: {}, 错误: {}", userId, firstEx.getMessage());
            try {
                browserPool.destroy(session);
            } catch (Exception destroyEx) {
                log.warn("[Yuanbao会话] 销毁失效会话异常 - 用户: {}, 错误: {}", userId, destroyEx.getMessage());
            }
            BrowserSession rebuilt = acquireFixedYuanbaoSession(userId);
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

    private Page trySwitchToActivePage(BrowserSession session, Page currentPage) {
        try {
            List<Page> pages = session.getContext().pages();
            for (int i = pages.size() - 1; i >= 0; i--) {
                Page candidate = pages.get(i);
                if (candidate == null || candidate.isClosed()) {
                    continue;
                }
                String url;
                try {
                    url = candidate.url();
                } catch (Exception e) {
                    continue;
                }
                if (url == null || url.isBlank() || "about:blank".equalsIgnoreCase(url)) {
                    continue;
                }
                if (candidate == currentPage) {
                    return currentPage;
                }
                try {
                    candidate.bringToFront();
                } catch (Exception ignore) {
                    // ignore
                }
                log.info("[Yuanbao扫码登录] 页面切换 - 从 {} 到 {}", safeUrl(currentPage), url);
                return candidate;
            }
        } catch (Exception e) {
            log.debug("[Yuanbao扫码登录] 尝试切换活动页失败: {}", e.getMessage());
        }
        return currentPage;
    }

    private String safeUrl(Page page) {
        if (page == null) {
            return "null";
        }
        try {
            return page.url();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private Page tryRecoverFromDrift(BrowserSession session, Page currentPage) {
        try {
            if (currentPage != null && !currentPage.isClosed()) {
                if (yuanbaoUtil.softRecoverScanToHome(currentPage)) {
                    return currentPage;
                }
            }
        } catch (Exception e) {
            log.debug("[Yuanbao扫码登录] 当前页软恢复失败: {}", e.getMessage());
        }
        try {
            Page freshPage = session.getContext().newPage();
            try {
                freshPage.bringToFront();
            } catch (Exception ignore) {
                // ignore
            }
            if (yuanbaoUtil.softRecoverScanToHome(freshPage)) {
                log.info("[Yuanbao扫码登录] 漂移恢复成功 - 已切换到新页面: {}", safeUrl(freshPage));
                return freshPage;
            }
            return freshPage;
        } catch (Exception e) {
            log.warn("[Yuanbao扫码登录] 新建页面恢复失败: {}", e.getMessage());
            return currentPage;
        }
    }
}
