package com.wx.fbsir.engine.controller.ai;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;
import com.wx.fbsir.engine.capability.CapabilityNormalizer;
import com.wx.fbsir.engine.capability.annotation.OnceCapability;
import com.wx.fbsir.engine.capability.annotation.StreamCapability;
import com.wx.fbsir.engine.capability.base.StreamTaskHelper;
import com.wx.fbsir.engine.playwright.pool.BrowserPoolManager;
import com.wx.fbsir.engine.playwright.session.BrowserSession;
import com.wx.fbsir.engine.utils.ai.QianwenUtil;
import com.wx.fbsir.engine.utils.common.FileDownloadUtil;
import com.wx.fbsir.engine.websocket.message.EngineMessage;
import com.wx.fbsir.engine.websocket.message.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

/**
 * 千问（Qianwen）网页版 WebSocket 控制器，流程对齐豆包/DeepSeek。
 */
@Controller
public class QianwenController extends StreamTaskHelper {

    @Autowired
    private QianwenUtil qianwenUtil;

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
        type = "QIANWEN_CHECK_LOGIN",
        description = "检查千问登录状态",
        timeout = 30000L
    )
    public void handleCheckLogin(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);
        String aiType = extractAiType(message);

        log.info("[Qianwen登录检测] 开始 - 用户: {}, 会话: {}", userId, sessionId);
        BrowserSession session = null;
        try {
            session = acquireQianwenSessionWithHealth(userId);

            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager.hasLoginState("qianwen", userId)) {
                try {
                    boolean restored = com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "qianwen", userId);
                    if (restored) {
                        log.info("[Qianwen登录检测] 登录状态已恢复 - 用户: {}", userId);
                    }
                } catch (Exception e) {
                    log.warn("[Qianwen登录检测] 恢复登录状态异常: {}", e.getMessage());
                }
            }

            String loginStatus = qianwenUtil.checkLoginStatus(session.getOrCreatePage(), true);
            boolean isLoggedIn = !"false".equals(loginStatus);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("isLoggedIn", isLoggedIn);
            resultData.put("userName", isLoggedIn ? loginStatus : null);
            resultData.put("platform", "Qianwen");
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(userId, sessionId, aiType, resultData);
            log.info("[Qianwen登录检测] 完成 - 已登录: {}", isLoggedIn);
        } catch (Exception e) {
            log.error("[Qianwen登录检测] 失败", e);
            sendErrorResult(userId, sessionId, aiType, "登录检测失败: " + e.getMessage());
        } finally {
            if (session != null) {
                try {
                    browserPool.destroy(session);
                } catch (Exception e) {
                    log.warn("[Qianwen登录检测] 销毁会话失败: {}", e.getMessage());
                }
            }
        }
    }

    @StreamCapability(
        type = "QIANWEN_SCAN_LOGIN",
        description = "千问扫码/页面登录（轮询检测）",
        progressInterval = 2000
    )
    public void handleScanLogin(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);

        log.info("[Qianwen扫码登录] 开始 - 用户: {}, 会话: {}", userId, sessionId);
        StreamTask task = startStreamTask(userId, sessionId, extractAiType(message), 2000);
        BrowserSession session = null;
        try {
            task.sendLog("正在打开千问并唤起登录浮层...");
            session = acquireQianwenSessionWithHealth(userId);
            Page page = session.getOrCreatePage();

            if (!qianwenUtil.navigateToLoginPageAndOpenLoginLayer(page)) {
                task.sendError("无法打开千问页面，请检查网络");
                return;
            }

            task.sendLog("请使用手机扫码或验证码完成登录（状态按右上角登录入口是否消失判定）");
            String qrCodeUrl = captureAndUpload(page, userId, "qianwen_login_initial");
            if (qrCodeUrl != null) {
                task.sendScreenshot(qrCodeUrl);
            }

            long startTime = System.currentTimeMillis();
            long maxWaitTime = 180000;
            long lastScreenshotTime = System.currentTimeMillis();
            int screenshotCount = 1;
            String lastQr = qrCodeUrl;

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
                    String newUrl = captureAndUpload(page, userId, "qianwen_login_" + screenshotCount);
                    if (newUrl != null) {
                        lastQr = newUrl;
                        task.sendLog("页面截图已刷新，请继续完成登录（已等待 " + (elapsed / 1000) + " 秒）");
                        task.sendScreenshot(newUrl);
                    }
                    lastScreenshotTime = System.currentTimeMillis();
                }

                String loginStatus = qianwenUtil.checkLoginStatus(page, false);
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
                            .saveLoginState(session, "qianwen", userId, loginStatus);
                        log.info("[Qianwen扫码登录] 登录状态保存: {}", saved);
                    } catch (Exception e) {
                        log.warn("[Qianwen扫码登录] 保存登录状态失败: {}", e.getMessage());
                    }
                    return;
                }
                page.waitForTimeout(2000);
            }
        } catch (Exception e) {
            log.error("[Qianwen扫码登录] 失败", e);
            task.sendError("扫码登录失败: " + e.getMessage());
        } finally {
            task.stop();
            if (session != null) {
                try {
                    browserPool.destroy(session);
                } catch (Exception e) {
                    log.warn("[Qianwen扫码登录] 销毁会话失败: {}", e.getMessage());
                }
            }
        }
    }

    @StreamCapability(
        type = "AI_QIANWEN_QUERY",
        description = "千问 AI 咨询",
        progressInterval = 6000
    )
    public void handleQuery(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);
        String aiType = extractAiType(message);

        String rawJson = message.getRawJson();
        if (rawJson == null || rawJson.isEmpty()) {
            log.error("[Qianwen咨询] 原始JSON为空");
            sendError(userId, sessionId, aiType, "请求数据为空，无法执行咨询");
            return;
        }

        JSONObject rootJson = com.alibaba.fastjson2.JSON.parseObject(rawJson);
        if (rootJson == null) {
            log.error("[Qianwen咨询] JSON解析失败");
            sendError(userId, sessionId, aiType, "请求JSON解析失败");
            return;
        }

        JSONObject payload = rootJson.getJSONObject("payload");
        if (payload == null) {
            log.error("[Qianwen咨询] payload 不存在");
            sendError(userId, sessionId, aiType, "请求缺少 payload");
            return;
        }

        String query = payload.getString("query");
        String toneChatId = payload.getString("toneChatId");
        if (toneChatId == null || toneChatId.isEmpty()) {
            toneChatId = payload.getString("qianwenChatId");
        }
        if (toneChatId == null || toneChatId.isEmpty()) {
            toneChatId = payload.getString("tyChatId");
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

            task.sendLog("正在连接千问...");
            session = acquireQianwenSessionWithHealth(userId);

            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager.hasLoginState("qianwen", userId)) {
                try {
                    com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "qianwen", userId);
                } catch (Exception e) {
                    log.warn("[Qianwen咨询] 恢复登录状态异常: {}", e.getMessage());
                }
            }

            Page page = session.getOrCreatePage();

            if (toneChatId != null && !toneChatId.isEmpty()) {
                task.sendLog("正在恢复千问会话: " + toneChatId);
                if (!qianwenUtil.navigateToChat(page, toneChatId)) {
                    task.sendError("导航到千问会话失败: " + toneChatId);
                    return;
                }
                task.sendLog("正在检查登录状态...");
                if ("false".equals(qianwenUtil.checkLoginStatus(page, false))) {
                    task.sendError("未登录，请先在登录管理器中完成千问登录");
                    return;
                }
            } else {
                task.sendLog("正在检查登录状态...");
                if ("false".equals(qianwenUtil.checkLoginStatus(page, true))) {
                    task.sendError("未登录，请先在登录管理器中完成千问登录");
                    return;
                }
            }

            boolean uploadAttempted = false;
            boolean uploadEffective = true;
            if (enableFileUpload && uploadedFileUrl != null && !uploadedFileUrl.isEmpty()) {
                uploadAttempted = true;
                task.sendLog("检测到文件，正在上传到千问...");
                FileDownloadUtil.UploadResult fileResult = fileDownloadUtil.downloadAndUploadWithFallback(
                    uploadedFileUrl,
                    page,
                    (p, localFilePath) -> qianwenUtil.uploadFile(p, localFilePath),
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
                    log.warn("[Qianwen咨询] 文件上传失败: {}", fileResult.getErrorMessage());
                    if (shouldRetryUpload(fileResult.getErrorMessage())) {
                        task.sendLog("检测到上传链路瞬态异常，正在恢复会话后重试上传...");
                        BrowserSession recovered = acquireQianwenSessionWithHealth(userId);
                        if (session != recovered) {
                            try {
                                browserPool.destroy(session);
                            } catch (Exception ignore) {
                                // ignore
                            }
                            session = recovered;
                        }
                        page = session.getOrCreatePage();
                        if (toneChatId != null && !toneChatId.isEmpty()) {
                            qianwenUtil.navigateToChat(page, toneChatId);
                        } else {
                            qianwenUtil.checkLoginStatus(page, true);
                        }
                        FileDownloadUtil.UploadResult retryResult = fileDownloadUtil.downloadAndUploadWithFallback(
                            uploadedFileUrl,
                            page,
                            (p, localFilePath) -> qianwenUtil.uploadFile(p, localFilePath),
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
                        if (retryResult.isSuccess()) {
                            uploadEffective = true;
                            task.sendLog("重试上传成功，继续执行问答");
                        } else {
                            uploadEffective = false;
                            task.sendLog("重试上传仍失败(" + retryResult.getErrorMessage() + ")，将继续发送文本消息");
                            log.warn("[Qianwen咨询] 文件重试上传失败: {}", retryResult.getErrorMessage());
                        }
                    }
                } else {
                    task.sendLog("文件已上传，等待平台解析...");
                    page.waitForTimeout(1200);
                }
            }

            task.sendLog("登录验证通过，准备发送问题...");
            final Page activePage = page;
            long startTime = System.currentTimeMillis();

            task.startAutoProgress(count -> {
                try {
                    long sec = (System.currentTimeMillis() - startTime) / 1000;
                    String logMessage = "千问正在生成回复（已等待 " + sec + " 秒）...";
                    task.sendLog(logMessage);
                    String shot = captureAndUpload(activePage, userId, "qianwen_progress_" + count);
                    if (shot != null) {
                        task.sendScreenshot(shot);
                    }
                    return logMessage;
                } catch (Exception e) {
                    log.warn("[Qianwen咨询] 进度更新失败", e);
                    return "千问处理中...";
                }
            });

            String answer = qianwenUtil.sendMessageAndWaitResponse(activePage, query);
            task.stop();

            activePage.waitForTimeout(1500);
            task.sendLog("正在提取会话信息...");

            String newChatId = qianwenUtil.extractChatId(activePage);
            String shareUrl = null;
            if (newChatId != null && !newChatId.isEmpty()) {
                shareUrl = "https://www.qianwen.com/chat/" + newChatId;
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("query", query);
            resultData.put("chatId", newChatId);
            resultData.put("shareUrl", shareUrl);
            resultData.put("mode", "normal");
            resultData.put("elapsedTime", (int) ((System.currentTimeMillis() - startTime) / 1000));
            resultData.put("textContent", answer != null ? answer : "");
            resultData.put("answer", answer != null ? answer : "千问回复完成，但获取内容失败");
            resultData.put("hasScreenshot", false);
            Map<String, Object> qualityGate = com.wx.fbsir.engine.utils.ai.ResponseQualityGate.evaluate(
                query, answer, uploadAttempted, uploadEffective, uploadedFileUrl
            );
            resultData.put("qualityGate", qualityGate);
            if ("suspect".equals(String.valueOf(qualityGate.get("status")))) {
                task.sendLog("结果门禁提示：" + qualityGate.get("summary"));
            }

            task.sendSuccess("千问回复完成", resultData);
            log.info("[Qianwen咨询] 完成 - 会话: {}, 耗时: {}s", sessionId, resultData.get("elapsedTime"));
        } catch (Exception e) {
            log.error("[Qianwen咨询] 失败", e);
            task.sendError("咨询失败: " + e.getMessage());
        } finally {
            task.stop();
            if (session != null) {
                try {
                    browserPool.destroy(session);
                } catch (Exception e) {
                    log.warn("[Qianwen咨询] 销毁会话失败: {}", e.getMessage());
                }
            }
        }
    }

    private String extractSessionId(EngineMessage message) {
        Object sessionId = message.getPayloadValue("sessionId");
        return sessionId != null ? sessionId.toString() : "unknown";
    }

    private String extractAiType(EngineMessage message) {
        Object aiType = message.getPayloadValue("aiType");
        return aiType != null ? aiType.toString() : "qianwen";
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
            log.debug("[Qianwen截图] 失败: {}", e.getMessage());
            return null;
        }
    }

    private BrowserSession acquireQianwenSessionWithHealth(String userId) {
        BrowserSession session = browserPool.acquirePersistent(userId, "qianwen", false);
        try {
            Page page = session.getOrCreatePage();
            page.url();
            return session;
        } catch (Exception firstEx) {
            if (!isTargetClosed(firstEx)) {
                throw (firstEx instanceof RuntimeException re) ? re : new RuntimeException(firstEx);
            }
            log.warn("[千问会话] 检测到 Page/Context 失效，准备重建 - 用户: {}, 错误: {}", userId, firstEx.getMessage());
            try {
                browserPool.destroy(session);
            } catch (Exception ignore) {
                // ignore
            }
            BrowserSession rebuilt = browserPool.acquirePersistent(userId, "qianwen", false);
            Page rebuiltPage = rebuilt.getOrCreatePage();
            rebuiltPage.url();
            return rebuilt;
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

    private boolean shouldRetryUpload(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return false;
        }
        String msg = errorMessage.toLowerCase();
        return msg.contains("adopt")
            || msg.contains("target page")
            || msg.contains("targetclosederror")
            || msg.contains("browser has been closed")
            || msg.contains("execution context was destroyed");
    }
}
