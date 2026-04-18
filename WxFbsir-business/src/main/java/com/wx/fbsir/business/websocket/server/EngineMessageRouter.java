package com.wx.fbsir.business.websocket.server;

import com.alibaba.fastjson2.JSON;
import com.wx.fbsir.business.websocket.message.EngineMessage;
import com.wx.fbsir.business.websocket.message.MessageType;
import com.wx.fbsir.business.aigc.service.IAigcService;
import com.wx.fbsir.business.aigc.manager.AiSessionStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine 消息路由器（主节点）
 * 
 * 职责：
 *   1. 处理 Engine 发来的系统消息（注册、心跳等）
 *   2. 将 Engine 的业务响应转发给前端
 * 
 * 消息流向：
 *   Engine → Admin(EngineMessageRouter) → 前端(ClientMessageRouter)
 *
 * @author wxfbsir
 * @date 2025-12-15
 */
@Component
public class EngineMessageRouter {

    private static final Logger log = LoggerFactory.getLogger(EngineMessageRouter.class);

    private final Map<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final ClientMessageRouter clientMessageRouter;
    private final com.wx.fbsir.business.websocket.controller.EngineRequestController engineRequestController;
    private final IAigcService aigcService;
    private final AiSessionStateManager sessionStateManager;
    private final com.wx.fbsir.business.aigc.service.AigcBatchUpdateService batchUpdateService;

    public EngineMessageRouter(ClientMessageRouter clientMessageRouter,
                                com.wx.fbsir.business.websocket.controller.EngineRequestController engineRequestController,
                                IAigcService aigcService,
                                AiSessionStateManager sessionStateManager,
                                com.wx.fbsir.business.aigc.service.AigcBatchUpdateService batchUpdateService) {
        this.clientMessageRouter = clientMessageRouter;
        this.engineRequestController = engineRequestController;
        this.aigcService = aigcService;
        this.sessionStateManager = sessionStateManager;
        this.batchUpdateService = batchUpdateService;
    }

    @PostConstruct
    public void init() {
        log.info("[消息路由] 初始化完成 - Engine 响应将自动转发给前端");
    }

    /**
     * 注册消息处理器
     *
     * @param type    消息类型
     * @param handler 处理器
     */
    public void registerHandler(MessageType type, MessageHandler handler) {
        handlers.put(type, handler);
        log.debug("[消息路由] 注册处理器 - 类型: {}, 处理器: {}", 
            type.getCode(), handler.getClass().getSimpleName());
    }

