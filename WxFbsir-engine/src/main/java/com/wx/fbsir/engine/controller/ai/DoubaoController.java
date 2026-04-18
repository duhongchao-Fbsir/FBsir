package com.wx.fbsir.engine.controller.ai;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;
import com.wx.fbsir.engine.capability.CapabilityNormalizer;
import com.wx.fbsir.engine.capability.annotation.OnceCapability;
import com.wx.fbsir.engine.capability.annotation.StreamCapability;
import com.wx.fbsir.engine.capability.base.StreamTaskHelper;
import com.wx.fbsir.engine.playwright.pool.BrowserPoolManager;
import com.wx.fbsir.engine.playwright.session.BrowserSession;
import com.wx.fbsir.engine.utils.ai.DoubaoUtil;
import com.wx.fbsir.engine.utils.common.FileDownloadUtil;
import com.wx.fbsir.engine.websocket.message.EngineMessage;
import com.wx.fbsir.engine.websocket.message.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

/**
 * 豆包（Doubao）网页版 WebSocket 控制器，流程对齐 DeepSeek / Gitee。
 */
@Controller
public class DoubaoController extends StreamTaskHelper {

    @Autowired
    private DoubaoUtil doubaoUtil;

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
        type = "DOUBAO_CHECK_LOGIN",
        description = "检查豆包登录状态",
        timeout = 30000L
    )
    public void handleCheckLogin(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);
        String aiType = extractAiType(message);

        log.info("[Doubao登录检测] 开始 - 用户: {}, 会话: {}", userId, sessionId);

        BrowserSession session = null;
        try {
            session = browserPool.acquirePersistent(userId, "doubao", false);

            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager.hasLoginState("doubao", userId)) {
                try {
                    boolean restored = com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "doubao", userId);
                    if (restored) {
                        log.info("[Doubao登录检测] 登录状态已恢复 - 用户: {}", userId);
                    }
                } catch (Exception e) {
                    log.warn("[Doubao登录检测] 恢复登录状态异常: {}", e.getMessage());
                }
            }

            String loginStatus = doubaoUtil.checkLoginStatus(session.getOrCreatePage(), true);
            boolean isLoggedIn = !"false".equals(loginStatus);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("isLoggedIn", isLoggedIn);
            resultData.put("userName", isLoggedIn ? loginStatus : null);
            resultData.put("platform", "Doubao");
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(userId, sessionId, aiType, resultData);
            log.info("[Doubao登录检测] 完成 - 已登录: {}", isLoggedIn);
        } catch (Exception e) {
            log.error("[Doubao登录检测] 失败", e);
            sendErrorResult(userId, sessionId, aiType, "登录检测失败: " + e.getMessage());
        } finally {
            if (session != null) {
                try {
                    browserPool.destroy(session);
                } catch (Exception e) {
                    log.warn("[Doubao登录检测] 销毁会话失败: {}", e.getMessage());
                }
            }
        }
    }

    @StreamCapability(
        type = "DOUBAO_SCAN_LOGIN",
        description = "豆包扫码/页面登录（轮询检测）",
        progressInterval = 2000
    )
    public void handleScanLogin(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);
        String aiType = extractAiType(message);

        log.info("[Doubao扫码登录] 开始 - 用户: {}, 会话: {}", userId, sessionId);

        StreamTask task = startStreamTask(userId, sessionId, 2000);
        BrowserSession session = null;

        try {
            task.sendLog("正在打开豆包并唤起登录浮层（请点击截图中的「登录」若未自动弹出）...");
            session = browserPool.acquirePersistent(userId, "doubao", false);
            Page page = session.getOrCreatePage();

            if (!doubaoUtil.navigateToLoginPageAndOpenLoginLayer(page)) {
                task.sendError("无法打开豆包页面，请检查网络");
                return;
            }

            task.sendLog("已尝试点击「登录」，请使用手机扫码或验证码完成登录（状态按页面右上角「登录」是否消失判定）");

            String qrCodeUrl = captureAndUpload(page, userId, "doubao_login_initial");
            if (qrCodeUrl != null) {
                task.sendLog("请扫描截图中的二维码，或在本机弹出的浏览器窗口中完成登录");
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
                    String newUrl = captureAndUpload(page, userId, "doubao_login_" + screenshotCount);
                    if (newUrl != null) {
                        lastQr = newUrl;
                        task.sendLog("页面已刷新截图，请继续完成登录（已等待 " + (elapsed / 1000) + " 秒）");
                        task.sendScreenshot(newUrl);
                    }
                    lastScreenshotTime = System.currentTimeMillis();
                }

                String loginStatus = doubaoUtil.checkLoginStatus(page, false);
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
                            .saveLoginState(session, "doubao", userId, loginStatus);
                        log.info("[Doubao扫码登录] 登录状态保存: {}", saved);
                    } catch (Exception e) {
                        log.warn("[Doubao扫码登录] 保存登录状态失败: {}", e.getMessage());
                    }
                    return;
                }

                page.waitForTimeout(2000);
            }
        } catch (Exception e) {
            log.error("[Doubao扫码登录] 失败", e);
            task.sendError("扫码登录失败: " + e.getMessage());
        } finally {
            task.stop();
            if (session != null) {
                try {
                    browserPool.destroy(session);
                } catch (Exception e) {
                    log.warn("[Doubao扫码登录] 销毁会话失败: {}", e.getMessage());
                }
            }
        }
    }

    @StreamCapability(
        type = "AI_DOUBAO_QUERY",
        description = "豆包 AI 咨询",
        progressInterval = 6000
    )
    public void handleQuery(EngineMessage message) {
        String userId = message.getUserId();
        String sessionId = extractSessionId(message);
        String aiType = extractAiType(message);

        String rawJson = message.getRawJson();
        if (rawJson == null || rawJson.isEmpty()) {
            log.error("[Doubao咨询] 原始JSON为空");
            sendError(userId, sessionId, aiType, "请求数据为空，无法执行咨询");
            return;
        }

        JSONObject rootJson = com.alibaba.fastjson2.JSON.parseObject(rawJson);
        if (rootJson == null) {
            log.error("[Doubao咨询] JSON解析失败");
            sendError(userId, sessionId, aiType, "请求JSON解析失败");
            return;
        }

        JSONObject payload = rootJson.getJSONObject("payload");
        if (payload == null) {
            log.error("[Doubao咨询] payload 不存在");
            sendError(userId, sessionId, aiType, "请求缺少 payload");
            return;
        }

        String query = payload.getString("query");
        String dbChatId = payload.getString("dbChatId");
        if (dbChatId == null || dbChatId.isEmpty()) {
            dbChatId = payload.getString("doubaoChatId");
        }
        CapabilityNormalizer.NormalizedCapabilities normalized = CapabilityNormalizer.normalize(aiType, payload);
        boolean enableDeepThinking = normalized.isReasoning();
        boolean enableFastMode = normalized.isFastMode();
        boolean enableExpertMode = normalized.isExpertMode();
        boolean enableFileUpload = normalized.isFileUploadEnabled();
        String uploadedFileUrl = normalized.getFileUploadUrl();
        String mode = normalized.getDeepseekMode();

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

            task.sendLog("正在连接豆包...");
            session = browserPool.acquirePersistent(userId, "doubao", false);

            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager.hasLoginState("doubao", userId)) {
                try {
                    com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "doubao", userId);
                } catch (Exception e) {
                    log.warn("[Doubao咨询] 恢复登录状态异常: {}", e.getMessage());
                }
            }

            Page page = session.getOrCreatePage();

            if (dbChatId != null && !dbChatId.isEmpty()) {
                task.sendLog("正在恢复豆包会话: " + dbChatId);
                if (!doubaoUtil.navigateToChat(page, dbChatId)) {
                    task.sendError("导航到豆包会话失败: " + dbChatId);
                    return;
                }
                task.sendLog("正在检查登录状态...");
                if ("false".equals(doubaoUtil.checkLoginStatus(page, false))) {
                    task.sendError("未登录，请先在登录管理器中完成豆包登录");
                    return;
                }
            } else {
                task.sendLog("正在检查登录状态...");
                if ("false".equals(doubaoUtil.checkLoginStatus(page, true))) {
                    task.sendError("未登录，请先在登录管理器中完成豆包登录");
                    return;
                }
            }

            if (enableFastMode || enableExpertMode) {
                task.sendLog("正在切换豆包模式...");
                doubaoUtil.applyConversationMode(page, enableFastMode, enableExpertMode);
                if (dbChatId != null && !dbChatId.isEmpty()) {
                    task.sendLog("正在恢复豆包会话: " + dbChatId);
                    if (!doubaoUtil.navigateToChat(page, dbChatId)) {
                        task.sendError("模式切换后恢复豆包会话失败: " + dbChatId);
                        return;
                    }
                }
            }

            if (enableFileUpload && uploadedFileUrl != null && !uploadedFileUrl.isEmpty()) {
                task.sendLog("检测到文件，正在上传到豆包...");
                FileDownloadUtil.UploadResult fileResult = fileDownloadUtil.downloadAndUploadWithFallback(
                    uploadedFileUrl,
                    page,
                    (p, localFilePath) -> doubaoUtil.uploadFile(p, localFilePath),
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
                if (!fileResult.isSuccess()) {
                    task.sendLog("文件处理失败(" + fileResult.getErrorMessage() + ")，将继续发送文本消息");
                    log.warn("[Doubao咨询] 文件上传失败: {}", fileResult.getErrorMessage());
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
                    String logMessage = "豆包正在生成回复（已等待 " + sec + " 秒）...";
                    task.sendLog(logMessage);
                    String shot = captureAndUpload(page, userId, "doubao_progress_" + count);
                    if (shot != null) {
                        task.sendScreenshot(shot);
                    }
                    return logMessage;
                } catch (Exception e) {
                    log.warn("[Doubao咨询] 进度更新失败", e);
                    return "豆包处理中...";
                }
            });

            String answer = doubaoUtil.sendMessageAndWaitResponse(page, query, enableDeepThinking);
            task.stop();

            page.waitForTimeout(1500);
            task.sendLog("正在提取会话信息...");
            String newChatId = doubaoUtil.extractChatId(page);
            String shareUrl = newChatId != null ? "https://www.doubao.com/chat/" + newChatId : null;

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("query", query);
            resultData.put("chatId", newChatId);
            resultData.put("shareUrl", shareUrl);
            resultData.put("mode", mode);
            resultData.put("elapsedTime", (int) ((System.currentTimeMillis() - startTime) / 1000));
            resultData.put("textContent", answer != null ? answer : "");
            resultData.put("answer", answer != null ? answer : "豆包回复完成，但获取内容失败");
            resultData.put("hasScreenshot", false);

            task.sendSuccess("豆包回复完成", resultData);
            log.info("[Doubao咨询] 完成 - 会话: {}, 耗时: {}s", sessionId, resultData.get("elapsedTime"));
        } catch (Exception e) {
            log.error("[Doubao咨询] 失败", e);
            task.sendError("咨询失败: " + e.getMessage());
        } finally {
            task.stop();
            if (session != null) {
                try {
                    browserPool.destroy(session);
                } catch (Exception e) {
                    log.warn("[Doubao咨询] 销毁会话失败: {}", e.getMessage());
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
        return aiType != null ? aiType.toString() : "doubao";
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
            log.debug("[Doubao截图] 失败: {}", e.getMessage());
            return null;
        }
    }
}
