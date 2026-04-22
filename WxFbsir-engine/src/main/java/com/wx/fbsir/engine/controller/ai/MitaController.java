package com.wx.fbsir.engine.controller.ai;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import com.wx.fbsir.engine.capability.CapabilityNormalizer;
import com.wx.fbsir.engine.capability.annotation.OnceCapability;
import com.wx.fbsir.engine.capability.annotation.StreamCapability;
import com.wx.fbsir.engine.capability.base.StreamTaskHelper;
import com.wx.fbsir.engine.playwright.pool.BrowserPoolManager;
import com.wx.fbsir.engine.playwright.pool.BrowserUserSerialLocks;
import com.wx.fbsir.engine.playwright.session.BrowserSession;
import com.wx.fbsir.engine.utils.ai.MitaUtil;
import com.wx.fbsir.engine.utils.common.FileDownloadUtil;
import com.wx.fbsir.engine.websocket.message.EngineMessage;
import com.wx.fbsir.engine.websocket.message.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 秘塔 AI 搜索（metaso.cn）WebSocket 控制器。
 */
@Controller
public class MitaController extends StreamTaskHelper {

    @Autowired
    private MitaUtil mitaUtil;

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
        type = "MITA_CHECK_LOGIN",
        description = "检查秘塔登录状态",
        timeout = 30000L
    )
    public void handleCheckLogin(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);
        String aiType = extractAiType(message);

        ReentrantLock userLock = BrowserUserSerialLocks.lockFor("mita", userId);
        userLock.lock();
        try {
        log.info("[秘塔登录检测] 开始 - 用户: {}, 会话: {}", userId, sessionId);
        BrowserSession session = null;
        try {
            session = acquireMitaSessionWithHealth(userId);

            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager.hasLoginState("mita", userId)) {
                try {
                    boolean restored = com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "mita", userId);
                    if (restored) {
                        log.info("[秘塔登录检测] 登录状态已恢复 - 用户: {}", userId);
                    }
                } catch (Exception e) {
                    log.warn("[秘塔登录检测] 恢复登录状态异常: {}", e.getMessage());
                }
            }

            Page page = session.getOrCreatePage();
            String loginStatus = mitaUtil.checkLoginStatus(page, true);
            if ("false".equals(loginStatus) && com.wx.fbsir.engine.playwright.login.manager.LoginStateManager.hasLoginState("mita", userId)) {
                try {
                    log.info("[秘塔登录检测] 已持久化登录态但首次检测为未登录，刷新页面后重试");
                    page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000));
                    page.waitForTimeout(1200);
                    loginStatus = mitaUtil.checkLoginStatus(page, false);
                } catch (Exception e) {
                    log.warn("[秘塔登录检测] 刷新重试失败: {}", e.getMessage());
                }
            }
            boolean isLoggedIn = !"false".equals(loginStatus);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("isLoggedIn", isLoggedIn);
            resultData.put("userName", isLoggedIn ? loginStatus : null);
            resultData.put("platform", "Mita");
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(userId, sessionId, aiType, resultData);
            log.info("[秘塔登录检测] 完成 - 已登录: {}", isLoggedIn);
        } catch (Exception e) {
            log.error("[秘塔登录检测] 失败", e);
            sendErrorResult(userId, sessionId, aiType, "登录检测失败: " + e.getMessage());
        } finally {
            if (session != null) {
                try {
                    session.touch();
                    browserPool.release(session);
                } catch (Exception e) {
                    log.warn("[秘塔登录检测] 释放会话失败: {}", e.getMessage());
                }
            }
        }
        } finally {
            userLock.unlock();
        }
    }

    @StreamCapability(
        type = "MITA_SCAN_LOGIN",
        description = "秘塔扫码/页面登录（轮询检测）",
        progressInterval = 2000
    )
    public void handleScanLogin(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);

        ReentrantLock userLock = BrowserUserSerialLocks.lockFor("mita", userId);
        userLock.lock();
        try {
        log.info("[秘塔扫码登录] 开始 - 用户: {}, 会话: {}", userId, sessionId);
        StreamTask task = startStreamTask(userId, sessionId, extractAiType(message), 2000);
        BrowserSession session = null;
        try {
            task.sendLog("正在打开秘塔并唤起登录...");
            session = acquireMitaSessionWithHealth(userId);
            Page page = session.getOrCreatePage();

            if (!mitaUtil.navigateToLoginPageAndOpenLoginLayer(page)) {
                task.sendError("无法打开秘塔页面，请检查网络");
                return;
            }

            task.sendLog("请完成登录（扫码或账号）");
            String qrCodeUrl = captureAndUpload(page, userId, "mita_login_initial");
            if (qrCodeUrl != null) {
                task.sendScreenshot(qrCodeUrl);
            }

            long startTime = System.currentTimeMillis();
            long maxWaitTime = 180000;
            long lastScreenshotTime = System.currentTimeMillis();
            int screenshotCount = 1;
            String lastQr = qrCodeUrl;
            int stableWorkbenchCount = 0;

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

                if (System.currentTimeMillis() - lastScreenshotTime >= 30000) {
                    screenshotCount++;
                    String newUrl = captureAndUpload(page, userId, "mita_login_" + screenshotCount);
                    if (newUrl != null) {
                        lastQr = newUrl;
                        task.sendLog("页面截图已刷新，请继续完成登录（已等待 " + (elapsed / 1000) + " 秒）");
                        task.sendScreenshot(newUrl);
                    }
                    lastScreenshotTime = System.currentTimeMillis();
                }

                String loginStatus = mitaUtil.checkLoginStatus(page, false);
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
                            .saveLoginState(session, "mita", userId, loginStatus);
                        log.info("[秘塔扫码登录] 登录状态保存: {}", saved);
                    } catch (Exception e) {
                        log.warn("[秘塔扫码登录] 保存登录状态失败: {}", e.getMessage());
                    }
                    return;
                }

                if (!mitaUtil.isLoginLayerPresent(page) && mitaUtil.hasStableWorkbenchReady(page)) {
                    stableWorkbenchCount++;
                    if (stableWorkbenchCount >= 2) {
                        String fallbackUser = "已登录用户";
                        Map<String, Object> successData = new HashMap<>();
                        successData.put("success", true);
                        successData.put("userName", fallbackUser);
                        successData.put("qrCodeUrl", lastQr);
                        successData.put("loginTime", elapsed / 1000);
                        task.sendSuccess("登录成功", successData);
                        try {
                            Thread.sleep(1500);
                            com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                                .saveLoginState(session, "mita", userId, fallbackUser);
                        } catch (Exception e) {
                            log.warn("[秘塔扫码登录] fallback 保存登录状态失败: {}", e.getMessage());
                        }
                        return;
                    }
                } else {
                    stableWorkbenchCount = 0;
                }
                page.waitForTimeout(2000);
            }
        } catch (Exception e) {
            log.error("[秘塔扫码登录] 失败", e);
            task.sendError("扫码登录失败: " + e.getMessage());
        } finally {
            task.stop();
            if (session != null) {
                try {
                    session.touch();
                    browserPool.release(session);
                } catch (Exception e) {
                    log.warn("[秘塔扫码登录] 释放会话失败: {}", e.getMessage());
                }
            }
        }
        } finally {
            userLock.unlock();
        }
    }

    @StreamCapability(
        type = "AI_MITA_QUERY",
        description = "秘塔 AI 咨询",
        progressInterval = 6000
    )
    public void handleQuery(EngineMessage message) {
        String userId = message.getUserId();
        ReentrantLock userLock = BrowserUserSerialLocks.lockFor("mita", userId);
        userLock.lock();
        try {
        String sessionId = extractSessionId(message);
        String aiType = extractAiType(message);

        String rawJson = message.getRawJson();
        if (rawJson == null || rawJson.isEmpty()) {
            log.error("[秘塔咨询] 原始JSON为空");
            sendError(userId, sessionId, aiType, "请求数据为空，无法执行咨询");
            return;
        }

        JSONObject rootJson = com.alibaba.fastjson2.JSON.parseObject(rawJson);
        if (rootJson == null) {
            log.error("[秘塔咨询] JSON解析失败");
            sendError(userId, sessionId, aiType, "请求JSON解析失败");
            return;
        }

        JSONObject payload = rootJson.getJSONObject("payload");
        if (payload == null) {
            log.error("[秘塔咨询] payload 不存在");
            sendError(userId, sessionId, aiType, "请求缺少 payload");
            return;
        }

        String query = payload.getString("query");
        boolean isNewChat = payload.getBooleanValue("isNewChat");
        String metasoChatId = payload.getString("metasoChatId");
        if (metasoChatId == null || metasoChatId.isEmpty()) {
            metasoChatId = payload.getString("mitaChatId");
        }
        if (isNewChat) {
            // 新会话必须从首页起步，避免误恢复到历史AI会话
            metasoChatId = null;
        }
        CapabilityNormalizer.NormalizedCapabilities normalized = CapabilityNormalizer.normalize(aiType, payload);
        boolean enableFileUpload = normalized.isFileUploadEnabled();
        String uploadedFileUrl = normalized.getFileUploadUrl();

        StreamTask task = startAiStreamTask(userId, sessionId, aiType, 6000);
        BrowserSession session = null;

        try {
            if (!normalized.getUnsupportedOptionIds().isEmpty()) {
                task.sendLog("检测到不支持能力，已自动忽略: " + String.join(", ", normalized.getUnsupportedOptionIds()));
            }
            if (query == null || query.trim().isEmpty()) {
                task.sendError("问题内容不能为空");
                return;
            }

            task.sendLog("正在连接秘塔...");
            session = acquireMitaSessionWithHealth(userId);

            boolean restoredLoginState = false;
            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager.hasLoginState("mita", userId)) {
                try {
                    com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "mita", userId);
                    restoredLoginState = true;
                } catch (Exception e) {
                    log.warn("[秘塔咨询] 恢复登录状态异常: {}", e.getMessage());
                }
            }

            Page page = session.getOrCreatePage();

            if (metasoChatId != null && !metasoChatId.isEmpty()) {
                task.sendLog("正在恢复秘塔页面: " + metasoChatId);
                if (!mitaUtil.navigateToChat(page, metasoChatId)) {
                    task.sendError("导航到秘塔页面失败: " + metasoChatId);
                    return;
                }
                task.sendLog("正在检查登录状态...");
                String loginStatus = mitaUtil.checkLoginStatus(page, false);
                if ("false".equals(loginStatus) && restoredLoginState) {
                    task.sendLog("检测到已恢复登录态，正在刷新页面后重试登录检测...");
                    page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000));
                    page.waitForTimeout(1000);
                    loginStatus = mitaUtil.checkLoginStatus(page, false);
                }
                if ("false".equals(loginStatus)) {
                    if (restoredLoginState) {
                        task.sendLog("登录检测可能误判，继续尝试发送问题...");
                        log.warn("[秘塔咨询] 登录检测返回未登录，但已恢复登录态，继续执行 - 用户: {}", userId);
                    } else {
                        task.sendError("未登录，请先在登录管理器中完成秘塔登录");
                        return;
                    }
                }
            } else {
                if (isNewChat) {
                    task.sendLog("已选择新会话，跳过历史会话恢复");
                }
                task.sendLog("正在检查登录状态...");
                String loginStatus = mitaUtil.checkLoginStatus(page, true);
                if ("false".equals(loginStatus) && restoredLoginState) {
                    task.sendLog("检测到已恢复登录态，正在刷新页面后重试登录检测...");
                    page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30000));
                    page.waitForTimeout(1000);
                    loginStatus = mitaUtil.checkLoginStatus(page, false);
                }
                if ("false".equals(loginStatus)) {
                    if (restoredLoginState) {
                        task.sendLog("登录检测可能误判，继续尝试发送问题...");
                        log.warn("[秘塔咨询] 登录检测返回未登录，但已恢复登录态，继续执行 - 用户: {}", userId);
                    } else {
                        task.sendError("未登录，请先在登录管理器中完成秘塔登录");
                        return;
                    }
                }
            }

            boolean uploadAttempted = false;
            boolean uploadEffective = true;
            if (enableFileUpload && uploadedFileUrl != null && !uploadedFileUrl.isEmpty()) {
                uploadAttempted = true;
                task.sendLog("检测到文件，正在上传到秘塔...");
                FileDownloadUtil.UploadResult fileResult = fileDownloadUtil.downloadAndUploadWithFallback(
                    uploadedFileUrl,
                    page,
                    (p, localFilePath) -> mitaUtil.uploadFile(p, localFilePath),
                    null,
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
                    log.warn("[秘塔咨询] 文件上传失败: {}", fileResult.getErrorMessage());
                } else {
                    task.sendLog("文件已上传，等待平台解析...");
                    page.waitForTimeout(1200);
                }
            }

            task.sendLog("登录验证通过，准备发送问题...");
            long startTime = System.currentTimeMillis();

            task.startAutoProgress(count -> {
                try {
                    long sec = (System.currentTimeMillis() - startTime) / 1000;
                    String logMessage = "秘塔正在生成回复（已等待 " + sec + " 秒）...";
                    task.sendLog(logMessage);
                    String shot = captureAndUpload(page, userId, "mita_progress_" + count);
                    if (shot != null) {
                        task.sendScreenshot(shot);
                    }
                    return logMessage;
                } catch (Exception e) {
                    log.warn("[秘塔咨询] 进度更新失败", e);
                    return "秘塔处理中...";
                }
            });

            String answer = mitaUtil.sendMessageAndWaitResponse(page, query);
            task.stop();

            task.sendLog("秘塔回复完成，正在整理结果...");
            page.waitForTimeout(1500);
            task.sendLog("正在提取会话信息...");

            String newChatId = mitaUtil.extractChatId(page);
            String shareUrl = null;
            if (newChatId != null && !newChatId.isEmpty()) {
                shareUrl = "https://metaso.cn/" + newChatId;
            }

            String conversationScreenshot = captureAndUpload(page, userId, "mita_conversation_" + System.currentTimeMillis());
            if (conversationScreenshot != null && !conversationScreenshot.isEmpty()) {
                task.sendScreenshot(conversationScreenshot);
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("query", query);
            resultData.put("chatId", newChatId);
            resultData.put("shareUrl", shareUrl);
            resultData.put("mode", "normal");
            resultData.put("elapsedTime", (int) ((System.currentTimeMillis() - startTime) / 1000));
            resultData.put("textContent", answer != null ? answer : "");
            resultData.put("answer", answer != null ? answer : "秘塔回复完成，但获取内容失败");
            resultData.put("conversationScreenshot", conversationScreenshot);
            resultData.put("hasScreenshot", conversationScreenshot != null && !conversationScreenshot.isEmpty());
            Map<String, Object> qualityGate = com.wx.fbsir.engine.utils.ai.ResponseQualityGate.evaluate(
                query, answer, uploadAttempted, uploadEffective, uploadedFileUrl
            );
            resultData.put("qualityGate", qualityGate);
            if ("suspect".equals(String.valueOf(qualityGate.get("status")))) {
                task.sendLog("结果门禁提示：" + qualityGate.get("summary"));
            }

            task.sendSuccess("秘塔回复完成", resultData);
            log.info("[秘塔咨询] 完成 - 会话: {}, 耗时: {}s", sessionId, resultData.get("elapsedTime"));
        } catch (Exception e) {
            log.error("[秘塔咨询] 失败", e);
            task.sendError("咨询失败: " + e.getMessage());
        } finally {
            task.stop();
            if (session != null) {
                try {
                    session.touch();
                    browserPool.release(session);
                } catch (Exception e) {
                    log.warn("[秘塔咨询] 释放会话失败: {}", e.getMessage());
                }
            }
        }
        } finally {
            userLock.unlock();
        }
    }

    /**
     * 与元宝类似：持久化会话复用后探测 Page 是否仍可用，失效则销毁并重建，避免 about:blank / 已关闭页导致误判未登录。
     */
    private BrowserSession acquireMitaSessionWithHealth(String userId) {
        BrowserSession session = browserPool.acquirePersistent(userId, "mita", false);
        try {
            Page page = session.getOrCreatePage();
            page.url();
            return session;
        } catch (Exception firstEx) {
            if (!isTargetClosed(firstEx)) {
                throw (firstEx instanceof RuntimeException re) ? re : new RuntimeException(firstEx);
            }
            log.warn("[秘塔会话] 检测到 Page/Context 失效，准备重建 - 用户: {}, 错误: {}", userId, firstEx.getMessage());
            try {
                browserPool.destroy(session);
            } catch (Exception ignore) {
            }
            BrowserSession rebuilt = browserPool.acquirePersistent(userId, "mita", false);
            Page rebuiltPage = rebuilt.getOrCreatePage();
            rebuiltPage.url();
            return rebuilt;
        }
    }

    private static boolean isTargetClosed(Throwable ex) {
        if (ex == null || ex.getMessage() == null) {
            return false;
        }
        String msg = ex.getMessage().toLowerCase();
        return msg.contains("target page, context or browser has been closed")
            || msg.contains("targetclosederror")
            || msg.contains("browser has been closed");
    }

    private String extractSessionId(EngineMessage message) {
        Object sessionId = message.getPayloadValue("sessionId");
        return sessionId != null ? sessionId.toString() : "unknown";
    }

    private String extractAiType(EngineMessage message) {
        Object aiType = message.getPayloadValue("aiType");
        return aiType != null ? aiType.toString() : "mita";
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
            log.debug("[秘塔截图] 失败: {}", e.getMessage());
            return null;
        }
    }
}