    /**
     * 路由 Engine 消息（完整转发payload）
     * 
     * 所有 Engine 发来的业务响应都会转发给对应的前端用户
     * Admin不对payload做任何处理，完整透传
     *
     * @param session 会话对象
     * @param message 消息对象
     */
    public void route(EngineSession session, EngineMessage message) {
        if (message == null) {
            log.warn("[消息路由] 收到空消息，已忽略");
            return;
        }

        String type = message.getType();
        String userId = message.getUserId();
        
        // 🔴 关键修复：根据请求来源区分响应目标
        // 提取 requestId 和 sourceType
        String requestId = message.getPayloadValue("requestId");
        String sourceType = message.getPayloadValue("sourceType");
        
        log.debug("[Router] 收到Engine响应: {} - 类型: {}, 用户: {}, 请求ID: {}", 
            session.getEngineId(), type, userId, requestId);
        
        // 🔥 实时存储：收到Engine消息时立即保存到数据库
        processRealtimeStorage(message, session);
        
        // 检查是否是单次返回结果（_RESULT后缀）
        boolean isResultMessage = type != null && type.endsWith("_RESULT");
        if (isResultMessage && requestId != null && !requestId.isEmpty()) {
            // 根据来源类型路由
            if ("HTTP".equals(sourceType)) {
                // HTTP 请求 → 仅完成 HTTP 响应
                Map<String, Object> resultData = new HashMap<>();
                if (message.getPayload() != null) {
                    resultData.putAll(message.getPayload());
                }
                engineRequestController.completeRequest(requestId, resultData);
                log.debug("[Router] HTTP响应完成 - 请求ID: {}, 类型: {}", requestId, type);
                return; // 不转发给 WebSocket
                
            } else if ("WEBSOCKET".equals(sourceType)) {
                // WebSocket 请求 → 仅转发给 WebSocket 客户端（完整转发payload）
                if (userId != null && !userId.isEmpty()) {
                    String jsonMessage = message.toJson();
                    clientMessageRouter.routeToClient(userId, jsonMessage);
                    log.debug("[Router] WebSocket响应已转发 - 请求ID: {}, 类型: {}", requestId, type);
                    return;
                }
            } else {
                // 未知来源或旧版本消息，兼容处理（双路转发）
                log.warn("[Router] 未知来源类型: {}, 请求ID: {}, 执行兼容路由", sourceType, requestId);
                
                // 尝试完成 HTTP 请求
                Map<String, Object> resultData = new HashMap<>();
                if (message.getPayload() != null) {
                    resultData.putAll(message.getPayload());
                }
                engineRequestController.completeRequest(requestId, resultData);
                
                // 尝试转发给 WebSocket
                if (userId != null && !userId.isEmpty()) {
                    String jsonMessage = message.toJson();
                    clientMessageRouter.routeToClient(userId, jsonMessage);
                }
                return;
            }
        }
        
        // 非 _RESULT 消息（进度消息等），正常转发给客户端
        if (userId != null && !userId.isEmpty()) {
            String jsonMessage = message.toJson();
            clientMessageRouter.routeToClient(userId, jsonMessage);
            log.debug("[Router] 转发进度消息: {} - 用户: {}", type, userId);
            return;
        }
        
        // 检查是否有注册的 Handler
        MessageType messageType = message.getMessageType();
        MessageHandler handler = handlers.get(messageType);
        if (handler != null) {
            try {
                handler.handle(session, message);
            } catch (Exception e) {
                log.error("[消息路由] 处理异常 - 类型: {}, 错误: {}", type, e.getMessage());
            }
            return;
        }
        
        log.warn("[消息路由] 未知消息类型且无 userId - 类型: {}, EngineID: {}", 
            type, session.getEngineId());
    }

    /**
     * 直接转发原始消息到前端
     * 
     * 用于 Engine 直接发送的响应消息
     */
    public void forwardToClient(String userId, String rawMessage) {
        if (userId != null && !userId.isEmpty()) {
            clientMessageRouter.routeToClient(userId, rawMessage);
        }
    }

    // ==========================================================================
    // 🤖 AIGC消息处理模块
    // 说明：所有AI相关消息的存储和处理逻辑，与通用WebSocket消息完全隔离
    // 支持消息类型：AI_TASK_LOG、AI_TASK_SCREENSHOT、AI_TASK_RESULT、AI_TASK_ERROR
    // ==========================================================================
    
    /**
     * 🤖 AIGC实时存储处理入口
     * 
     * 处理AI咨询业务的所有消息，收到Engine消息时立即保存到数据库
     * 仅支持AI_TASK_*格式消息
     */
    private void processRealtimeStorage(EngineMessage message, EngineSession session) {
        try {
            String type = message.getType();
            
            // 🎯 跳过登录类消息
            if (type != null && (type.contains("LOGIN") || type.contains("CHECK"))) {
                log.debug("[AIGC存储] 跳过登录类消息 - 类型: {}", type);
                return;
            }
            
            // 🤖 只处理AIGC消息（AI_TASK_*格式）
            if (!MessageType.isAiTaskResponse(type)) {
                return; // 非AI任务消息，跳过存储
            }
            
            String userId = message.getUserId();
            if (userId == null || userId.isEmpty()) {
                log.warn("[实时存储] AI消息缺少用户ID - 类型: {}", type);
                return;
            }
            
            Map<String, Object> payload = message.getPayload();
            if (payload == null) {
                log.warn("[实时存储] AI消息缺少payload - 类型: {}", type);
                return;
            }
            
            // 提取关键信息
            String sessionId = message.getPayloadValue("sessionId");
            String aiType = message.getPayloadValue("aiType");
            String userPrompt = message.getPayloadValue("userPrompt");
            // 🔥 优先从顶层获取chatId，如果为空则从payload获取，最后从缓存获取
            String chatId = message.getChatId();
            if (chatId == null || chatId.isEmpty()) {
                chatId = message.getPayloadValue("chatId");
            }
            // 🔥 如果仍然为空，尝试从缓存中获取（Engine返回的消息不带chatId）
            if ((chatId == null || chatId.isEmpty()) && sessionId != null) {
                chatId = ClientMessageRouter.getCachedChatId(sessionId);
                if (chatId != null) {
                    log.debug("[实时存储] 从缓存获取chatId: {} -> {}", sessionId, chatId);
                }
            }
            
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = message.getPayloadValue("requestId"); // 兼容旧版本
            }
            
            if (sessionId == null || sessionId.isEmpty()) {
                log.warn("[实时存储] AI消息缺少sessionId - 类型: {}", type);
                return;
            }
            
            log.debug("[AIGC存储] 提取参数 - sessionId: {}, chatId: {}, aiType: {}", 
                sessionId, chatId, aiType);
            
            if ("AI_TASK_RESULT".equals(type) || "AI_TASK_ERROR".equals(type)) {
                String platformChatId = extractPlatformChatIdFromPayload(payload);
                Object errCode = payload != null ? payload.get("errorCode") : null;
                Object errMsg = payload != null ? payload.get("errorMessage") : null;
                log.info("[AIGC入站] stage=inbound requestType={} sessionId={} chatId={} aiType={} platformChatId={} errorCode={} errorMessage={}",
                    type, sessionId, chatId, aiType, platformChatId, errCode, errMsg);
            }

            // 🤖 根据消息类型处理存储
            if ("AI_TASK_LOG".equals(type)) {
                // 进度日志消息 - 追加到数据库
                appendProgressLog(userId, sessionId, chatId, aiType, payload);
                log.debug("[AIGC存储] stage=appendProgress 会话: {}, AI: {}", sessionId, aiType);
                
            } else if ("AI_TASK_SCREENSHOT".equals(type)) {
                // 截图消息 - 追加到数据库
                appendScreenshot(userId, sessionId, chatId, aiType, payload);
                log.debug("[AIGC存储] stage=appendScreenshot 会话: {}, AI: {}", sessionId, aiType);
                
            } else if ("AI_TASK_RESULT".equals(type)) {
                // 🔥 AI对话结果 - 保存完整结果到聊天历史
                saveAiResult(userId, sessionId, chatId, aiType, userPrompt, payload, type);
                log.info("[AIGC存储] stage=storeResult status=success 会话: {}, chatId: {}, AI: {}", sessionId, chatId, aiType);
                
            } else if ("AI_TASK_ERROR".equals(type)) {
                // 错误消息 - 保存错误信息
                saveAiResult(userId, sessionId, chatId, aiType, userPrompt, payload, type);
                log.warn("[AIGC存储] stage=storeResult status=error 会话: {}, AI: {}", sessionId, aiType);
            }
            
        } catch (Exception e) {
            log.error("[实时存储] 处理失败 - Engine: {}, 消息类型: {}, 错误: {}", 
                session.getEngineId(), message.getType(), e.getMessage(), e);
        }
    }
    
    /**
     * 🔥 更新AI最终结果到聊天历史（先存后发架构：预保存已创建记录，此处更新）
     * 每个AI的结果都独立保存，互不影响
     */
    private void saveAiResult(String userId, String sessionId, String chatId, String aiType, 
                             String userPrompt, Map<String, Object> payload, String messageType) {
        try {
            log.info("[AI存储] 开始更新 - 用户: {}, 会话: {}, AI类型: {}", userId, sessionId, aiType);
            log.debug("[AI存储] 原始payload: {}", JSON.toJSONString(payload));
            
            // 🔥 先取历史记录，便于错误场景回填 userPrompt（Engine 的 AI_TASK_ERROR 常不带 data.query）
            Map<String, Object> existingChat = aigcService.getChatBySessionId(sessionId);
            
            // 🔥 从 payload.data 提取（避免非 Map 类型导致 ClassCastException）
            Map<String, Object> dataMap = safeGetDataMap(payload);
            
            // 🔥 用户提问文本：优先使用 Engine 在 payload 顶层回传的 userPrompt（与 data.query 同源，但避免嵌套 Map 反序列化差异导致乱码）
            // 仅当顶层为空时再回退到 payload.data.query，再回退历史记录
            String actualUserPrompt = userPrompt;
            String platformChatId = null;
            
            if (dataMap != null) {
                String queryFromData = getStringValue(dataMap, "query");
                if ((actualUserPrompt == null || actualUserPrompt.isEmpty())
                        && queryFromData != null && !queryFromData.isEmpty()) {
                    actualUserPrompt = queryFromData;
                    log.debug("[AI存储] 从payload.data.query回填 userPrompt: {}", actualUserPrompt);
                }
                
                // 从 dataMap 中提取平台侧 chatId（用于对应AI上下文复用）
                platformChatId = getStringValue(dataMap, "chatId");
                log.debug("[AI存储] 从payload.data.chatId获取平台会话ID: {}", platformChatId);
            }
            
            if ((actualUserPrompt == null || actualUserPrompt.isEmpty()) && existingChat != null) {
                Object prev = existingChat.get("userPrompt");
                if (prev != null && !prev.toString().isEmpty()) {
                    actualUserPrompt = prev.toString();
                    log.debug("[AI存储] 从历史记录回填 userPrompt");
                }
            }
            
            // 🔥 AI_TASK_ERROR：将 errorMessage 合入 results（含 data==null、data=={}、data 无 answer 等）
            Map<String, Object> effectiveDataMap = dataMap != null ? new HashMap<>(dataMap) : null;
            if ("AI_TASK_ERROR".equals(messageType)) {
                String err = getStringValue(payload, "errorMessage");
                if (effectiveDataMap == null) {
                    effectiveDataMap = new HashMap<>();
                }
                if (err != null && !err.isEmpty()) {
                    String prevAns = getStringValue(effectiveDataMap, "answer");
                    if (prevAns == null || prevAns.isEmpty()) {
                        effectiveDataMap.put("answer", err);
                        effectiveDataMap.put("textContent", err);
                    }
                }
                effectiveDataMap.put("success", false);
            }
            
            // 🔥 使用前端传递的chatId作为会话分组ID（从缓存获取）
            String actualChatId = chatId;
            if (actualChatId == null || actualChatId.isEmpty()) {
                actualChatId = ClientMessageRouter.getCachedChatId(sessionId);
            }
            if (actualChatId == null || actualChatId.isEmpty()) {
                actualChatId = sessionId; // 最后兜底
                log.warn("[AI存储] 前端未传递chatId，使用sessionId: {}", actualChatId);
            }
            
            log.info("[AI存储] 提取结果 - userPrompt: {}, chatId: {}", actualUserPrompt, actualChatId);
            
            // 🔥 读取现有聊天记录，合并progressLogs和screenshots（避免覆盖）
            Map<String, Object> mergedData = new HashMap<>();
            List<Map<String, Object>> existingResults = new ArrayList<>();
            
            if (existingChat != null) {
                // 解析现有data，保留progressLogs和screenshots
                String existingDataStr = (String) existingChat.get("data");
                if (existingDataStr != null && !existingDataStr.isEmpty()) {
                    Map<String, Object> existingDataMap = JSON.parseObject(existingDataStr);
                    if (existingDataMap.get("progressLogs") != null) {
                        mergedData.put("progressLogs", existingDataMap.get("progressLogs"));
                    }
                    if (existingDataMap.get("screenshots") != null) {
                        mergedData.put("screenshots", existingDataMap.get("screenshots"));
                    }
                    existingResults = extractStoredResults(existingDataMap);
                }
            }
            
            // 合并payload到mergedData（payload中的字段优先级更高）
            mergedData.putAll(payload);
            mergedData.put("results", mergeAiResults(existingResults, aiType, effectiveDataMap));
            
            Map<String, Object> chatData = new HashMap<>();
            chatData.put("id", sessionId);
            chatData.put("userId", userId);
            chatData.put("userPrompt", actualUserPrompt);
            
            // 存储合并后的完整数据（包含progressLogs + payload结果）
            chatData.put("data", JSON.toJSONString(mergedData));
            
            // 设置内部chatId（用于会话分组）
            chatData.put("chatId", actualChatId);
            
            // 🔥 根据AI类型设置对应的AI会话ID字段（用于上下文复用）
            setAiChatIdField(chatData, aiType, platformChatId);
            
            // 🔥🔥🔥 改为更新记录（因为预保存已创建，避免主键冲突）
            // existingChat已在上面获取，直接使用
            if (existingChat != null) {
                aigcService.updateChatData(chatData);
                log.info("[AI存储] ✅ {}结果已更新到历史表 - 会话: {}, chatId: {}, userPrompt: {}", aiType, sessionId, actualChatId, actualUserPrompt);
            } else {
                aigcService.saveChatData(chatData);
                log.info("[AI存储] ✅ {}结果已新增到历史表 - 会话: {}, chatId: {}, userPrompt: {}", aiType, sessionId, actualChatId, actualUserPrompt);
            }
            
            // 🔥 同步保存到AI记录扩展表（存储分享链接和截图）
            saveToExtensionTable(userId, sessionId, actualUserPrompt, aiType, effectiveDataMap);
            
            // 🎯 标记AI任务完成，检查整轮对话是否结束
            boolean allCompleted = sessionStateManager.markAiCompleted(sessionId, aiType);
            if (allCompleted) {
                log.info("[AI业务] 🎉 整轮对话完成 - 会话: {}, 所有AI任务已完成", sessionId);
            }
            
        } catch (Exception e) {
            log.error("[AI存储] stage=storeResult errorCategory=DB_SAVE_FAILED 保存{}结果失败 - 会话: {}, 错误: {}", aiType, sessionId, e.getMessage());
            // 即使保存失败也标记为完成，避免阻塞其他任务
            sessionStateManager.markAiFailed(sessionId, aiType, "数据库保存失败: " + e.getMessage());
        }
    }
    
    /**
     * 🔥 追加进度日志到批量更新队列（性能优化：批量攒批）
     * 
     * 【优化前】每条日志触发一次数据库UPDATE
     * 【优化后】日志攒批到队列，每500ms或10条统一UPDATE
     * 【收益】减少90%数据库压力，降低行锁竞争
     */
    private void appendProgressLog(String userId, String sessionId, String chatId, String aiType, Map<String, Object> payload) {
        try {
            String logMessage = getStringValue(payload, "message");
            if (logMessage == null || logMessage.isEmpty()) {
                return;
            }
            
            Long timestamp = payload.get("timestamp") != null ? 
                ((Number) payload.get("timestamp")).longValue() : System.currentTimeMillis();
            
            // 🔥 加入批量更新队列（不再直接操作数据库）
            batchUpdateService.addProgressLog(userId, sessionId, chatId, aiType, logMessage, timestamp);
            
            log.debug("[进度日志] 已加入队列 - 会话: {}, AI: {}, 消息: {}", sessionId, aiType, logMessage);
            
        } catch (Exception e) {
            log.error("[进度日志] 加入队列失败 - 会话: {}, 错误: {}", sessionId, e.getMessage(), e);
            // ⚠️ 批量服务会自动重试，此处仅记录错误
        }
    }
    
    /**
     * 🔥 追加截图到批量更新队列（性能优化：批量攒批）
     * 
     * 【优化前】每张截图触发一次数据库UPDATE
     * 【优化后】截图攒批到队列，每500ms或10条统一UPDATE
     * 【收益】减少90%数据库压力，降低行锁竞争
     */
    private void appendScreenshot(String userId, String sessionId, String chatId, String aiType, Map<String, Object> payload) {
        try {
            String screenshotUrl = getStringValue(payload, "screenshotUrl");
            if (screenshotUrl == null || screenshotUrl.isEmpty()) {
                return;
            }
            
            // 🔥 加入批量更新队列（不再直接操作数据库）
            batchUpdateService.addScreenshot(userId, sessionId, chatId, aiType, screenshotUrl);
            
            log.debug("[截图] 已加入队列 - 会话: {}, AI: {}, URL: {}", sessionId, aiType, screenshotUrl);
            
        } catch (Exception e) {
            log.error("[截图] 加入队列失败 - 会话: {}, 错误: {}", sessionId, e.getMessage(), e);
            // ⚠️ 批量服务会自动重试，此处仅记录错误
        }
    }
    
    /**
     * 安全获取字符串值
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 安全获取 Engine 下发的 payload.data，避免强转非 Map 类型导致 ClassCastException。
     */
    private Map<String, Object> safeGetDataMap(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object raw = payload.get("data");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        log.warn("[AI存储] payload.data 非 Map，已忽略 - type={}", raw.getClass().getName());
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractStoredResults(Map<String, Object> existingDataMap) {
        Object resultsObj = existingDataMap.get("results");
        if (resultsObj instanceof List) {
            return new ArrayList<>((List<Map<String, Object>>) resultsObj);
        }

        Object nestedObj = existingDataMap.get("data");
        if (nestedObj instanceof Map) {
            Object nestedResults = ((Map<String, Object>) nestedObj).get("results");
            if (nestedResults instanceof List) {
                return new ArrayList<>((List<Map<String, Object>>) nestedResults);
            }
        }

        return new ArrayList<>();
    }

    private List<Map<String, Object>> mergeAiResults(List<Map<String, Object>> existingResults,
                                                     String aiType,
                                                     Map<String, Object> dataMap) {
        List<Map<String, Object>> mergedResults =
            existingResults != null ? new ArrayList<>(existingResults) : new ArrayList<>();

        if (dataMap == null) {
            return mergedResults;
        }
        String mergeAiType = aiType;
        if (mergeAiType == null || mergeAiType.isEmpty()) {
            mergeAiType = "unknown";
            log.warn("[AI存储] mergeAiResults: aiType 为空，使用 unknown");
        }

        Map<String, Object> currentResult = new HashMap<>();
        currentResult.put("aiType", mergeAiType);
        currentResult.put("answer", getStringValue(dataMap, "answer"));
        currentResult.put("textContent", getStringValue(dataMap, "textContent"));
        currentResult.put("conversationScreenshot", getStringValue(dataMap, "conversationScreenshot"));
        currentResult.put("hasScreenshot", dataMap.get("hasScreenshot"));
        currentResult.put("shareUrl", getStringValue(dataMap, "shareUrl"));
        currentResult.put("chatId", getStringValue(dataMap, "chatId"));
        currentResult.put("query", getStringValue(dataMap, "query"));
        currentResult.put("mode", getStringValue(dataMap, "mode"));
        currentResult.put("elapsedTime", dataMap.get("elapsedTime"));

        boolean updated = false;
        for (int i = 0; i < mergedResults.size(); i++) {
            Map<String, Object> existing = mergedResults.get(i);
            String existingAiType = getStringValue(existing, "aiType");
            if (mergeAiType.equalsIgnoreCase(existingAiType)) {
                mergedResults.set(i, currentResult);
                updated = true;
                break;
            }
        }

        if (!updated) {
            mergedResults.add(currentResult);
        }

        return mergedResults;
    }

    /**
     * 🔥 保存到AI记录扩展表（原草稿表）
     * 存储AI生成的分享链接和截图等扩展信息
     * 
     * 【数据存储策略 - 与Engine端配合】
     * Engine端发送结构：
     * - data.answer: 优先存储截图URL，无截图时存储文本内容（二选一）
     * - data.conversationScreenshot: 截图URL
     * - data只是简单显示，完整信息存储在wc_playwright_draft表
     * 
     * Business端存储到wc_playwright_draft表：
     * - draft_content字段：存储文本内容（如果answer是截图URL则提示查看分享链接）
     * - share_img_url字段：存储截图URL（conversationScreenshot）
     */
    private void saveToExtensionTable(String userId, String sessionId, String userPrompt, 
                                     String aiType, Map<String, Object> dataMap) {
        try {
            if (dataMap == null) {
                return;
            }
            
            // 提取分享链接、截图、文本内容
            String shareUrl = getStringValue(dataMap, "shareUrl");
            String conversationScreenshot = getStringValue(dataMap, "conversationScreenshot");
            String textContent = getStringValue(dataMap, "textContent");  // 🔥 Engine端单独传递的真实文本内容
            
            // 🔥 如果textContent为空，回退到answer（兼容旧数据）
            boolean hasTextContent = textContent != null && !textContent.isEmpty();
            if (!hasTextContent) {
                String answer = getStringValue(dataMap, "answer");
                boolean answerIsUrl = answer != null && (answer.startsWith("http://") || answer.startsWith("https://"));
                textContent = answerIsUrl ? "" : (answer != null ? answer : "");
                hasTextContent = textContent != null && !textContent.isEmpty();
            }
            
            // 判断是否有截图
            boolean hasScreenshot = conversationScreenshot != null && !conversationScreenshot.isEmpty();
            
            // 只有当有内容、分享链接或截图时才保存到扩展表
            if ((shareUrl != null && !shareUrl.isEmpty()) || 
                (conversationScreenshot != null && !conversationScreenshot.isEmpty()) ||
                hasTextContent) {
                
                Map<String, Object> extensionData = new HashMap<>();
                extensionData.put("id", generateUUID());
                extensionData.put("taskId", sessionId); // 关联聊天历史记录ID
                extensionData.put("userPrompt", userPrompt);
                extensionData.put("draftContent", textContent); // 🔥 存储真实文本内容
                extensionData.put("aiName", aiType);
                extensionData.put("userName", userId);
                extensionData.put("shareUrl", shareUrl);
                extensionData.put("shareImgUrl", conversationScreenshot); // 🔥 存储截图URL
                
                aigcService.saveExtensionData(extensionData);
                log.info("[AI扩展表] ✅ 已保存 - 会话: {}, AI: {}, 截图: {}, 文本长度: {}", 
                    sessionId, aiType, hasScreenshot ? "有" : "无", textContent != null ? textContent.length() : 0);
            }
            
        } catch (Exception e) {
            log.error("[AI扩展表] 保存失败 - 会话: {}, 错误: {}", sessionId, e.getMessage());
        }
    }
    
    /**
     * 生成UUID
     */
    private String generateUUID() {
        return java.util.UUID.randomUUID().toString();
    }
    
    /**
     * 🔥 根据AI类型设置对应的会话ID字段（完全参考旧项目cube-admin）
     * 支持所有AI类型的会话ID保存，用于上下文复用
     */
    private void setAiChatIdField(Map<String, Object> chatData, String aiType, String aiChatId) {
        if (aiChatId == null || aiChatId.isEmpty()) {
            return;
        }
        
        String aiTypeLower = aiType != null ? aiType.toLowerCase() : "";
        
        switch (aiTypeLower) {
            case "deepseek":
                chatData.put("deepseekChatId", aiChatId);
                break;
            case "gitee":
                chatData.put("giteeChatId", aiChatId);
                break;
            case "yuanbao":
            case "元宝":
            case "腾讯元宝":
            case "yb":
                chatData.put("ybChatId", aiChatId);
                break;
            case "doubao":
            case "豆包":
            case "db":
                chatData.put("dbChatId", aiChatId);
                break;
            case "tongyi":
            case "qianwen":
            case "通义":
            case "千问":
            case "通义千问":
            case "tone":
                chatData.put("toneChatId", aiChatId);
                break;
            case "ty":
                chatData.put("tyChatId", aiChatId);
                break;
            case "kimi":
                chatData.put("kimiChatId", aiChatId);
                break;
            case "baidu":
            case "百度":
            case "百度ai":
            case "wenxin":
            case "文心":
            case "文心一言":
                chatData.put("baiduChatId", aiChatId);
                break;
            case "metaso":
            case "秘塔":
            case "秘塔ai":
            case "mita":
            case "秘塔搜索":
                chatData.put("metasoChatId", aiChatId);
                break;
            case "minimax":
            case "max":
                chatData.put("maxChatId", aiChatId);
                break;
            case "zhzd":
            case "知乎":
            case "知乎直答":
                chatData.put("zhzdChatId", aiChatId);
                break;
            default:
                log.debug("[AI存储] 未知AI类型: {}, 会话ID: {}", aiType, aiChatId);
                break;
        }
    }

    /**
     * 从 Engine 回包 payload 中提取厂商侧会话 ID（payload.data.chatId，与前端分组 chatId 区分）
     */
    private String extractPlatformChatIdFromPayload(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object data = payload.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object cid = dataMap.get("chatId");
            return cid != null ? String.valueOf(cid) : null;
        }
        return null;
    }

    /**
     * 消息处理器接口
     */
    public interface MessageHandler {
        void handle(EngineSession session, EngineMessage message);
    }
}
