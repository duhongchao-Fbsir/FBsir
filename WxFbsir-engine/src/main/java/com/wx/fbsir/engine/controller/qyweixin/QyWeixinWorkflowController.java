package com.wx.fbsir.engine.controller.qyweixin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import com.microsoft.playwright.options.BoundingBox;
import com.wx.fbsir.engine.capability.annotation.OnceCapability;
import com.wx.fbsir.engine.capability.base.StreamTaskHelper;
import com.wx.fbsir.engine.controller.qyweixin.mapping.QyWeixinWorkflowEditorNodeMapping;
import com.wx.fbsir.engine.playwright.util.PlaywrightWorkflowEditorDiagnostics;
import com.wx.fbsir.engine.playwright.util.QyWeixinAutomationRunRecorder;
import com.wx.fbsir.engine.controller.qyweixin.support.QyWeixinSetVariableDomProbe;
import com.wx.fbsir.engine.controller.qyweixin.learning.QyWeixinWorkflowCloneLearning;
import com.wx.fbsir.engine.controller.qyweixin.support.QyWeixinWorkflowFullPanelSampler;
import com.wx.fbsir.engine.controller.qyweixin.verify.QyWeixinWorkflowExportComparator;
import com.wx.fbsir.engine.controller.qyweixin.support.QyWeixinValidationHintHandlers;
import com.wx.fbsir.engine.playwright.pool.BrowserPoolManager;
import com.wx.fbsir.engine.playwright.session.BrowserSession;
import com.wx.fbsir.engine.playwright.util.HumanBehaviorUtil;
import com.wx.fbsir.engine.utils.qyweixin.QyWeixinLoginUtil;
import com.wx.fbsir.engine.websocket.message.EngineMessage;
import com.wx.fbsir.engine.websocket.message.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Controller;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 企业微信智能机器人 - 工作流管理控制器
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 功能概述
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 对企业微信 AI 助手工作流页面（#/aiHelper/list?tab=workflow）进行完整的
 * CRUD 操作，全程使用 {@link HumanBehaviorUtil} 自然人浏览模式规避自动化检测。
 *
 * | 消息类型                          | 功能                                    |
 * |----------------------------------|----------------------------------------|
 * | QYWEIXIN_WORKFLOW_LIST           | 获取工作流列表（含名称/状态/创建人/时间） |
 * | QYWEIXIN_WORKFLOW_OPEN_EDITOR    | 点击指定工作流的"编辑"按钮，进入编辑器  |
 * | QYWEIXIN_WORKFLOW_CREATE         | 点击"添加工作流"新建工作流              |
 * | QYWEIXIN_WORKFLOW_IMPORT_DRAFT   | 新建向导录入草稿；默认临时测试并在列表侧尝试删除，防占额度 |
 * | QYWEIXIN_WORKFLOW_VALIDATE       | 工作流编辑器校验（检查/调试前置）         |
 * | QYWEIXIN_WORKFLOW_EDITOR_PROBE   | 开发诊断：视口/文本命中/菜单采样与截图    |
 * | QYWEIXIN_WORKFLOW_EXPORT_FULL    | 逐节点打开配置面板，完整采样（画布+表单字段） |
 * | QYWEIXIN_WORKFLOW_PUBLISH        | 在编辑器内点击「发布」并尽量确认          |
 * | QYWEIXIN_WORKFLOW_CLONE_PUBLISH  | 导出→新建→录入→回放→二次导出比对(cloneVerify)→保存→发布  |
 * | QYWEIXIN_WORKFLOW_LEARNING_FEEDBACK | 写入克隆纠偏样本，强化菜单/面板记忆     |
 * | QYWEIXIN_WORKFLOW_DELETE         | 删除指定工作流（索引 or 名称定位）       |
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 无列表「复制工作流」时：解构 → 重建（迁移）管线
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 企微工作流列表不提供「一键复制」，迁移只能：先从源工作流读出可见要素（画布节点文案、截图、
 * HTML/DOM），再人工或规则补全为 {@code workflowDraft}，最后对新工作流执行
 * {@code QYWEIXIN_WORKFLOW_CREATE} + {@code QYWEIXIN_WORKFLOW_IMPORT_DRAFT}，
 * 并用 {@code QYWEIXIN_WORKFLOW_VALIDATE} / {@code QYWEIXIN_WORKFLOW_EDITOR_PROBE} 扫尾。
 * <p>
 * {@code QYWEIXIN_WORKFLOW_OPEN_EDITOR} 的返回中附带 {@code sourceWorkflowTitle}、{@code canvasElements}
 *（逐节点要素摘要）与 {@code heuristicWorkflowDraft}（仅根据节点文案启发式拼的草稿，需校对）；
 * 深度配置（变量表、HTTP URL、提示词全文）通常需打开各节点面板或结合 Skill/FBS-IR 侧迁移。
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 页面 DOM 关键选择器（来自探索结果 2026-04-22）
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * - 工作流行：      tbody tr
 * - 添加工作流按钮：button:has-text('添加工作流') 或 [class*='create_btn_by_query']
 * - 创建按钮：      [class*='create_btn']
 * - 编辑按钮：      tr 内 button:has-text('编辑') 或 a:has-text('编辑')
 * - 工作流 Tab：    侧边栏文本"工作流"
 *
 * @author wxfbsir
 */
@Controller
public class QyWeixinWorkflowController extends StreamTaskHelper {

    private static final String QYWEIXIN_FIXED_INSTANCE = "fixed-main";
    private static final String WORKFLOW_URL =
            "https://work.weixin.qq.com/wework_admin/frame#/aiHelper/list?tab=workflow";
    private static final String AIHELPER_URL =
            "https://work.weixin.qq.com/wework_admin/frame#/aiHelper/list?from=manage_tools";

    /** 工作流列表行选择器（探索结果确认） */
    private static final String WORKFLOW_ROW_SELECTOR = "tbody tr";

    /**
     * 编辑器就绪等待选择器（来自真实探索结果 2026-04-22）
     * 编辑器 URL: /oamng/workflow_editor_client
     * 顶层: div#root > div.w-full.h-screen.flex > main.flex-1.relative.h-full
     */
    private static final String EDITOR_READY_SELECTOR =
            // 已知真实结构
            "div#root, main.flex-1, " +
            // 通用工作流编辑器选择器（兜底）
            "[class*='workflow-editor'], [class*='flowEditor'], " +
            "[class*='workflow_editor'], [class*='flow-canvas'], " +
            "[class*='node-panel'], [class*='nodePanel'], .wd-flow-wrap, " +
            "[class*='editor-wrap'], [class*='editorWrap']";

    private static final ConcurrentHashMap<String, ReentrantLock> USER_SERIAL_LOCKS =
            new ConcurrentHashMap<>();

    @Autowired
    private QyWeixinLoginUtil loginUtil;

    @Autowired
    private BrowserPoolManager browserPool;

    @Autowired
    private HumanBehaviorUtil human;

    @Autowired
    @Lazy
    private com.wx.fbsir.engine.websocket.client.WebSocketClientManager webSocketClientManager;

    @Autowired
    private com.wx.fbsir.engine.playwright.util.ScreenshotUploadClient uploadClient;

    @Autowired
    private QyWeixinWorkflowEditorNodeMapping workflowEditorNodeMapping;

    @Autowired
    private QyWeixinAutomationRunRecorder qyweixinAutomationRunRecorder;

    @Autowired(required = false)
    private QyWeixinValidationHintHandlers validationHintHandlers;

    @Autowired
    private QyWeixinWorkflowCloneLearning workflowCloneLearning;

    // =========================================================================
    // QYWEIXIN_WORKFLOW_LIST：获取工作流列表（结构化数据）
    // =========================================================================

    /**
     * 获取企业微信智能机器人工作流列表（结构化）
     *
     * 请求示例：
     * {"type": "QYWEIXIN_WORKFLOW_LIST", "engineId": "engine-001", "userId": "1"}
     *
     * 返回数据：
     * - workflows:  [{name, description, creator, status, updateTime, index, rowText}]
     * - count:      工作流总数
     * - screenshotUrl, currentUrl
     */
    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_LIST",
        description = "获取企业微信智能机器人工作流列表（自然人浏览模式）",
        timeout = 90000L
    )
    public void handleWorkflowList(EngineMessage message) {
        String userId    = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");

        log.info("[企业微信工作流列表] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            SessionPage sp = acquireSessionPageWithRecovery(userId);
            session = sp.session();
            Page page = sp.page();
            session.touch();

            // 导航到工作流页
            navigateToWorkflowTab(page, userId);

            // 登录检查
            String loginStatus = loginUtil.checkLoginStatus(page, false);
            if ("false".equals(loginStatus)) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }

            // ★ 滚动浏览列表（模拟人眼扫描）
            human.naturalScroll(page, 200, 800);
            human.think(page, 400, 800);

            // 提取工作流列表
            List<Map<String, Object>> workflows = extractStructuredWorkflowList(page);

            // 如果为空，等待后重试
            if (workflows.isEmpty()) {
                log.info("[企业微信工作流列表] 首次为空，等待后重试...");
                human.think(page, 1500, 2500);
                human.naturalScroll(page, 100, 500);
                workflows = extractStructuredWorkflowList(page);
            }

            String screenshotUrl = captureAndUpload(page, userId,
                    "qyweixin_wf_list_" + System.currentTimeMillis());

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("workflows", workflows);
            resultData.put("count", workflows.size());
            resultData.put("screenshotUrl", screenshotUrl);
            resultData.put("currentUrl", page.url());
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(message, resultData);
            log.info("[企业微信工作流列表] 完成 - 用户: {}, 数量: {}", userId, workflows.size());

        } catch (Exception e) {
            log.error("[企业微信工作流列表] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "获取工作流列表失败: " + e.getMessage());
        } finally {
            releaseSession(session, "工作流列表");
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_OPEN_EDITOR：点击指定工作流的"编辑"按钮
    // =========================================================================

    /**
     * 打开指定工作流的编辑器
     *
     * 请求示例：
     * {
     *   "type": "QYWEIXIN_WORKFLOW_OPEN_EDITOR",
     *   "engineId": "engine-001",
     *   "userId": "1",
     *   "workflowIndex": 0,          // 按行索引（0起），优先
     *   "workflowName": "正常问答",   // 按首列标题（见 resolveWorkflowIndex）
     *   "workflowCreator": "曹敏昊"  // 可选：创建人列，同名消歧
     * }
     *
     * 返回数据：
     * - editorUrl:     编辑器 URL
     * - editorReady:   编辑器是否就绪
     * - screenshotUrl: 编辑器截图
     * - editorHtml:    编辑器区域 HTML 片段（前 6000 字，供后续分析）
     * - editorDom:     编辑器 DOM 结构摘要
     * - nodeList:      节点列表（画布 DOM 采样，与 canvasElements 同源）
     * - sourceWorkflowTitle: 列表行解析的源工作流标题
     * - canvasElements:     逐节点要素摘要（index、text、guessKind、guessMenuHint）
     * - heuristicWorkflowDraft: 启发式 workflowDraft（payload.migrationDraftTitle 或默认「副本-源标题」），需人工校对后再 IMPORT_DRAFT
     */
    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_OPEN_EDITOR",
        description = "打开指定企业微信工作流编辑器（自然人浏览模式）",
        timeout = 120000L
    )
    public void handleOpenEditor(EngineMessage message) {
        String userId        = message.getUserId();
        String requestId     = extractRoutingField(message, "requestId");
        String indexStr      = extractPayload(message, "workflowIndex");
        String workflowName   = extractPayload(message, "workflowName");
        String workflowCreator = extractPayload(message, "workflowCreator");
        String migrationDraftTitle = extractPayload(message, "migrationDraftTitle");

        int targetIndex = -1;
        if (indexStr != null && !indexStr.isBlank()) {
            try { targetIndex = Integer.parseInt(indexStr.trim()); } catch (Exception ignored) {}
        }

        log.info("[企业微信工作流编辑器] 开始 - 用户: {}, 索引: {}, 名称: {}, 创建人: {}",
                userId, targetIndex, workflowName, workflowCreator);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            SessionPage sp = acquireSessionPageWithRecovery(userId);
            session = sp.session();
            Page page = sp.page();
            session.touch();

            // 导航到工作流页
            navigateToWorkflowTab(page, userId);

            // 登录检查
            String loginStatus = loginUtil.checkLoginStatus(page, false);
            if ("false".equals(loginStatus)) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }

            // 等待列表渲染
            human.think(page, 800, 1500);

            // 定位目标行
            Locator rows = page.locator(WORKFLOW_ROW_SELECTOR);
            int rowCount = rows.count();
            log.info("[企业微信工作流编辑器] 共找到 {} 行工作流", rowCount);

            if (rowCount == 0) {
                sendErrorResult(message, "工作流列表为空，请先导航到工作流 Tab");
                return;
            }

            // 确定目标行
            int resolvedIndex = resolveWorkflowIndex(rows, rowCount, targetIndex, workflowName, workflowCreator);
            if (resolvedIndex < 0) {
                sendErrorResult(message, "未找到目标工作流：index=" + targetIndex + ", name=" + workflowName
                    + ", creator=" + workflowCreator);
                return;
            }

            Locator targetRow = rows.nth(resolvedIndex);
            String sourceWorkflowTitle = parsePrimaryNameFromRow(targetRow);
            log.info("[企业微信工作流编辑器] 目标行 #{}: {}",
                    resolvedIndex, targetRow.textContent().trim().substring(0, 50));

            // ★ 自然人浏览：滚动到目标行，悬停浏览
            human.naturalScroll(page, resolvedIndex * 50, 600);
            human.think(page, 400, 800);

            // 查找"编辑"按钮（在目标行内）
            Locator editBtn = targetRow.locator("button:has-text('编辑'), a:has-text('编辑'), [class*='edit']");
            if (editBtn.count() == 0) {
                // 备用：页面全局查找该行对应的编辑按钮（按行悬停触发显示）
                com.microsoft.playwright.options.BoundingBox rowBox = targetRow.boundingBox();
                if (rowBox != null) {
                    human.moveMouse(page, rowBox.x + rowBox.width / 2, rowBox.y + rowBox.height / 2);
                }
                human.think(page, 600, 1000);
                editBtn = targetRow.locator("button:has-text('编辑'), a:has-text('编辑'), [class*='edit']");
            }

            if (editBtn.count() == 0) {
                log.warn("[企业微信工作流编辑器] 未找到'编辑'按钮，尝试直接点击行");
                human.naturalClick(page, targetRow);
            } else {
                log.info("[企业微信工作流编辑器] 点击编辑按钮...");
                human.naturalClick(page, editBtn.first());
            }

            // 等待编辑器加载（可能是新 Tab 或当前页跳转）
            human.think(page, 2000, 3500);

            // 检查是否有新 Tab 打开
            Page editorPage = null;
            var pages = page.context().pages();
            if (pages.size() > 1) {
                // 取最新打开的页面
                editorPage = pages.get(pages.size() - 1);
                log.info("[企业微信工作流编辑器] 检测到新 Tab: {}", editorPage.url());
                human.think(editorPage, 2000, 4000);
            } else {
                editorPage = page;
                log.info("[企业微信工作流编辑器] 编辑器在当前 Tab: {}", editorPage.url());
            }

            // 等待编辑器内容渲染
            boolean editorReady = waitForEditorReady(editorPage);

            // ★ 自然人浏览：在编辑器中浏览一下
            human.warmUp(editorPage, HumanBehaviorUtil.Intensity.LOW);

            // 提取编辑器 DOM
            String editorHtml    = extractEditorHtml(editorPage, 6000);
            String editorDom     = extractEditorDomSummary(editorPage);
            List<Map<String, String>> nodeList = extractEditorNodeList(editorPage);
            String screenshotUrl = captureAndUpload(editorPage, userId,
                    "qyweixin_wf_editor_" + System.currentTimeMillis());

            String draftTitle = (migrationDraftTitle != null && !migrationDraftTitle.isBlank())
                ? migrationDraftTitle.trim()
                : ("副本-" + (sourceWorkflowTitle.isEmpty() ? "workflow" : sourceWorkflowTitle));
            List<Map<String, Object>> canvasElements = buildCanvasElementsFromNodeList(nodeList);
            Map<String, Object> heuristicWorkflowDraft = buildHeuristicWorkflowDraftFromCanvas(draftTitle, canvasElements);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("editorUrl", editorPage.url());
            resultData.put("editorReady", editorReady);
            resultData.put("screenshotUrl", screenshotUrl);
            resultData.put("editorHtml", editorHtml);
            resultData.put("editorDom", editorDom);
            resultData.put("nodeList", nodeList);
            resultData.put("sourceWorkflowTitle", sourceWorkflowTitle);
            resultData.put("canvasElements", canvasElements);
            resultData.put("heuristicWorkflowDraft", heuristicWorkflowDraft);
            resultData.put("targetIndex", resolvedIndex);
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(message, resultData);
            log.info("[企业微信工作流编辑器] 完成 - 用户: {}, 编辑器就绪: {}, 节点数: {}",
                    userId, editorReady, nodeList.size());

        } catch (Exception e) {
            log.error("[企业微信工作流编辑器] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "打开工作流编辑器失败: " + e.getMessage());
        } finally {
            releaseSession(session, "工作流编辑器");
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_CREATE：点击"添加工作流"创建新工作流
    // =========================================================================

    /**
     * 创建新工作流（点击"添加工作流"按钮进入创建向导）
     *
     * 请求示例：
     * {
     *   "type": "QYWEIXIN_WORKFLOW_CREATE",
     *   "engineId": "engine-001",
     *   "userId": "1"
     * }
     *
     * 返回数据：
     * - screenshotUrl:    创建向导截图
     * - createUrl:        创建向导 URL
     * - dialogReady:      创建向导/弹窗是否就绪
     * - dialogHtml:       创建向导 HTML（前 5000 字）
     * - dialogDom:        创建向导 DOM 结构
     * - inputFields:      可填写的表单字段列表
     */
    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_CREATE",
        description = "点击'添加工作流'进入创建向导（自然人浏览模式）",
        timeout = 90000L
    )
    public void handleWorkflowCreate(EngineMessage message) {
        String userId    = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");

        log.info("[企业微信工作流创建] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            SessionPage sp = acquireSessionPageWithRecovery(userId);
            session = sp.session();
            Page page = sp.page();
            session.touch();

            // 导航到工作流页
            navigateToWorkflowTab(page, userId);

            // 登录检查
            String loginStatus = loginUtil.checkLoginStatus(page, false);
            if ("false".equals(loginStatus)) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }

            // 等待页面稳定
            human.think(page, 800, 1500);

            // ★ 查找"添加工作流"按钮（探索结果确认：文本="添加工作流"）
            Locator addBtn = page.locator(
                "button:has-text('添加工作流'), " +
                "[class*='create_btn_by_query'], " +
                "button:has-text('新建工作流'), " +
                "button:has-text('+ 工作流'), " +
                "[class*='add'][class*='workflow']"
            );

            if (addBtn.count() == 0) {
                log.warn("[企业微信工作流创建] '添加工作流'按钮未找到，尝试备用选择器...");
                // 备用：查找所有按钮里包含"添加"或"创建"的
                addBtn = page.locator("button").filter(new Locator.FilterOptions()
                    .setHasText("添加"));
            }

            if (addBtn.count() == 0) {
                String screenshotUrl = captureAndUpload(page, userId,
                        "qyweixin_wf_create_notfound_" + System.currentTimeMillis());
                Map<String, Object> failData = new HashMap<>();
                failData.put("screenshotUrl", screenshotUrl);
                failData.put("currentUrl", page.url());
                failData.put("error", "未找到'添加工作流'按钮");
                sendErrorResult(message, "未找到'添加工作流'按钮，请确认工作流 Tab 已打开");
                return;
            }

            log.info("[企业微信工作流创建] 找到'添加工作流'按钮（共 {} 个），点击第一个...", addBtn.count());

            // ★ 自然人浏览：鼠标漫游到按钮区域，再点击
            human.naturalClick(page, addBtn.first());

            // 等待弹窗或新页面
            human.think(page, 2000, 3500);

            // 检查是否是新 Tab
            Page targetPage = page;
            var pages = page.context().pages();
            if (pages.size() > 1) {
                targetPage = pages.get(pages.size() - 1);
                log.info("[企业微信工作流创建] 新 Tab: {}", targetPage.url());
                human.think(targetPage, 1500, 3000);
            }

            // 检查弹窗/对话框是否出现
            boolean dialogReady = checkDialogOrEditorReady(targetPage);
            log.info("[企业微信工作流创建] 创建向导就绪: {}", dialogReady);

            // ★ 自然人浏览：轻度预热
            human.warmUp(targetPage, HumanBehaviorUtil.Intensity.LOW);

            // 提取表单结构
            List<Map<String, String>> inputFields = extractInputFields(targetPage);
            String dialogHtml    = extractDialogHtml(targetPage, 5000);
            String dialogDom     = extractDialogDomSummary(targetPage);
            String screenshotUrl = captureAndUpload(targetPage, userId,
                    "qyweixin_wf_create_" + System.currentTimeMillis());

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("createUrl", targetPage.url());
            resultData.put("dialogReady", dialogReady);
            resultData.put("screenshotUrl", screenshotUrl);
            resultData.put("dialogHtml", dialogHtml);
            resultData.put("dialogDom", dialogDom);
            resultData.put("inputFields", inputFields);
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(message, resultData);
            log.info("[企业微信工作流创建] 完成 - 用户: {}, 就绪: {}, 表单字段: {}",
                    userId, dialogReady, inputFields.size());

        } catch (Exception e) {
            log.error("[企业微信工作流创建] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "打开工作流创建向导失败: " + e.getMessage());
        } finally {
            releaseSession(session, "工作流创建");
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_IMPORT_DRAFT：在「添加工作流」向导中填入迁移草稿（L1 录入）
    // =========================================================================

    /**
     * 打开新建向导并将 {@code workflowDraft} 中的标题/说明/节点摘要填入可见表单，尽量点击「确定/下一步/创建」。
     * <p>
     * payload：
     * <ul>
     *   <li>{@code workflowDraft}：JSON 对象（与迁移预览返回的 {@code workflowDraft} 一致）</li>
     *   <li>或 {@code workflowDraftJson}：上述对象的字符串形式</li>
     *   <li>{@code workflowLifecycle}：{@code TEMP_TEST}（默认）或 {@code PRODUCTION}。非生产即「临时测试」，
     *       成功后尝试在列表中删除刚创建的工作流，避免触及条数上限</li>
     *   <li>{@code workflowRunMode}：与 {@code workflowLifecycle} 同义（可选）</li>
     *   <li>{@code skipTemporaryWorkflowCleanup}：{@code true} 时跳过测试后删除（排查调试用）</li>
     *   <li>{@code workflowCleanupDisplayName}：列表首列标题，用于定位删除行；默认取 draft.title，再默认「未命名工作流」</li>
     *   <li>{@code workflowCleanupCreator} / {@code temporaryWorkflowCreator}：创建人列子串，同名消歧（建议自动化环境配置）</li>
     * </ul>
     */
    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_IMPORT_DRAFT",
        description = "在新建工作流向导中填入迁移草稿（自然人输入）",
        timeout = 420000L
    )
    public void handleWorkflowImportDraft(EngineMessage message) {
        String userId    = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");

        com.alibaba.fastjson2.JSONObject draft = resolveWorkflowDraft(message);
        if (draft == null || draft.isEmpty()) {
            sendErrorResult(message, "缺少 payload.workflowDraft 或 workflowDraftJson");
            return;
        }
        List<Map<String, Object>> sourceExportRows = parsePayloadExportRows(message);
        boolean verifyImportDefault = !sourceExportRows.isEmpty() || draftHasBizNodesForVerify(draft);
        boolean verifyImport = parsePayloadBoolean(message, "verifyImport", verifyImportDefault);
        Integer verifyRoundsRaw = parsePayloadInteger(message, "verifyMaxRounds");
        int verifyMaxRounds = verifyRoundsRaw != null ? Math.max(1, Math.min(5, verifyRoundsRaw)) : 2;
        boolean autoLearningFeedback = parsePayloadBoolean(message, "autoLearningFeedback", false);

        log.info("[企业微信工作流录入] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        final int maxAttempts = 2;
        try {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                session = null;
                try {
                    SessionPage sp = acquireSessionPageWithRecovery(userId);
                    session = sp.session();
                    Page page = sp.page();
                    session.touch();

                    navigateToWorkflowTab(page, userId);

                    String loginStatus = loginUtil.checkLoginStatus(page, false);
                    if ("false".equals(loginStatus)) {
                        sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                        return;
                    }

                    human.think(page, 800, 1500);

                    Locator addBtn = page.locator(
                        "button:has-text('添加工作流'), " +
                        "[class*='create_btn_by_query'], " +
                        "button:has-text('新建工作流'), " +
                        "button:has-text('+ 工作流'), " +
                        "[class*='add'][class*='workflow']"
                    );
                    if (addBtn.count() == 0) {
                        addBtn = page.locator("button").filter(new Locator.FilterOptions().setHasText("添加"));
                    }
                    if (addBtn.count() == 0) {
                        captureAndUpload(page, userId, "qyweixin_wf_import_noadd_" + System.currentTimeMillis());
                        sendErrorResult(message, "未找到「添加工作流」按钮");
                        return;
                    }

                    human.naturalClick(page, addBtn.first());
                    human.think(page, 2000, 3500);

                    Page targetPage = page;
                    var pages = page.context().pages();
                    if (pages.size() > 1) {
                        targetPage = pages.get(pages.size() - 1);
                        human.think(targetPage, 1500, 3000);
                    }

                    boolean editorMode = isWorkflowEditorPage(targetPage);
                    boolean editorDiag = parsePayloadBoolean(message, "editorDiagnostics", false);
                    Map<String, Object> fillResult;
                    boolean submitted;
                    if (editorMode) {
                        fillResult = fillWorkflowEditorDraft(targetPage, draft, editorDiag, userId);
                        submitted = true;
                    } else {
                        fillResult = fillWorkflowCreateWizard(targetPage, draft);
                        human.think(targetPage, 800, 1500);
                        submitted = tryClickWizardPrimary(targetPage);
                        human.think(targetPage, 1500, 2500);
                    }

                    String screenshotUrl = captureAndUpload(targetPage, userId,
                            "qyweixin_wf_import_" + System.currentTimeMillis());
                    Map<String, Object> verifyReport = null;
                    if (verifyImport) {
                        List<Map<String, Object>> expectedRows = sourceExportRows;
                        String expectedSource = "payload.sourceExportRows";
                        if (expectedRows == null || expectedRows.isEmpty()) {
                            expectedRows = buildExpectedExportRowsFromDraft(draft);
                            expectedSource = "workflowDraft.nodes";
                        }
                        verifyReport = new LinkedHashMap<>();
                        if (expectedRows == null || expectedRows.isEmpty()) {
                            verifyReport.put("pass", false);
                            verifyReport.put("skipped", true);
                            verifyReport.put("reason", "缺少有效对照：payload.sourceExportRows 为空，且 workflowDraft.nodes 不可用");
                        } else {
                            Page editorForVerify = resolveLatestWorkflowEditorPage(targetPage);
                            if (editorForVerify == null) {
                                verifyReport.put("pass", false);
                                verifyReport.put("skipped", true);
                                verifyReport.put("reason", "导入后未定位到工作流编辑器页面");
                            } else {
                                Map<String, Object> repairReplay = null;
                                for (int vr = 0; vr < verifyMaxRounds; vr++) {
                                    if (vr > 0) {
                                        repairReplay = replayClonePanelExports(editorForVerify, expectedRows);
                                    }
                                    Map<String, Object> importedExportBundle = exportFullWorkflowPanels(editorForVerify, userId);
                                    @SuppressWarnings("unchecked")
                                    List<Map<String, Object>> importedRows =
                                            (List<Map<String, Object>>) importedExportBundle.getOrDefault("rows", List.of());
                                    List<Map<String, Object>> importedForCompare =
                                            QyWeixinWorkflowExportComparator.alignImportedRowsForDraftVerification(
                                                    expectedRows, importedRows);
                                    verifyReport = QyWeixinWorkflowExportComparator.compare(
                                            expectedRows, importedForCompare, true);
                                    if (importedForCompare != importedRows) {
                                        verifyReport.put("importedRowCountRaw", importedRows.size());
                                        verifyReport.put("verifyRowAlignment", "strip_terminals_greedy");
                                    } else {
                                        verifyReport.put("verifyRowAlignment", "raw_index");
                                    }
                                    verifyReport.put("verifyRound", vr + 1);
                                    verifyReport.put("verifyMaxRounds", verifyMaxRounds);
                                    verifyReport.put("expectedSource", expectedSource);
                                    verifyReport.put("importGraphHints", importedExportBundle.get("graphHints"));
                                    if (repairReplay != null) {
                                        verifyReport.put("repairReplay", repairReplay);
                                    }
                                    if (Boolean.TRUE.equals(verifyReport.get("pass"))) {
                                        break;
                                    }
                                }
                                List<Map<String, Object>> suggested =
                                        buildLearningCorrectionsFromVerify(expectedRows, verifyReport);
                                if (!suggested.isEmpty()) {
                                    verifyReport.put("learningCorrectionsSuggested", suggested);
                                    if (autoLearningFeedback) {
                                        workflowCloneLearning.mergeFeedback(suggested);
                                        verifyReport.put("autoLearningFeedbackApplied", true);
                                    }
                                }
                                appendQywxCloneVerifyLine(userId, draft.getString("title"), -1, verifyReport);
                            }
                        }
                    }

                    Map<String, Object> resultData = new HashMap<>();
                    resultData.put("createUrl", targetPage.url());
                    resultData.put("screenshotUrl", screenshotUrl);
                    resultData.put("editorMode", editorMode);
                    resultData.put("fill", fillResult);
                    resultData.put("submitted", submitted);
                    resultData.put("editorWorkflowId", extractWorkflowIdFromEditorUrl(targetPage.url()));
                    if (verifyReport != null) {
                        resultData.put("verifyReport", verifyReport);
                    }
                    resultData.put("timestamp", System.currentTimeMillis());

                    if (shouldCleanupTemporaryWorkflowAfterImport(message)) {
                        try {
                            String listTitle = resolveWorkflowCleanupDisplayName(message, draft);
                            String creator = extractPayload(message, "workflowCleanupCreator");
                            if (creator == null || creator.isBlank()) {
                                creator = extractPayload(message, "temporaryWorkflowCreator");
                            }
                            navigateToWorkflowTab(targetPage, userId);
                            human.think(targetPage, 800, 1500);
                            Map<String, Object> cleanup = runDeleteWorkflowOnListPage(
                                    targetPage, userId, -1, listTitle, creator);
                            resultData.put("temporaryWorkflowCleanup", cleanup);
                            log.info("[企业微信工作流录入] 临时测试清理: success={}, title={}",
                                    cleanup.get("success"), listTitle);
                        } catch (Exception ex) {
                            log.warn("[企业微信工作流录入] 临时测试清理异常（不影响录入主结果）: {}", ex.getMessage());
                            resultData.put("temporaryWorkflowCleanup", Map.of(
                                    "success", false,
                                    "errorMessage", "cleanup_exception: " + ex.getMessage()));
                        }
                    } else {
                        Map<String, Object> skipInfo = new LinkedHashMap<>();
                        skipInfo.put("skipped", true);
                        if (skipTemporaryCleanup(message)) {
                            skipInfo.put("reason", "skipTemporaryWorkflowCleanup");
                        } else if (isProductionWorkflowRun(message)) {
                            skipInfo.put("reason", "workflowLifecycle=PRODUCTION");
                        }
                        resultData.put("temporaryWorkflowCleanup", skipInfo);
                    }

                    recordQywxAutomation("QYWEIXIN_WORKFLOW_IMPORT_DRAFT", message, resultData);
                    sendResult(message, resultData);
                    log.info("[企业微信工作流录入] 完成 - 用户: {}, submitted={}", userId, submitted);
                    return;

                } catch (Exception e) {
                    boolean willRetry = attempt < maxAttempts && isTargetClosed(e);
                    if (willRetry) {
                        log.warn("[企业微信工作流录入] TargetClosedError，销毁会话并重试 ({}/{}) - 用户: {}, err: {}",
                                attempt, maxAttempts, userId, e.getMessage());
                        destroyQywxSessionIfPresent(session);
                        session = null;
                        continue;
                    }
                    if (isTargetClosed(e)) {
                        destroyQywxSessionIfPresent(session);
                        session = null;
                    }
                    log.error("[企业微信工作流录入] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
                    recordQywxAutomation("QYWEIXIN_WORKFLOW_IMPORT_DRAFT", message, Map.of(
                            "ok", false,
                            "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
                    ));
                    sendErrorResult(message, formatWorkflowImportDraftFailureMessage(e));
                    return;
                }
            }
        } finally {
            releaseSession(session, "工作流录入");
            userLock.unlock();
        }
    }

    private void destroyQywxSessionIfPresent(BrowserSession s) {
        if (s == null) {
            return;
        }
        try {
            browserPool.destroy(s);
        } catch (Exception ignored) {
            // ignore
        }
    }

    /** 面向管理端的可读错误（含 TargetClosed 时避免只堆 Playwright 英文栈） */
    private String formatWorkflowImportDraftFailureMessage(Exception e) {
        if (!isTargetClosed(e)) {
            return "工作流草稿录入失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        return "工作流草稿录入失败: 自动化浏览器页面或窗口已关闭（TargetClosedError）。"
            + "请勿手动关闭企微管理后台的浏览器窗口或整页刷新导致会话中断；"
            + "若曾弹登录二维码，请先完成登录后再重试。技术详情: "
            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    /**
     * 从消息中解析 workflowDraft（优先 payload 内 JSON 对象，其次 workflowDraftJson 字符串）。
     */
    private com.alibaba.fastjson2.JSONObject resolveWorkflowDraft(EngineMessage message) {
        try {
            String raw = message.getRawJson();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(raw);
            if (root == null) {
                return null;
            }
            com.alibaba.fastjson2.JSONObject payload = root.getJSONObject("payload");
            if (payload == null) {
                return null;
            }
            com.alibaba.fastjson2.JSONObject obj = payload.getJSONObject("workflowDraft");
            if (obj != null && !obj.isEmpty()) {
                return obj;
            }
            String jsonStr = payload.getString("workflowDraftJson");
            if (jsonStr != null && !jsonStr.isBlank()) {
                return com.alibaba.fastjson2.JSON.parseObject(jsonStr);
            }
        } catch (Exception e) {
            log.warn("[企业微信工作流录入] 解析 workflowDraft 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 草稿中有非起止业务节点时，应对照验收（与仅传 workflowDraft、不传 sourceExportRows 的客户端兼容）。 */
    private boolean draftHasBizNodesForVerify(com.alibaba.fastjson2.JSONObject draft) {
        if (draft == null) {
            return false;
        }
        com.alibaba.fastjson2.JSONArray nodes = draft.getJSONArray("nodes");
        if (nodes == null || nodes.isEmpty()) {
            return false;
        }
        for (int i = 0; i < nodes.size(); i++) {
            com.alibaba.fastjson2.JSONObject n = nodes.getJSONObject(i);
            if (n == null) {
                continue;
            }
            String t = n.getString("type");
            if (t == null || t.isBlank()) {
                return true;
            }
            if (!"start".equalsIgnoreCase(t) && !"end".equalsIgnoreCase(t)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parsePayloadExportRows(EngineMessage message) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            String raw = message.getRawJson();
            if (raw == null || raw.isBlank()) {
                return rows;
            }
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(raw);
            if (root == null) {
                return rows;
            }
            com.alibaba.fastjson2.JSONObject payload = root.getJSONObject("payload");
            Object direct = payload != null ? payload.get("sourceExportRows") : null;
            if (direct == null && payload != null) {
                direct = payload.get("expectedExportRows");
            }
            if (direct == null && payload != null) {
                direct = payload.get("exportRows");
            }
            if (direct == null && payload != null) {
                Object b = payload.get("sourceExportBundle");
                if (b instanceof Map<?, ?> bundleMap) {
                    direct = bundleMap.get("rows");
                } else if (b instanceof com.alibaba.fastjson2.JSONObject bundleObj) {
                    direct = bundleObj.get("rows");
                }
            }
            if (direct instanceof List<?> list) {
                for (Object one : list) {
                    if (one instanceof Map<?, ?> mapObj) {
                        rows.add((Map<String, Object>) mapObj);
                    } else {
                        Map<String, Object> m = com.alibaba.fastjson2.JSON.parseObject(
                                com.alibaba.fastjson2.JSON.toJSONString(one), Map.class);
                        if (m != null) {
                            rows.add(m);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[企业微信工作流录入] 解析 sourceExportRows 失败: {}", e.getMessage());
        }
        return rows;
    }

    /** 当未提供 sourceExportRows 时，退化用 workflowDraft.nodes 生成期望对照，避免直接判定验收失败。 */
    private List<Map<String, Object>> buildExpectedExportRowsFromDraft(com.alibaba.fastjson2.JSONObject draft) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (draft == null || draft.isEmpty()) {
            return out;
        }
        com.alibaba.fastjson2.JSONArray nodes = draft.getJSONArray("nodes");
        if (nodes == null || nodes.isEmpty()) {
            return out;
        }
        for (int i = 0; i < nodes.size(); i++) {
            com.alibaba.fastjson2.JSONObject n = nodes.getJSONObject(i);
            if (n == null) {
                continue;
            }
            String type = String.valueOf(n.getOrDefault("type", "")).trim().toLowerCase();
            if ("start".equals(type) || "end".equals(type)) {
                continue;
            }
            String label = String.valueOf(n.getOrDefault("label", "节点_" + i)).trim();
            if (label.isBlank()) {
                label = "节点_" + i;
            }
            String inferred = "大模型问答";
            Object specObj = n.get("editorNodeSpec");
            if (specObj instanceof Map<?, ?> m) {
                Object p = m.get("preferredMenu");
                if (p != null && !String.valueOf(p).isBlank()) {
                    inferred = String.valueOf(p).trim();
                }
            } else if (specObj instanceof com.alibaba.fastjson2.JSONObject so) {
                String p = so.getString("preferredMenu");
                if (p != null && !p.isBlank()) {
                    inferred = p.trim();
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", out.size());
            row.put("labelLine", label);
            row.put("inferredMenu", inferred);
            row.put("panelSnapshot", Map.of("panelTitle", label, "panelTextDigest", label, "fields", List.of()));
            out.add(row);
        }
        return out;
    }

    private Page resolveLatestWorkflowEditorPage(Page fallback) {
        if (fallback == null) {
            return null;
        }
        try {
            List<Page> pages = fallback.context().pages();
            for (int i = pages.size() - 1; i >= 0; i--) {
                Page p = pages.get(i);
                if (isWorkflowEditorPage(p)) {
                    return p;
                }
            }
            if (isWorkflowEditorPage(fallback)) {
                return fallback;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildLearningCorrectionsFromVerify(
            List<Map<String, Object>> sourceRows, Map<String, Object> verifyReport) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (sourceRows == null || sourceRows.isEmpty() || verifyReport == null || verifyReport.isEmpty()) {
            return out;
        }
        Object rowsObj = verifyReport.get("perIndex");
        if (!(rowsObj instanceof List<?> perIndex)) {
            return out;
        }
        for (Object one : perIndex) {
            if (!(one instanceof Map<?, ?> row)) {
                continue;
            }
            boolean menuOk = Boolean.TRUE.equals(row.get("menuOk"));
            if (menuOk) {
                continue;
            }
            Object idxObj = row.get("index");
            int idx = parseIntSafe(String.valueOf(idxObj != null ? idxObj : "-1"), -1);
            if (idx < 0 || idx >= sourceRows.size()) {
                continue;
            }
            Map<String, Object> src = sourceRows.get(idx);
            String menu = String.valueOf(src.getOrDefault("inferredMenu", "")).trim();
            if (menu.isBlank()) {
                continue;
            }
            Map<String, Object> panel = src.get("panelSnapshot") instanceof Map
                    ? (Map<String, Object>) src.get("panelSnapshot") : Map.of();
            String panelTitle = String.valueOf(panel.getOrDefault("panelTitle", ""));
            String panelDigest = String.valueOf(panel.getOrDefault("panelTextDigest", ""));
            Map<String, Object> correction = new LinkedHashMap<>();
            correction.put("index", idx);
            correction.put("panelTitle", panelTitle);
            correction.put("panelDigestPrefix", panelDigest.length() > 128 ? panelDigest.substring(0, 128) : panelDigest);
            correction.put("correctMenu", menu);
            correction.put("sourceLabel", src.getOrDefault("labelLine", ""));
            out.add(correction);
        }
        return out;
    }

    /**
     * 在创建向导页面向可见 input/textarea 依次填入标题、说明、节点摘要。
     */
    private Map<String, Object> fillWorkflowCreateWizard(Page targetPage, com.alibaba.fastjson2.JSONObject draft) {
        Map<String, Object> out = new HashMap<>();
        String title = draft.getString("title");
        if (title == null || title.isBlank()) {
            title = "未命名工作流";
        }
        String desc = draft.getString("description");
        if (desc == null) {
            desc = "";
        }
        StringBuilder nodesText = new StringBuilder();
        com.alibaba.fastjson2.JSONArray nodes = draft.getJSONArray("nodes");
        if (nodes != null && !nodes.isEmpty()) {
            for (int i = 0; i < nodes.size(); i++) {
                com.alibaba.fastjson2.JSONObject n = nodes.getJSONObject(i);
                if (n == null) {
                    continue;
                }
                String label = n.getString("label");
                String type = n.getString("type");
                if (label != null && !label.isBlank()) {
                    nodesText.append("- [").append(type != null ? type : "?").append("] ").append(label).append('\n');
                }
            }
        }
        String detailBlock = desc;
        if (nodesText.length() > 0) {
            detailBlock = detailBlock + "\n\n【节点骨架】\n" + nodesText;
        }
        String endOutputText = summarizeEndOutputs(draft);
        if (!endOutputText.isBlank()) {
            detailBlock = detailBlock + "\n\n【结束节点输出】\n" + endOutputText;
        }
        String invokeHintsText = summarizeInvokeHints(draft);
        if (!invokeHintsText.isBlank()) {
            detailBlock = detailBlock + "\n\n【触发提示】\n" + invokeHintsText;
        }

        List<String> filledKeys = new ArrayList<>();
        try {
            Locator candidates = targetPage.locator(
                "input:not([type='hidden']):not([type='checkbox']):not([type='radio']):not([type='file']), textarea"
            );
            int total = candidates.count();
            List<Locator> visible = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                Locator loc = candidates.nth(i);
                try {
                    if (loc.isVisible()) {
                        visible.add(loc);
                    }
                } catch (Exception ignored) {
                }
            }
            log.info("[企业微信工作流录入] 可见表单控件数量: {}", visible.size());

            if (!visible.isEmpty()) {
                human.humanType(targetPage, visible.get(0), title);
                filledKeys.add("field0_title");
            }
            if (visible.size() > 1) {
                human.humanType(targetPage, visible.get(1), detailBlock.trim());
                filledKeys.add("field1_detail");
            } else if (!visible.isEmpty() && !detailBlock.isBlank()) {
                String combined = title + "\n\n" + detailBlock.trim();
                human.humanType(targetPage, visible.get(0), combined);
                filledKeys.add("field0_combined");
            }
        } catch (Exception e) {
            log.warn("[企业微信工作流录入] 填表异常: {}", e.getMessage());
            out.put("fillError", e.getMessage());
        }
        out.put("filledKeys", filledKeys);
        out.put("titleLen", title.length());
        out.put("detailLen", detailBlock.length());
        out.put("endOutputsApplied", !endOutputText.isBlank());
        out.put("invokeHintsApplied", !invokeHintsText.isBlank());
        return out;
    }

    /** 尝试点击向导主按钮（确定 / 下一步 / 创建 / 完成） */
    private boolean tryClickWizardPrimary(Page page) {
        String[] texts = {"确定", "下一步", "创建", "完成", "保存"};
        for (String t : texts) {
            Locator btn = page.locator("button:has-text('" + t + "')");
            if (btn.count() > 0) {
                try {
                    Locator first = btn.first();
                    if (first.isVisible()) {
                        human.naturalClick(page, first);
                        log.info("[企业微信工作流录入] 已点击按钮: {}", t);
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    private boolean isWorkflowEditorPage(Page page) {
        if (page == null) {
            return false;
        }
        String url = page.url();
        if (url != null && url.contains("workflow_editor_client")) {
            return true;
        }
        return waitForEditorReady(page);
    }

    /**
     * 编辑器模式下的最小自动动作：尝试设置标题、触发保存/检查，避免误填右侧调试输入框。
     */
    private Map<String, Object> fillWorkflowEditorDraft(Page page, com.alibaba.fastjson2.JSONObject draft,
                                                       boolean editorDiagnostics) {
        return fillWorkflowEditorDraft(page, draft, editorDiagnostics, null);
    }

    private Map<String, Object> fillWorkflowEditorDraft(Page page, com.alibaba.fastjson2.JSONObject draft,
                                                       boolean editorDiagnostics, String userId) {
        Map<String, Object> out = new HashMap<>();
        String title = draft.getString("title");
        if (title == null || title.isBlank()) {
            title = "未命名工作流";
        }
        if (editorDiagnostics) {
            out.put("diagnosticsBefore", snapshotEditorForProbe(page));
        }
        gateIfLoginQrBlocking(page);
        boolean renamed = tryRenameEditorTitle(page, title);
        human.think(page, 500, 1000);

        boolean saved = tryClickAny(page, "保存");
        human.think(page, 400, 900);
        // 不在录入前反复点「检查」/收侧栏：左栏多数情况下不挡画布点击，Toolbar「检查」反而会在展开/折叠间抢焦点，
        // 影响「设置变量」等居中弹层与后续 keyboard/DOM 探测。
        boolean checked = false;
        human.think(page, 350, 700);
        Map<String, Object> addNodes = tryAddDraftNodesInEditor(page, draft, userId);
        Map<String, Object> autoRecovery = tryAutoRecoverAddNodes(page, draft, addNodes, userId);
        human.think(page, 400, 900);
        Map<String, Object> hintSweep = runValidationHintSweep(page);
        if (!hintSweep.isEmpty()) {
            out.put("validationHintSweep", hintSweep);
        }
        if (!saved) {
            saved = tryClickAny(page, "保存");
        }
        List<Map<String, Object>> issues = collectValidationIssues(page);
        Map<String, Object> summary = summarizeValidationIssues(issues, true, checked, true);

        List<Map<String, String>> nodeList = extractEditorNodeList(page);
        out.put("titleRenamed", renamed);
        out.put("saveClicked", saved);
        out.put("checkClicked", checked);
        out.put("addNodes", addNodes);
        if (autoRecovery != null && !autoRecovery.isEmpty()) {
            out.put("autoRecovery", autoRecovery);
        }
        out.put("issues", issues);
        out.put("summary", summary);
        out.put("nodeCount", nodeList.size());
        out.put("nodeList", nodeList);
        out.put("mode", "editor");
        if (editorDiagnostics) {
            out.put("diagnosticsAfter", snapshotEditorForProbe(page));
        }
        return out;
    }

    /** 与 {@link #fillWorkflowEditorDraft(Page, com.alibaba.fastjson2.JSONObject, boolean)} 兼容的无诊断重载 */
    private Map<String, Object> fillWorkflowEditorDraft(Page page, com.alibaba.fastjson2.JSONObject draft) {
        return fillWorkflowEditorDraft(page, draft, false, null);
    }

    private Map<String, Object> snapshotEditorForProbe(Page page) {
        List<String> triggers = workflowEditorNodeMapping != null
                ? new ArrayList<>(workflowEditorNodeMapping.getAddPanelTriggers())
                : List.of("添加指定操作", "添加运营操作", "添加节点");
        return PlaywrightWorkflowEditorDiagnostics.fullSnapshot(page, triggers);
    }

    /**
     * openPanel 失败时随响应带回的轻量 DOM 摘要，便于与 {@link #captureAndUpload} 截图对照解读交互意图。
     */
    private Map<String, Object> probeOpenPanelEnvironment(Page page) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> js = (Map<String, Object>) page.evaluate(
                    "() => {\n"
                            + "  const nodes = document.querySelectorAll('[data-node-id]').length;\n"
                            + "  let addHits = 0;\n"
                            + "  const scan = document.querySelectorAll('span, div, button, svg text, svg tspan');\n"
                            + "  for (const el of scan) {\n"
                            + "    const t = (el.textContent || '').replace(/\\s+/g, '');\n"
                            + "    if (!t || t.length > 48) continue;\n"
                            + "    if (t.includes('添加指定操作') || t.includes('添加运营')\n"
                            + "        || (t.includes('添加') && t.length < 22)) {\n"
                            + "      addHits++;\n"
                            + "    }\n"
                            + "  }\n"
                            + "  return {\n"
                            + "    dataNodeCount: nodes,\n"
                            + "    roughAddTextHits: addHits,\n"
                            + "    bodyTextLen: (document.body.innerText || '').length\n"
                            + "  };\n"
                            + "}");
            if (js != null) {
                m.putAll(js);
            }
        } catch (Exception e) {
            m.put("probeError", e.getMessage());
        }
        return m;
    }

    /**
     * 当首轮添加节点几乎全失败时，自动做一次恢复尝试（无需用户手工再点一次）。
     */
    private Map<String, Object> tryAutoRecoverAddNodes(Page page,
                                                       com.alibaba.fastjson2.JSONObject draft,
                                                       Map<String, Object> firstAddNodes,
                                                       String userId) {
        if (!shouldTriggerAddNodeRecovery(firstAddNodes)) {
            return Map.of();
        }
        Map<String, Object> recovery = new HashMap<>();
        recovery.put("triggered", true);
        recovery.put("reason", "first-pass-add-nodes-not-effective");
        try {
            try {
                page.setViewportSize(1920, 1080);
                human.think(page, 500, 1000);
                recovery.put("viewport", "1920x1080");
            } catch (Exception ignored) {
            }
            human.think(page, 400, 900);
            Map<String, Object> retry = tryAddDraftNodesInEditor(page, draft, userId);
            recovery.put("retryAddNodes", retry);
            if (shouldTriggerAddNodeRecovery(retry)) {
                recovery.put("diagnostics", snapshotEditorForProbe(page));
            }
        } catch (Exception e) {
            recovery.put("ok", false);
            recovery.put("error", e.getMessage());
        }
        return recovery;
    }

    @SuppressWarnings("unchecked")
    private boolean shouldTriggerAddNodeRecovery(Map<String, Object> addNodes) {
        if (addNodes == null || addNodes.isEmpty()) {
            return true;
        }
        List<String> added = addNodes.get("added") instanceof List<?>
                ? (List<String>) addNodes.get("added")
                : List.of();
        List<String> failed = addNodes.get("failed") instanceof List<?>
                ? (List<String>) addNodes.get("failed")
                : List.of();
        return failed != null && !failed.isEmpty() && (added == null || added.isEmpty());
    }

    private Map<String, Object> tryAddDraftNodesInEditor(Page page, com.alibaba.fastjson2.JSONObject draft,
                                                       String userId) {
        Map<String, Object> result = new HashMap<>();
        List<String> added = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> skippedExisting = new ArrayList<>();
        com.alibaba.fastjson2.JSONArray nodes = draft.getJSONArray("nodes");
        if (nodes == null || nodes.isEmpty()) {
            result.put("attempted", 0);
            result.put("added", added);
            result.put("failed", failed);
            result.put("skippedExisting", skippedExisting);
            result.put("status", "no-nodes");
            return result;
        }

        Set<String> existingLabels = new HashSet<>();
        List<Map<String, String>> existingNodes = extractEditorNodeList(page);
        for (Map<String, String> row : existingNodes) {
            String tx = row.get("text");
            if (tx != null && !tx.isBlank()) {
                existingLabels.add(tx.replace("\n", "").trim());
            }
        }

        int maxNodes = workflowEditorNodeMapping != null ? workflowEditorNodeMapping.getMaxNodesPerImportRun() : 3;
        Integer cloneCap = draft.getInteger("cloneMaxNodesPerRun");
        if (cloneCap != null && cloneCap > 0) {
            maxNodes = Math.min(cloneCap, 20);
        }
        int attempts = 0;
        List<Map<String, Object>> perNodeLog = new ArrayList<>();
        for (int i = 0; i < nodes.size() && attempts < maxNodes; i++) {
            com.alibaba.fastjson2.JSONObject node = nodes.getJSONObject(i);
            if (node == null) {
                continue;
            }
            String type = node.getString("type");
            if ("start".equals(type) || "end".equals(type)) {
                continue;
            }
            String label = node.getString("label");
            if (label == null || label.isBlank()) {
                label = "节点-" + (i + 1);
            }
            String compact = label.replace("\n", "").trim();
            boolean alreadyExists = false;
            for (String ex : existingLabels) {
                if (ex.contains(compact) || compact.contains(ex)) {
                    alreadyExists = true;
                    break;
                }
            }
            if (alreadyExists) {
                skippedExisting.add(label);
                continue;
            }
            attempts++;
            com.alibaba.fastjson2.JSONObject editorNodeSpec = node.getJSONObject("editorNodeSpec");
            Map<String, Object> oneLog = addSingleNodeWithMapping(page, type, label, editorNodeSpec, userId);
            perNodeLog.add(oneLog);
            if (Boolean.TRUE.equals(oneLog.get("ok"))) {
                added.add(label);
                existingLabels.add(compact);
            } else {
                failed.add(label);
            }
            human.think(page, 300, 800);
        }
        result.put("attempted", attempts);
        result.put("added", added);
        result.put("failed", failed);
        result.put("skippedExisting", skippedExisting);
        result.put("perNodeLog", perNodeLog);
        result.put("mappingConfigVersion", workflowEditorNodeMapping != null ? "classpath:qyweixin-workflow-editor-node-mapping.json" : "none");
        result.put("status", failed.isEmpty() ? "ok" : "partial");
        return result;
    }

    /**
     * 使用可配置映射：先打开「添加操作」面板，再按类型策略链与全局回退依次点菜单项。
     *
     * @param editorNodeSpec 可选；迁移侧 {@code preferredMenu} / {@code menuCandidates} 优先于类型映射，表达「完整结构」意图。
     */
    private Map<String, Object> addSingleNodeWithMapping(Page page, String draftType, String label,
                                                         com.alibaba.fastjson2.JSONObject editorNodeSpec,
                                                         String userId) {
        Map<String, Object> logRow = new HashMap<>();
        logRow.put("draftType", draftType);
        logRow.put("label", label);
        try {
            if (!openAddOperationPanel(page)) {
                logRow.put("ok", false);
                logRow.put("phase", "openPanel");
                logRow.put("reason", "未找到「添加指定操作/添加运营操作」等入口");
                logRow.put("openPanelEnvironment", probeOpenPanelEnvironment(page));
                if (userId != null && !userId.isBlank()) {
                    String safe = label.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_");
                    if (safe.length() > 36) {
                        safe = safe.substring(0, 36);
                    }
                    String shot = captureAndUpload(page, userId,
                            "qyweixin_wf_openpanel_fail_" + System.currentTimeMillis() + "_" + safe);
                    if (shot != null) {
                        logRow.put("failureScreenshotUrl", shot);
                    }
                }
                return logRow;
            }
            human.think(page, 400, 900);

            if (editorNodeSpec != null) {
                String pm = editorNodeSpec.getString("preferredMenu");
                if (pm != null && !pm.isBlank() && tryClickVisibleMenuItemWithAliases(page, pm.trim())) {
                    human.think(page, 500, 1100);
                    logRow.put("ok", true);
                    logRow.put("chosenMenu", pm.trim());
                    logRow.put("via", "editorNodeSpec.preferredMenu");
                    attachPostAddWorkflowNodeConfig(page, pm.trim(), label, logRow);
                    return logRow;
                }
                com.alibaba.fastjson2.JSONArray cands = editorNodeSpec.getJSONArray("menuCandidates");
                if (cands != null && !cands.isEmpty()) {
                    List<String> triedSpec = new ArrayList<>();
                    for (int ci = 0; ci < cands.size(); ci++) {
                        String c = cands.getString(ci);
                        if (c == null || c.isBlank()) {
                            continue;
                        }
                        triedSpec.add(c);
                        if (tryClickVisibleMenuItemWithAliases(page, c)) {
                            human.think(page, 500, 1100);
                            logRow.put("ok", true);
                            logRow.put("chosenMenu", c);
                            logRow.put("via", "editorNodeSpec.menuCandidates");
                            logRow.put("tried", triedSpec);
                            attachPostAddWorkflowNodeConfig(page, c, label, logRow);
                            return logRow;
                        }
                    }
                    logRow.put("editorSpecTried", triedSpec);
                }
            }

            QyWeixinWorkflowEditorNodeMapping mapping = workflowEditorNodeMapping;
            if (mapping == null) {
                logRow.put("ok", false);
                logRow.put("phase", "mapping");
                logRow.put("reason", "QyWeixinWorkflowEditorNodeMapping 未注入");
                return logRow;
            }
            List<List<String>> strategies = mapping.resolveStrategies(draftType);
            List<String> tried = new ArrayList<>();
            int stratIdx = 0;
            for (List<String> strategy : strategies) {
                for (String menuText : strategy) {
                    if (menuText == null || menuText.isBlank()) {
                        continue;
                    }
                    tried.add(menuText);
                    if (tryClickVisibleMenuItemWithAliases(page, menuText)) {
                        human.think(page, 500, 1100);
                        logRow.put("ok", true);
                        logRow.put("chosenMenu", menuText);
                        logRow.put("strategyIndex", stratIdx);
                        logRow.put("tried", tried);
                        attachPostAddWorkflowNodeConfig(page, menuText, label, logRow);
                        return logRow;
                    }
                }
                stratIdx++;
            }
            for (String fb : mapping.getGlobalMenuFallback()) {
                if (fb == null || fb.isBlank()) {
                    continue;
                }
                tried.add("fallback:" + fb);
                if (tryClickVisibleMenuItemWithAliases(page, fb)) {
                    human.think(page, 500, 1100);
                    logRow.put("ok", true);
                    logRow.put("chosenMenu", fb);
                    logRow.put("via", "globalFallback");
                    logRow.put("tried", tried);
                    attachPostAddWorkflowNodeConfig(page, fb, label, logRow);
                    return logRow;
                }
            }
            if (clickFirstVisibleMenuFallback(page)) {
                human.think(page, 500, 1100);
                logRow.put("ok", true);
                logRow.put("via", "firstVisibleMenuItem");
                logRow.put("tried", tried);
                // fallback 未记录具体菜单项：根据当前弹窗标题推断
                String inferred = inferChosenMenuFromOpenConfigModal(page);
                if (inferred != null) {
                    logRow.put("chosenMenu", inferred);
                    attachPostAddWorkflowNodeConfig(page, inferred, label, logRow);
                } else {
                    attachPostAddWorkflowNodeConfig(page, "", label, logRow);
                }
                return logRow;
            }
            logRow.put("ok", false);
            logRow.put("phase", "pickMenu");
            logRow.put("reason", "策略与全局回退均未匹配到可点菜单项");
            logRow.put("tried", tried);
        } catch (Exception e) {
            log.warn("[企业微信工作流录入] 添加节点失败 type={}, label={}, err={}", draftType, label, e.getMessage());
            logRow.put("ok", false);
            logRow.put("error", e.getMessage());
        }
        return logRow;
    }

    /**
     * 菜单点选成功后：编辑器常立刻弹出节点<b>属性/配置</b>抽屉或对话框。左侧「检查」里的报错、
     * 弹层内红字（如「变量列表不能为空」）不是无关提示，而是<b>自动化尚未完成的必填步骤</b>。
     * 此处按菜单类型做最小合法填充，使校验可通过；后续新增节点类型时在此接续。
     */
    private void attachPostAddWorkflowNodeConfig(Page page, String chosenMenu, String nodeLabel,
                                                 Map<String, Object> logRow) {
        Map<String, Object> post = new HashMap<>();
        try {
            post.putAll(completeQywxNodeConfigAfterMenuPick(page, chosenMenu, nodeLabel));
        } catch (Exception e) {
            post.put("ok", false);
            post.put("error", e.getMessage());
            log.debug("[企业微信工作流] 节点配置收尾: {}", e.getMessage());
        }
        if (!post.isEmpty()) {
            logRow.put("postNodeConfig", post);
        }
    }

    /** 从当前可见配置弹窗标题推断节点类型（用于菜单回退路径未记录 chosenMenu 时）。 */
    private String inferChosenMenuFromOpenConfigModal(Page page) {
        String[][] pairs = {
                {"设置变量", "设置变量"},
                {"HTTP请求", "HTTP请求"},
                {"大模型", "大模型问答"},
                {"知识库", "知识库问答"},
        };
        for (String[] p : pairs) {
            try {
                Locator t = page.getByText(p[0], new Page.GetByTextOptions().setExact(false));
                if (t.count() > 0 && t.first().isVisible()) {
                    Locator scope = resolveWorkflowEditorModalScope(page, p[0]);
                    if (scope.count() > 0 && scope.first().isVisible()) {
                        return p[1];
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /**
     * 根据所选菜单与当前 DOM，完成必要的节点配置弹窗（目前实现：设置变量）。
     */
    private Map<String, Object> completeQywxNodeConfigAfterMenuPick(Page page, String chosenMenu,
                                                                    String nodeLabel) {
        Map<String, Object> out = new HashMap<>();
        human.think(page, 350, 800);
        boolean wantSetVar = chosenMenu != null && (chosenMenu.contains("设置变量")
                || chosenMenu.contains("变量") && !chosenMenu.contains("输入变量"));
        boolean modalLooksLikeSetVar = isWorkflowSetVariableModalVisible(page);
        if (wantSetVar || modalLooksLikeSetVar) {
            boolean filled = tryFillSetVariableModalMinimal(page, nodeLabel);
            out.put("setVariableModal", filled ? "filled" : "skippedOrFailed");
            out.put("ok", filled);
            return out;
        }
        if (chosenMenu != null && chosenMenu.contains("结束")) {
            human.think(page, 400, 900);
            boolean dismissed = tryClickAny(page, "确定", "确认", "完成", "保存", "应用");
            out.put("endOutputModal", dismissed ? "confirmedOrDismissed" : "skipped");
            if (!dismissed) {
                try {
                    page.keyboard().press("Escape");
                } catch (Exception ignored) {
                }
            }
            human.think(page, 220, 500);
            return out;
        }
        out.put("skipped", true);
        return out;
    }

    private boolean isWorkflowSetVariableModalVisible(Page page) {
        try {
            Locator scope = resolveWorkflowEditorModalScope(page, "设置变量");
            if (scope.count() == 0 || !scope.first().isVisible()) {
                return false;
            }
            String tx = scope.first().textContent();
            return tx != null && (tx.contains("输入变量") || tx.contains("变量列表"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 企微工作流编辑器里「设置变量」配置层：点「+ 添加」并填一条占位变量名，消除「变量列表不能为空」。
     * <p>
     * 变量输入框在点击「添加」后 DOM 会重排，固定 Playwright 定位不可靠；故优先用浏览器内
     * {@link QyWeixinSetVariableDomProbe}（找弹层 → 点添加 → 最后一个空输入 + React 友好赋值），定位器仅作兜底。
     */
    private boolean tryFillSetVariableModalMinimal(Page page, String nodeLabel) {
        Locator modal = resolveWorkflowEditorModalScope(page, "设置变量");
        if (modal.count() == 0 || !modal.first().isVisible()) {
            return false;
        }
        Locator scope = modal.first();
        scope.scrollIntoViewIfNeeded();
        human.think(page, 250, 550);

        boolean clickedAdd = false;
        try {
            Object o = page.evaluate(QyWeixinSetVariableDomProbe.jsClickAddInSetVariableModal());
            clickedAdd = Boolean.TRUE.equals(o);
        } catch (Exception e) {
            log.debug("[企业微信工作流] JS 点击「+添加」失败: {}", e.getMessage());
        }
        if (!clickedAdd) {
            clickedAdd = tryClickSetVariableModalAddButton(scope, page);
        }
        if (!clickedAdd) {
            log.warn("[企业微信工作流] 「设置变量」未命中「+添加」（含 JS 探测），已尝试 Playwright 兜底");
        }
        human.think(page, 550, 1200);

        String varName = sanitizeWorkflowVarName(nodeLabel);
        boolean filled = false;
        try {
            Object fo = page.evaluate(QyWeixinSetVariableDomProbe.jsFillFirstVariableNameField(), varName);
            filled = Boolean.TRUE.equals(fo);
        } catch (Exception e) {
            log.debug("[企业微信工作流] JS 填变量名失败: {}", e.getMessage());
        }
        if (!filled) {
            filled = fillFirstVariableNameInSetVarModal(scope, page, varName);
        }
        if (filled) {
            human.think(page, 350, 700);
            tryClickModalPrimaryConfirm(scope, page);
            return true;
        }
        return clickedAdd;
    }

    /** 企微「+ 添加」占位：多策略，避免只认合并文本导致点不中。 */
    private boolean tryClickSetVariableModalAddButton(Locator scope, Page page) {
        String[] selectors = {
                "button:has-text('+ 添加')",
                "button:has-text('＋ 添加')",
                "[role='button']:has-text('+ 添加')",
                "div[role='button']:has-text('+ 添加')",
                "span:has-text('+ 添加')",
                "button:has-text('添加')",
                "[role='button']:has-text('添加')",
                "div[role='button']:has-text('添加')",
                "[class*='add'][class*='btn'], [class*='Add'][class*='button']"
        };
        for (String sel : selectors) {
            try {
                Locator loc = scope.locator(sel);
                int n = Math.min(loc.count(), 14);
                for (int i = 0; i < n; i++) {
                    Locator el = loc.nth(i);
                    if (!el.isVisible()) {
                        continue;
                    }
                    String tx = el.textContent();
                    if (tx == null || !tx.contains("添加")) {
                        continue;
                    }
                    if (tx.length() > 40) {
                        continue;
                    }
                    reliableClickLocator(page, el);
                    log.info("[企业微信工作流] 已点「设置变量」添加控件 sel={}", sel);
                    return true;
                }
            } catch (Exception e) {
                log.debug("[企业微信工作流] 添加按钮 sel={} : {}", sel, e.getMessage());
            }
        }
        // 兄弟文案拆分时：同一行含 + 与 添加
        try {
            Locator rowHint = scope.locator(
                    "xpath=.//*[contains(normalize-space(.), '添加')][contains(.,'+') or contains(.,'＋')]");
            int rn = Math.min(rowHint.count(), 10);
            for (int i = 0; i < rn; i++) {
                Locator row = rowHint.nth(i);
                if (!row.isVisible()) {
                    continue;
                }
                reliableClickLocator(page, row);
                log.info("[企业微信工作流] 已点「设置变量」含+添加的聚合行 xpath");
                return true;
            }
        } catch (Exception ignored) {
        }
        return tryClickModalAddRowButton(scope, page);
    }

    private boolean fillFirstVariableNameInSetVarModal(Locator scope, Page page, String varName) {
        Locator inputs = scope.locator(
                ".t-input input, .t-input__inner, input.t-input__inner, "
                        + "[class*='Input'] input:not([type='hidden']), "
                        + "input[type='text'], input:not([type='hidden']), textarea");
        int in = Math.min(inputs.count(), 24);
        for (int i = 0; i < in; i++) {
            Locator inp = inputs.nth(i);
            try {
                if (!inp.isVisible()) {
                    continue;
                }
                String tp = inp.getAttribute("type");
                if ("hidden".equals(tp) || "file".equals(tp) || "checkbox".equals(tp) || "radio".equals(tp)) {
                    continue;
                }
                inp.scrollIntoViewIfNeeded();
                try {
                    inp.fill(varName);
                } catch (Exception e1) {
                    log.debug("[企业微信工作流] fill 失败，改 humanType: {}", e1.getMessage());
                    human.humanType(page, inp, varName);
                }
                log.info("[企业微信工作流] 「设置变量」已填入占位变量名: {} (input #{})", varName, i);
                return true;
            } catch (Exception e) {
                log.debug("[企业微信工作流] 变量名 input#{}: {}", i, e.getMessage());
            }
        }
        // 部分实现用 contenteditable 承载变量名
        Locator edit = scope.locator("[contenteditable='true']");
        int en = Math.min(edit.count(), 8);
        for (int i = 0; i < en; i++) {
            Locator ce = edit.nth(i);
            try {
                if (!ce.isVisible()) {
                    continue;
                }
                ce.click();
                try {
                    ce.fill(varName);
                } catch (Exception e2) {
                    page.keyboard().type(varName);
                }
                log.info("[企业微信工作流] 「设置变量」已填入占位变量名: {} (contenteditable #{})", varName, i);
                return true;
            } catch (Exception e) {
                log.debug("[企业微信工作流] contenteditable#{}: {}", i, e.getMessage());
            }
        }
        return false;
    }

    private boolean tryClickModalAddRowButton(Locator scope, Page page) {
        String[][] candidates = {{"+ 添加"}, {"＋ 添加"}, {"添加"}};
        for (String[] c : candidates) {
            try {
                Locator hit = scope.getByText(c[0], new Locator.GetByTextOptions().setExact(false));
                int n = Math.min(hit.count(), 10);
                for (int i = 0; i < n; i++) {
                    Locator el = hit.nth(i);
                    if (!el.isVisible()) {
                        continue;
                    }
                    String tx = el.textContent();
                    if (tx != null && tx.contains("添加")) {
                        Locator clickTarget = resolveQywxPickerRowForClick(page, el, "添加");
                        reliableClickLocator(page, clickTarget);
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void tryClickModalPrimaryConfirm(Locator scope, Page page) {
        String[] labels = {"确定", "确认", "保存", "完成", "Done"};
        for (String lb : labels) {
            try {
                Locator btn = scope.locator("button, [role='button']").filter(
                        new Locator.FilterOptions().setHasText(lb));
                if (btn.count() > 0 && btn.first().isVisible()) {
                    reliableClickLocator(page, btn.first());
                    log.info("[企业微信工作流] 已点配置弹窗「{}」", lb);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String sanitizeWorkflowVarName(String nodeLabel) {
        if (nodeLabel == null || nodeLabel.isBlank()) {
            return "skill_import_var";
        }
        String s = nodeLabel.trim().replaceAll("\\s+", "_");
        s = s.replaceAll("[^a-zA-Z0-9_\u4e00-\u9fa5]", "_");
        if (s.isBlank() || s.length() > 64) {
            return "skill_import_var";
        }
        return s;
    }

    /** 解析包含指定标题文案的、当前最可能的前台模态/抽屉（企微常为自定义层，未必有 role=dialog）。 */
    private Locator resolveWorkflowEditorModalScope(Page page, String titleContains) {
        Locator title = page.getByText(titleContains, new Page.GetByTextOptions().setExact(false));
        if (title.count() == 0) {
            return page.locator("[data-wxfbsir-engine-missing-modal='1']");
        }
        Locator hit = title.first();
        if (!hit.isVisible()) {
            return page.locator("[data-wxfbsir-engine-missing-modal='1']");
        }
        try {
            Locator byRole = page.locator("[role='dialog']").filter(
                    new Locator.FilterOptions().setHasText(titleContains));
            if (byRole.count() > 0) {
                for (int i = 0; i < Math.min(byRole.count(), 5); i++) {
                    Locator m = byRole.nth(i);
                    if (m.isVisible()) {
                        return m;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Locator anc = hit.locator("xpath=ancestor::*[(@role='dialog' or contains(@class,'Dialog') "
                    + "or contains(@class,'dialog') or contains(@class,'Modal') or contains(@class,'Drawer') "
                    + "or contains(@class,'drawer'))][1]");
            if (anc.count() > 0 && anc.first().isVisible()) {
                return anc.first();
            }
        } catch (Exception ignored) {
        }
        try {
            Locator anc2 = hit.locator("xpath=ancestor::div[position()<=12][1]");
            if (anc2.count() > 0 && anc2.first().isVisible()) {
                BoundingBox box = anc2.first().boundingBox();
                if (box != null && box.width > 200 && box.height > 120) {
                    return anc2.first();
                }
            }
        } catch (Exception ignored) {
        }
        return page.locator("[data-wxfbsir-engine-missing-modal='1']");
    }

    /**
     * 根据页面聚合文案（检查区 + 弹层 + collectValidationIssues）匹配 {@link QyWeixinValidationHintHandlers}，
     * 多轮执行占位步骤，直到无进展或无匹配。企微「检查」报错是待完成步骤，不是背景噪音。
     */
    private Map<String, Object> runValidationHintSweep(Page page) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (validationHintHandlers == null) {
            out.put("skipped", "handlers-missing");
            return out;
        }
        int maxRounds = validationHintHandlers.getRoundsMaxDefault();
        List<Map<String, Object>> rounds = new ArrayList<>();
        boolean anyProgress = false;
        for (int r = 0; r < maxRounds; r++) {
            // 不每轮点工具栏「检查」：易切换左侧面板焦点/重绘，与居中节点配置弹层冲突。
            human.think(page, 350, 800);
            String digest = PlaywrightWorkflowEditorDiagnostics.collectAggregatedValidationHints(page);
            StringBuilder agg = new StringBuilder(digest != null ? digest : "");
            for (Map<String, Object> iss : collectValidationIssues(page)) {
                Object msg = iss.get("message");
                if (msg != null) {
                    agg.append(" | ").append(msg);
                }
            }
            String combined = agg.toString();
            if (combined.length() > 16000) {
                combined = combined.substring(0, 16000);
            }
            Map<String, Object> h = validationHintHandlers.findFirstHandler(combined);
            if (h == null || h.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("round", r);
                row.put("action", "no-matching-handler");
                rounds.add(row);
                break;
            }
            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) h.get("steps");
            if (steps == null || steps.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (String step : steps) {
                if (executeValidationPlaceholderStep(page, step)) {
                    progressed = true;
                    anyProgress = true;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("round", r);
            row.put("handlerId", h.get("handlerId"));
            row.put("matchedBy", h.get("matchedBy"));
            row.put("steps", steps);
            row.put("progress", progressed);
            rounds.add(row);
            if (!progressed) {
                break;
            }
            human.think(page, 500, 1000);
        }
        out.put("rounds", rounds);
        out.put("anyProgress", anyProgress);
        return out;
    }

    private boolean executeValidationPlaceholderStep(Page page, String step) {
        if (step == null) {
            return false;
        }
        return switch (step) {
            case "SET_VARIABLE_MINIMAL" -> tryFillSetVariableModalMinimal(page, "skill_hint_sweep");
            case "HTTP_REQUEST_PLACEHOLDER" -> tryFillHttpRequestModalMinimal(page);
            default -> false;
        };
    }

    /** HTTP 请求节点：填占位 URL 以通过「地址不能为空」类校验（可后续换真实域名策略）。 */
    private boolean tryFillHttpRequestModalMinimal(Page page) {
        String[] titles = {"HTTP请求", "HTTP"};
        for (String title : titles) {
            Locator scope = resolveWorkflowEditorModalScope(page, title);
            if (scope.count() == 0 || !scope.first().isVisible()) {
                continue;
            }
            Locator s = scope.first();
            Locator inputs = s.locator("input[type='text'], input[type='url'], input:not([type='hidden'])");
            for (int i = 0; i < Math.min(inputs.count(), 12); i++) {
                Locator inp = inputs.nth(i);
                try {
                    if (!inp.isVisible()) {
                        continue;
                    }
                    String tp = inp.getAttribute("type");
                    if ("hidden".equals(tp) || "file".equals(tp)) {
                        continue;
                    }
                    String ph = inp.getAttribute("placeholder");
                    if (ph != null && (ph.contains("URL") || ph.contains("地址") || ph.contains("http") || ph.contains("接口"))) {
                        human.humanType(page, inp, "https://example.com/");
                        human.think(page, 300, 600);
                        tryClickModalPrimaryConfirm(s, page);
                        log.info("[企业微信工作流] HTTP 请求占位 URL 已填");
                        return true;
                    }
                } catch (Exception e) {
                    log.debug("[企业微信工作流] HTTP 占位: {}", e.getMessage());
                }
            }
            if (inputs.count() > 0 && inputs.first().isVisible()) {
                try {
                    human.humanType(page, inputs.first(), "https://example.com/");
                    human.think(page, 300, 600);
                    tryClickModalPrimaryConfirm(s, page);
                    log.info("[企业微信工作流] HTTP 请求占位 URL 已填(首框)");
                    return true;
                } catch (Exception e) {
                    log.debug("[企业微信工作流] HTTP 首框: {}", e.getMessage());
                }
            }
        }
        return false;
    }

    private boolean openAddOperationPanel(Page page) {
        gateIfLoginQrBlocking(page);
        if (isQywxOperationPickerVisible(page)) {
            log.info("[企业微信工作流] 「选择需要执行的操作」已显示，不再重复点画布「+」避免无响应");
            return true;
        }

        tryDismissBlockingConfigModalsBeforeCanvasAdd(page);
        // 自动保存、连线重绘后 DOM/SVG 层晚一拍就绪；略拉长稳定窗降低 openPanel 假阴性
        human.think(page, 500, 1100);
        scrollWorkflowCanvasTowardLatestEdge(page);
        scrollWorkflowCanvasAddTriggersIntoView(page);

        human.think(page, 350, 700);

        List<String> triggers = workflowEditorNodeMapping != null
                ? workflowEditorNodeMapping.getAddPanelTriggers()
                : List.of("添加指定操作", "添加运营操作", "添加节点操作", "添加节点");
        if (runAddPanelOpenStrategies(page, triggers)) {
            return true;
        }
        nudgeWorkflowCanvasWithWheel(page);
        scrollWorkflowCanvasTowardLatestEdge(page);
        scrollWorkflowCanvasAddTriggersIntoView(page);
        human.think(page, 450, 950);
        return runAddPanelOpenStrategies(page, triggers);
    }

    /**
     * 线性追加节点时，新的「添加指定操作」常在中间连线上且不在当前视口；仅靠滚到末端节点不够。
     * 在浏览器端扫描 SVG / 文案节点并 scrollIntoView，便于 Playwright 命中。
     */
    private void scrollWorkflowCanvasAddTriggersIntoView(Page page) {
        try {
            Object ok = page.evaluate(
                    "() => {\n"
                            + "  const needles = ['添加指定操作', '添加运营操作', '添加节点操作', '添加节点'];\n"
                            + "  const cand = [...document.querySelectorAll(\n"
                            + "      'svg text, svg tspan, span, div, button, a, p, foreignObject *')];\n"
                            + "  let best = null;\n"
                            + "  let bestArea = -1;\n"
                            + "  for (const el of cand) {\n"
                            + "    const tx = (el.innerText || el.textContent || '').replace(/\\s+/g, '');\n"
                            + "    if (!tx || tx.length > 80) continue;\n"
                            + "    let hit = false;\n"
                            + "    for (const n of needles) {\n"
                            + "      if (tx.includes(n)) { hit = true; break; }\n"
                            + "    }\n"
                            + "    if (!hit) continue;\n"
                            + "    const r = el.getBoundingClientRect();\n"
                            + "    const area = Math.max(0, r.width) * Math.max(0, r.height);\n"
                            + "    if (area > bestArea) { bestArea = area; best = el; }\n"
                            + "  }\n"
                            + "  if (best) {\n"
                            + "    try {\n"
                            + "      best.scrollIntoView({ block: 'center', inline: 'nearest' });\n"
                            + "      return true;\n"
                            + "    } catch (e) { /* ignore */ }\n"
                            + "  }\n"
                            + "  return false;\n"
                            + "}");
            if (Boolean.TRUE.equals(ok)) {
                log.info("[企业微信工作流] 已将画布「添加」触发文案滚入视口");
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] scrollWorkflowCanvasAddTriggersIntoView: {}", e.getMessage());
        }
    }

    /** 首轮录入后连线入口常只剩 SVG/Canvas 层，HTML span 上已无「添加指定操作」挂载。 */
    private boolean primaryCanvasAddAnchorsMissing(Page page, List<String> triggers) {
        String needle = "添加指定操作";
        if (triggers != null) {
            for (String t : triggers) {
                if (t != null && !t.isBlank()) {
                    needle = t.trim();
                    break;
                }
            }
        }
        String esc = needle.replace("\\", "\\\\").replace("'", "\\'");
        try {
            int n = page.locator(
                    "span:has-text('" + esc + "'), div:has-text('" + esc + "'), "
                            + "[role=\"button\"]:has-text('" + esc + "')"
            ).count();
            return n == 0;
        } catch (Exception e) {
            log.debug("[企业微信工作流] primaryCanvasAddAnchorsMissing: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 连线上的「添加」常在两节点矩形之间的间隙（SVG 层），无 HTML 文案时用几何中点探测点击。
     */
    private boolean tryClickInterNodeEdgeGapsUntilPicker(Page page) {
        try {
            Object raw = page.evaluate(
                    "() => {\n"
                            + "  const nodes = [...document.querySelectorAll('[data-node-id]')];\n"
                            + "  if (nodes.length < 2) return [];\n"
                            + "  const rects = nodes.map(el => {\n"
                            + "    const r = el.getBoundingClientRect();\n"
                            + "    return { r };\n"
                            + "  }).filter(x => x.r.width > 4 && x.r.height > 4);\n"
                            + "  rects.sort((a, b) => a.r.top - b.r.top);\n"
                            + "  const out = [];\n"
                            + "  for (let i = 0; i < rects.length - 1; i++) {\n"
                            + "    const a = rects[i].r, b = rects[i + 1].r;\n"
                            + "    const midY = (a.bottom + b.top) / 2;\n"
                            + "    const cx = ((a.left + a.right) / 2 + (b.left + b.right) / 2) / 2;\n"
                            + "    if (midY >= 8 && midY <= window.innerHeight - 8\n"
                            + "        && cx >= 8 && cx <= window.innerWidth - 8) {\n"
                            + "      out.push({ x: cx, y: midY });\n"
                            + "    }\n"
                            + "  }\n"
                            + "  return out;\n"
                            + "}");
            if (!(raw instanceof List)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) raw;
            if (list.isEmpty()) {
                return false;
            }
            for (int gi = list.size() - 1; gi >= 0; gi--) {
                Object o = list.get(gi);
                if (!(o instanceof Map<?, ?>)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) o;
                Object ox = p.get("x");
                Object oy = p.get("y");
                if (!(ox instanceof Number) || !(oy instanceof Number)) {
                    continue;
                }
                double x = ((Number) ox).doubleValue();
                double y = ((Number) oy).doubleValue();
                page.mouse().move(x, y);
                human.think(page, 120, 260);
                page.mouse().click(x, y);
                log.info("[企业微信工作流] 已点击节点竖向间隙中点 ({}, {}) gapIndex={}/{}",
                        String.format(java.util.Locale.ROOT, "%.0f", x),
                        String.format(java.util.Locale.ROOT, "%.0f", y),
                        list.size() - gi, list.size());
                human.think(page, 350, 750);
                if (isQywxOperationPickerVisible(page)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] tryClickInterNodeEdgeGapsUntilPicker: {}", e.getMessage());
        }
        return false;
    }

    /**
     * SVG / foreignObject / JS / 画布短语占位 —— 与 DOM 文案触发互补；顺序影响命中率。
     */
    private boolean trySvgForeignObjectJsCanvasPlaceholdersChain(Page page) {
        if (tryClickInterNodeEdgeGapsUntilPicker(page)) {
            return true;
        }
        if (tryClickSvgWorkflowAddGlyphs(page)) {
            return true;
        }
        if (tryClickForeignObjectAddPatches(page)) {
            return true;
        }
        if (tryClickJsDiscoveredWorkflowAddHandles(page)) {
            human.think(page, 400, 900);
            if (isQywxOperationPickerVisible(page)) {
                return true;
            }
        }
        return tryClickCanvasAddPlaceholdersUntilPicker(page);
    }

    /** 触发词 + 画布占位 + 兜底按钮的一条完整策略链，成功则「选择需要执行的操作」可见。 */
    private boolean runAddPanelOpenStrategies(Page page, List<String> triggers) {
        scrollWorkflowCanvasAddTriggersIntoView(page);
        if (primaryCanvasAddAnchorsMissing(page, triggers)) {
            log.info("[企业微信工作流] HTML 未挂载画布「添加」锚点，优先 SVG / foreignObject / JS / 画布占位");
            if (trySvgForeignObjectJsCanvasPlaceholdersChain(page)) {
                return true;
            }
        }
        for (String trigger : triggers) {
            if (trigger != null && tryClickInteractiveTextUntilPicker(page, trigger)) {
                return true;
            }
        }
        if (tryClickCanvasAddPlaceholdersUntilPicker(page)) {
            return true;
        }
        if (tryClickSvgWorkflowAddGlyphs(page)) {
            return true;
        }
        if (tryClickForeignObjectAddPatches(page)) {
            return true;
        }
        if (tryClickJsDiscoveredWorkflowAddHandles(page)) {
            human.think(page, 400, 900);
            if (isQywxOperationPickerVisible(page)) {
                return true;
            }
        }
        Locator addBtn = page.locator(
                "button:has-text('添加'), [class*='add'][class*='node'], [class*='add'][class*='operation']"
        );
        if (addBtn.count() > 0 && addBtn.first().isVisible()) {
            reliableClickLocator(page, addBtn.first());
            human.think(page, 350, 800);
            return isQywxOperationPickerVisible(page);
        }
        return isQywxOperationPickerVisible(page);
    }

    /** 企微画布常可平移/缩放：在末端节点附近轻滚轮，便于下一屏的「添加」占位进入命中区。 */
    private void nudgeWorkflowCanvasWithWheel(Page page) {
        try {
            Locator nodes = page.locator("[data-node-id]");
            int n = nodes.count();
            if (n > 0) {
                Locator last = nodes.nth(n - 1);
                BoundingBox box = safeBoundingBox(last);
                if (box != null) {
                    page.mouse().move(box.x + box.width / 2, box.y + box.height / 2);
                    page.mouse().wheel(0, 300);
                    log.info("[企业微信工作流] 已在末端节点处滚轮 nudge");
                    return;
                }
            }
            page.mouse().wheel(0, 360);
            log.debug("[企业微信工作流] 画布无节点句柄时全页滚轮 nudge");
        } catch (Exception e) {
            log.debug("[企业微信工作流] nudgeWorkflowCanvasWithWheel: {}", e.getMessage());
        }
    }

    /**
     * 画布节点、连线标签常在 SVG {@code text}/{@code tspan} 内，普通 div 选择器永远扫不到。
     */
    private boolean tryClickSvgWorkflowAddGlyphs(Page page) {
        try {
            Locator all = page.locator("svg text, svg tspan");
            int n = Math.min(all.count(), 80);
            if (n <= 0) {
                return false;
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            order.sort((a, b) -> {
                BoundingBox ba = safeBoundingBox(all.nth(a));
                BoundingBox bb = safeBoundingBox(all.nth(b));
                if (ba == null && bb == null) {
                    return 0;
                }
                if (ba == null) {
                    return 1;
                }
                if (bb == null) {
                    return -1;
                }
                return Double.compare(bb.y + bb.height, ba.y + ba.height);
            });
            final double topSkip = 88;
            for (int idx : order) {
                Locator el = all.nth(idx);
                try {
                    el.scrollIntoViewIfNeeded();
                } catch (Exception ignored) {
                }
                try {
                    human.think(page, 40, 100);
                } catch (Exception ignored) {
                }
                if (!el.isVisible()) {
                    continue;
                }
                String tx = el.textContent();
                if (tx == null) {
                    continue;
                }
                String compact = tx.replaceAll("\\s+", "").trim();
                if (compact.isEmpty() || compact.length() > 48) {
                    continue;
                }
                boolean hit = compact.contains("添加") || "+".equals(compact) || "＋".equals(compact)
                        || compact.startsWith("+") || compact.startsWith("＋");
                if (!hit) {
                    continue;
                }
                BoundingBox box = safeBoundingBox(el);
                if (box != null && box.y + box.height < topSkip && box.x + box.width < 420) {
                    continue;
                }
                reliableClickLocator(page, el);
                log.info("[企业微信工作流] 已点击 SVG 文案控件: {}", compact.length() > 16 ? compact.substring(0, 16) + "…" : compact);
                human.think(page, 400, 900);
                if (isQywxOperationPickerVisible(page)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] SVG 添加文案: {}", e.getMessage());
        }
        return false;
    }

    /**
     * SVG 内的 HTML 常包在 {@code foreignObject} 里，不走普通 div 树。
     */
    private boolean tryClickForeignObjectAddPatches(Page page) {
        try {
            Locator fos = page.locator("foreignObject");
            int fn = Math.min(fos.count(), 48);
            final double topSkip = 88;
            for (int fi = 0; fi < fn; fi++) {
                Locator inner = fos.nth(fi).locator("button, span, div, a, p");
                int n = Math.min(inner.count(), 28);
                for (int j = n - 1; j >= 0; j--) {
                    Locator el = inner.nth(j);
                    if (!el.isVisible()) {
                        continue;
                    }
                    String tx = el.textContent();
                    if (tx == null) {
                        continue;
                    }
                    String compact = tx.replaceAll("\\s+", "").trim();
                    if (compact.isEmpty() || compact.length() > 44) {
                        continue;
                    }
                    boolean hit = compact.contains("添加") || "+".equals(compact) || "＋".equals(compact)
                            || compact.startsWith("+") || compact.startsWith("＋");
                    if (!hit) {
                        continue;
                    }
                    BoundingBox box = safeBoundingBox(el);
                    if (box != null && box.y + box.height < topSkip) {
                        continue;
                    }
                    reliableClickLocator(page, el);
                    log.info("[企业微信工作流] 已点击 foreignObject 内控件");
                    human.think(page, 400, 900);
                    if (isQywxOperationPickerVisible(page)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] foreignObject 添加: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 部分环境「添加」在连线上或极短文案（+ / 添加）中，Playwright 长选择器点不中；在视口内用 JS 扫可见小控件（偏下优先），
     * 并排除顶栏（y 过小）减少误点列表页按钮。
     */
    private boolean tryClickJsDiscoveredWorkflowAddHandles(Page page) {
        try {
            Object clicked = page.evaluate(
                    "() => {\n"
                            + "const TOP_SKIP = 88;\n"
                            + constScanWorkflowAddCandidates()
                            + "}\n");
            if (Boolean.TRUE.equals(clicked)) {
                log.info("[企业微信工作流] JS 扫描命中可见「添加」/「+」控件并已点击");
                return true;
            }
            return false;
        } catch (Exception e) {
            log.debug("[企业微信工作流] JS 扫描添加控件: {}", e.getMessage());
            return false;
        }
    }

    /** 嵌入 {@link #tryClickJsDiscoveredWorkflowAddHandles} 的内联脚本（避免超长 escape）。 */
    private static String constScanWorkflowAddCandidates() {
        return ""
                + "const candidates = [\n"
                + "  ...document.querySelectorAll(\n"
                + "      'button,[role=\\\"button\\\"],div.cursor-pointer,span,a'),\n"
                + "  ...document.querySelectorAll('svg text, svg tspan'),\n"
                + "  ...document.querySelectorAll(\n"
                + "      'foreignObject button, foreignObject span, foreignObject div, foreignObject a'),\n"
                + "];\n"
                + "const scored = [];\n"
                + "for (const el of candidates) {\n"
                + "  let t = (el.textContent || '').replace(/\\s+/g, '').trim();\n"
                + "  if (!t || t.length > 36) continue;\n"
                + "  const r = el.getBoundingClientRect();\n"
                + "  if (r.width < 2 || r.height < 2 || r.top < TOP_SKIP) continue;\n"
                + "  const hit = t.includes('添加指定') || t.includes('添加运营') || t.includes('添加节点')\n"
                + "      || (t.length <= 10 && t.includes('添加'))\n"
                + "      || t === '+' || t === '＋' || /^[+＋]/.test(t);\n"
                + "  if (!hit) continue;\n"
                + "  scored.push({ el, bottom: r.bottom });\n"
                + "}\n"
                + "scored.sort((a, b) => b.bottom - a.bottom);\n"
                + "for (const { el } of scored.slice(0, 12)) {\n"
                + "  try {\n"
                + "    el.click();\n"
                + "    return true;\n"
                + "  } catch (e) { /* next */ }\n"
                + "}\n"
                + "return false;\n";
    }

    /**
     * 画布「+ 添加指定操作」占位：线性追加节点时应优先点<strong>最后一个</strong>可见占位（靠近流程末端），
     * 否则重复点第一个边的「+」无法插入后续节点。
     */
    private boolean tryClickCanvasAddPlaceholdersUntilPicker(Page page) {
        List<String> phrases = new ArrayList<>();
        phrases.add("添加指定操作");
        if (workflowEditorNodeMapping != null) {
            for (String t : workflowEditorNodeMapping.getAddPanelTriggers()) {
                if (t != null && !t.isBlank() && !phrases.contains(t.trim())) {
                    phrases.add(t.trim());
                }
            }
        }
        phrases.add("添加运营操作");

        for (String phrase : phrases) {
            if (phrase == null || phrase.isBlank()) {
                continue;
            }
            if (tryClickCanvasPhraseAllReverseUntilPicker(page, phrase)) {
                return true;
            }
            if (tryClickCanvasPhraseSpatialBottomFirstUntilPicker(page, phrase)) {
                return true;
            }
        }
        return isQywxOperationPickerVisible(page);
    }

    private BoundingBox safeBoundingBox(Locator el) {
        try {
            return el.boundingBox();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 画布纵向流程：视觉上越靠下的「添加」边越接近末端节点；DOM 顺序未必一致，故按 bounding box 从下往上试。
     */
    private boolean tryClickCanvasPhraseSpatialBottomFirstUntilPicker(Page page, String phrase) {
        try {
            Locator byLine = page.getByText(phrase, new Page.GetByTextOptions().setExact(false));
            int n = byLine.count();
            if (n <= 1) {
                return false;
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                order.add(i);
            }
            final String needle = phrase.replace("\n", "");
            order.sort((a, b) -> {
                BoundingBox ba = safeBoundingBox(byLine.nth(a));
                BoundingBox bb = safeBoundingBox(byLine.nth(b));
                if (ba == null && bb == null) {
                    return 0;
                }
                if (ba == null) {
                    return 1;
                }
                if (bb == null) {
                    return -1;
                }
                double baBottom = ba.y + ba.height;
                double bbBottom = bb.y + bb.height;
                int cmp = Double.compare(bbBottom, baBottom);
                if (cmp != 0) {
                    return cmp;
                }
                return Double.compare(bb.x, ba.x);
            });
            for (int idx : order) {
                Locator el = byLine.nth(idx);
                if (!el.isVisible()) {
                    continue;
                }
                String tx = el.textContent();
                if (tx == null || !tx.replace("\n", "").contains(needle)) {
                    continue;
                }
                reliableClickLocator(page, el);
                log.info("[企业微信工作流] 画布占位 spatial phrase={} idx={}", phrase, idx);
                human.think(page, 400, 900);
                if (isQywxOperationPickerVisible(page)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] canvas spatial phrase={} : {}", phrase, e.getMessage());
        }
        return false;
    }

    private boolean tryClickCanvasPhraseAllReverseUntilPicker(Page page, String phrase) {
        try {
            Locator byLine = page.getByText(phrase, new Page.GetByTextOptions().setExact(false));
            int n = byLine.count();
            for (int i = n - 1; i >= 0; i--) {
                Locator el = byLine.nth(i);
                if (!el.isVisible()) {
                    continue;
                }
                String tx = el.textContent();
                if (tx == null || !tx.replace("\n", "").contains(phrase.replace("\n", ""))) {
                    continue;
                }
                reliableClickLocator(page, el);
                log.info("[企业微信工作流] 已画布占位点击 phrase={} index={}/{}", phrase, i, n);
                human.think(page, 400, 900);
                if (isQywxOperationPickerVisible(page)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] 画布占位 phrase={} : {}", phrase, e.getMessage());
        }
        return false;
    }

    /** 收尾或下一节点前：收起可能挡在画布上的「设置变量」等配置层，以便再次点到「添加指定操作」。 */
    private void tryDismissBlockingConfigModalsBeforeCanvasAdd(Page page) {
        try {
            human.think(page, 100, 240);
            if (isWorkflowSetVariableModalVisible(page)) {
                Locator modal = resolveWorkflowEditorModalScope(page, "设置变量");
                if (modal.count() > 0 && modal.first().isVisible()) {
                    tryClickModalPrimaryConfirm(modal.first(), page);
                    human.think(page, 200, 450);
                }
                if (isWorkflowSetVariableModalVisible(page)) {
                    page.keyboard().press("Escape");
                    human.think(page, 220, 500);
                }
                if (isWorkflowSetVariableModalVisible(page)) {
                    page.keyboard().press("Escape");
                    human.think(page, 220, 500);
                }
                if (isWorkflowSetVariableModalVisible(page)) {
                    log.warn("[企业微信工作流] 「设置变量」弹层仍在，可能挡住后续「添加指定操作」");
                }
            }
            // 操作选择器未弹出时，Escape 用于收起抽屉/右侧配置层（变量填写失败时常残留）
            if (!isQywxOperationPickerVisible(page)) {
                page.keyboard().press("Escape");
                human.think(page, 160, 380);
                page.keyboard().press("Escape");
                human.think(page, 160, 380);
            }
            blurWorkflowCanvasSelection(page);
        } catch (Exception e) {
            log.debug("[企业微信工作流] dismiss modal before canvas add: {}", e.getMessage());
        }
    }

    /**
     * 选中节点或右侧属性打开时，连线上的「添加指定操作」常从 DOM 隐藏；点画布空白（SVG）取消选中以恢复入口。
     */
    private void blurWorkflowCanvasSelection(Page page) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> pt = (Map<String, Object>) page.evaluate(
                    "() => {\n"
                            + "  const svgs = [...document.querySelectorAll('svg')];\n"
                            + "  let best = null;\n"
                            + "  let bestArea = 0;\n"
                            + "  for (const svg of svgs) {\n"
                            + "    const r = svg.getBoundingClientRect();\n"
                            + "    const a = Math.max(0, r.width) * Math.max(0, r.height);\n"
                            + "    if (a > bestArea && r.width >= 160 && r.height >= 100) {\n"
                            + "      bestArea = a;\n"
                            + "      best = svg;\n"
                            + "    }\n"
                            + "  }\n"
                            + "  if (!best) return { ok: false };\n"
                            + "  const r = best.getBoundingClientRect();\n"
                            + "  const x = r.left + Math.min(72, r.width * 0.14);\n"
                            + "  const y = r.top + r.height * 0.44;\n"
                            + "  return { ok: true, x, y };\n"
                            + "}");
            if (pt != null && Boolean.TRUE.equals(pt.get("ok"))) {
                double x = ((Number) pt.get("x")).doubleValue();
                double y = ((Number) pt.get("y")).doubleValue();
                page.mouse().move(x, y);
                human.think(page, 90, 200);
                page.mouse().click(x, y);
                log.info("[企业微信工作流] 已点击画布 SVG 空白以取消节点选中");
            }
            human.think(page, 220, 520);
        } catch (Exception e) {
            log.debug("[企业微信工作流] blurWorkflowCanvasSelection: {}", e.getMessage());
        }
    }

    /**
     * 线性追加节点时，末端「添加指定操作」常在视口外；把<b>最靠下的画布节点</b>滚入视口并尝试滚动画布滚动容器。
     */
    private void scrollWorkflowCanvasTowardLatestEdge(Page page) {
        try {
            Object ok = page.evaluate(
                    "() => {\n"
                            + "  const nodes = [...document.querySelectorAll('[data-node-id]')];\n"
                            + "  if (nodes.length > 0) {\n"
                            + "    let best = null;\n"
                            + "    let bestBottom = -1;\n"
                            + "    for (const el of nodes) {\n"
                            + "      const r = el.getBoundingClientRect();\n"
                            + "      if (r.bottom > bestBottom) { bestBottom = r.bottom; best = el; }\n"
                            + "    }\n"
                            + "    if (best) {\n"
                            + "      try {\n"
                            + "        best.scrollIntoView({ block: 'center', inline: 'nearest' });\n"
                            + "        return true;\n"
                            + "      } catch (e) { /* ignore */ }\n"
                            + "    }\n"
                            + "  }\n"
                            + "  const hosts = [...document.querySelectorAll(\n"
                            + "    '[class*=\"canvas\"],[class*=\"Canvas\"],[class*=\"viewport\"],[class*=\"Viewport\"]')];\n"
                            + "  for (const h of hosts) {\n"
                            + "    const cs = window.getComputedStyle(h);\n"
                            + "    if (cs.overflowY === 'auto' || cs.overflowY === 'scroll' || cs.overflow === 'auto') {\n"
                            + "      try { h.scrollTop = h.scrollHeight; } catch (e) {}\n"
                            + "    }\n"
                            + "  }\n"
                            + "  return true;\n"
                            + "}");
            if (Boolean.TRUE.equals(ok)) {
                log.info("[企业微信工作流] 已滚动画布至末端节点附近");
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] scrollWorkflowCanvasTowardLatestEdge: {}", e.getMessage());
        }
    }

    private boolean isQywxOperationPickerVisible(Page page) {
        try {
            Locator t = page.getByText("选择需要执行的操作", new Page.GetByTextOptions().setExact(false));
            return t.count() > 0 && t.first().isVisible();
        } catch (Exception e) {
            return false;
        }
    }

    private void reliableClickLocator(Page page, Locator target) {
        Locator el = target.first();
        try {
            el.scrollIntoViewIfNeeded();
        } catch (Exception ignored) {
        }
        try {
            human.naturalClick(page, el);
            return;
        } catch (Exception e) {
            log.debug("[企业微信工作流] 自然点击失败，尝试 force: {}", e.getMessage());
        }
        try {
            el.click(new Locator.ClickOptions().setForce(true).setTimeout(10000));
        } catch (Exception e2) {
            log.debug("[企业微信工作流] force 点击失败，尝试坐标: {}", e2.getMessage());
            try {
                BoundingBox box = el.boundingBox();
                if (box != null) {
                    page.mouse().click(box.x + box.width / 2, box.y + box.height / 2);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private boolean tryClickVisibleMenuItemWithAliases(Page page, String primary) {
        for (String key : expandMenuAliasCandidates(primary)) {
            if (tryClickVisibleMenuItemContaining(page, key)) {
                return true;
            }
        }
        return false;
    }

    private List<String> expandMenuAliasCandidates(String primary) {
        List<String> out = new ArrayList<>();
        if (primary == null || primary.isBlank()) {
            return out;
        }
        String t = primary.trim();
        out.add(t);
        if (t.contains("HTTP")) {
            out.add("HTTP Request");
            out.add("HTTP");
        }
        // 真实企微面板多为「大模型问答 / 知识库问答」；旧 FBS-IR/草稿 可能写「回答 / 集」
        if (t.contains("大模型") || "大模型回答".equals(t) || "大模型问答".equals(t)) {
            out.add("大模型问答");
            out.add("大模型回答");
            out.add("大模型");
        }
        if (t.contains("知识") || "知识集问答".equals(t) || "知识库问答".equals(t)) {
            out.add("知识库问答");
            out.add("知识集问答");
            out.add("知识库");
        }
        if ("设置变量".equals(t) || t.contains("变量")) {
            out.add("变量");
        }
        return out;
    }

    /** 在浮动层/菜单中点击文案包含 {@code substring} 的可见项（适配企微菜单文案变体） */
    private boolean tryClickVisibleMenuItemContaining(Page page, String substring) {
        if (substring == null || substring.isBlank()) {
            return false;
        }
        String key = substring.trim();
        // 新版编辑器：弹出「选择需要执行的操作」面板，选项多为卡片/区块，未必带 role=menuitem
        if (tryClickInQywxOperationPicker(page, key)) {
            return true;
        }
        Locator candidates = page.locator(
            "[role='menuitem'], [role='option'], " +
            ".t-dropdown__item, .t-menu__item, " +
            "[class*='dropdown'] li, [class*='Dropdown'] [class*='item']"
        );
        int n = candidates.count();
        for (int i = 0; i < n; i++) {
            Locator el = candidates.nth(i);
            try {
                if (!el.isVisible()) {
                    continue;
                }
                String tx = el.textContent();
                if (tx != null && tx.contains(key)) {
                    human.naturalClick(page, el);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * 在「选择需要执行的操作」类弹层内点击含指定文案的项（限定在 dialog 内，避免点到背后画布）。
     * <p>
     * 选项多为图标+文案的卡片，getByText 常命中内层 leaf，无有效点击热区；故向上解析到合适尺寸的祖先再点。
     */
    private boolean tryClickInQywxOperationPicker(Page page, String key) {
        try {
            Locator title = page.getByText("选择需要执行的操作", new Page.GetByTextOptions().setExact(false));
            if (title.count() == 0 || !title.first().isVisible()) {
                return false;
            }
            Locator titleHit = page.getByText("选择需要执行的操作", new Page.GetByTextOptions().setExact(false)).first();
            List<Locator> roots = new ArrayList<>();
            roots.add(page.locator("[role='dialog']").filter(
                    new Locator.FilterOptions().setHas(titleHit)));
            roots.add(page.locator("[class*='t-dialog']").filter(
                    new Locator.FilterOptions().setHas(titleHit)));
            roots.add(page.locator("[class*='Dialog']").filter(
                    new Locator.FilterOptions().setHas(titleHit)));
            roots.add(page.locator("[class*='popover'], [class*='Popover'], [class*='popup'], [class*='Popup']")
                    .filter(new Locator.FilterOptions().setHas(titleHit)));
            // 部分构建页无 role=dialog，仅有固定定位的浮层
            try {
                Locator xpathScope = page.locator(
                        "xpath=(//*[contains(.,'选择需要执行的操作')])[1]/ancestor::*"
                                + "[@role='dialog' or contains(@class,'dialog') or contains(@class,'popover') "
                                + "or contains(@class,'Popup') or contains(@class,'portal') or contains(@class,'Portal')][1]");
                if (xpathScope.count() > 0) {
                    roots.add(xpathScope);
                }
            } catch (Exception ignored) {
            }
            try {
                // 最近一层常见浮层祖先（标题节点向上），无 role=dialog 时仍可能包住整块菜单
                Locator titleAncestor = titleHit.locator(
                        "xpath=ancestor::*[contains(@class,'fixed') or contains(@class,'absolute') "
                                + "or @role='dialog'][1]");
                if (titleAncestor.count() > 0) {
                    roots.add(titleAncestor.first());
                }
            } catch (Exception ignored) {
            }
            for (Locator root : roots) {
                if (root.count() == 0) {
                    continue;
                }
                Locator scope = root.first();
                if (!scope.isVisible()) {
                    continue;
                }
                // 策略 A：卡片行/按钮等可点容器上带完整文案（企微常用 div 包一行，非 menuitem）
                Locator rowLike = scope.locator(
                        "button, [role='button'], [role='menuitem'], "
                                + "[class*='grid'] > div, [class*='Grid'] > div, "
                                + "[class*='item'], [class*='Item'], [class*='card'], [class*='Card'], "
                                + "[class*='Cell'], [class*='cell'], "
                                + "div[class*='cursor-pointer'], a");
                int rows = rowLike.count();
                int limit = Math.min(rows, 80);
                for (int r = 0; r < limit; r++) {
                    Locator row = rowLike.nth(r);
                    if (!row.isVisible()) {
                        continue;
                    }
                    String rtx = row.textContent();
                    if (rtx == null || !rtx.contains(key) || rtx.length() > 180) {
                        continue;
                    }
                    if (rtx.contains("选择需要执行的操作") && rtx.length() > 40) {
                        continue;
                    }
                    Locator toClick = resolveQywxPickerRowForClick(page, row, key);
                    reliableClickLocator(page, toClick);
                    log.info("[企业微信工作流] 在「选择需要执行的操作」内点击(行容器): {}", key);
                    return true;
                }
                // 策略 B：文本叶子 + 上溯到合适热区
                Locator matches = scope.getByText(key, new Locator.GetByTextOptions().setExact(false));
                int m = matches.count();
                for (int i = 0; i < m; i++) {
                    Locator c = matches.nth(i);
                    if (!c.isVisible()) {
                        continue;
                    }
                    String tx = c.textContent();
                    if (tx == null || !tx.contains(key)) {
                        continue;
                    }
                    if (tx.length() > 200) {
                        continue;
                    }
                    Locator toClick = resolveQywxPickerRowForClick(page, c, key);
                    reliableClickLocator(page, toClick);
                    log.info("[企业微信工作流] 在「选择需要执行的操作」内点击(文本上溯): {}", key);
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] 操作选择面板点击: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 从命中节点上溯，找到包含 key、且 bbox 像「菜单卡片一行」的祖先，避免点到仅有几个像素高的纯文本节点。
     */
    private Locator resolveQywxPickerRowForClick(Page page, Locator hit, String key) {
        Locator cur = hit;
        int vw = 1920;
        try {
            com.microsoft.playwright.options.ViewportSize vs = page.viewportSize();
            if (vs != null) {
                vw = vs.width;
            }
        } catch (Exception ignored) {
        }
        for (int depth = 0; depth < 14; depth++) {
            try {
                if (!cur.isVisible()) {
                    break;
                }
                String ctx = cur.textContent();
                if (ctx == null || !ctx.contains(key)) {
                    break;
                }
                BoundingBox box = cur.boundingBox();
                if (box != null) {
                    boolean rowLike = box.width >= 72 && box.width <= vw * 0.92
                            && box.height >= 22 && box.height <= 200;
                    boolean notWholePanel = box.width < vw * 0.88 || box.height < 400;
                    if (rowLike && notWholePanel && ctx.length() < 220) {
                        return cur;
                    }
                }
            } catch (Exception ignored) {
            }
            Locator parent = cur.locator("xpath=parent::*");
            if (parent.count() == 0) {
                break;
            }
            cur = parent;
        }
        return hit;
    }

    private boolean clickFirstVisibleMenuFallback(Page page) {
        String[] quick = {"大模型问答", "知识库问答", "HTTP请求", "设置变量", "大模型回答", "知识集问答"};
        for (String q : quick) {
            if (tryClickInQywxOperationPicker(page, q)) {
                return true;
            }
        }
        Locator menus = page.locator(
            "[role='menuitem'], .t-dropdown__item, [class*='dropdown'] [class*='item'], " +
            "[role='dialog'] [class*='item'], [class*='node-item'], [class*='NodeItem']"
        );
        int n = menus.count();
        for (int i = 0; i < n; i++) {
            Locator mi = menus.nth(i);
            try {
                if (mi.isVisible()) {
                    String tx = mi.textContent();
                    if (tx != null && !tx.isBlank() && tx.length() < 40) {
                        human.naturalClick(page, mi);
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private boolean tryRenameEditorTitle(Page page, String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String[] selectors = {
            "input[placeholder*='名称']",
            "input[placeholder*='工作流']",
            "input[placeholder*='title']",
            "[class*='title'] input",
            "[class*='name'] input"
        };
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    human.humanType(page, loc.first(), title);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        // 标题常是静态文本：先点标题区域再找输入框
        String[] titleHints = {"未命名工作流", "工作流", "名称"};
        for (String hint : titleHints) {
            try {
                Locator t = page.getByText(hint, new Page.GetByTextOptions().setExact(false));
                if (t.count() > 0 && t.first().isVisible()) {
                    human.naturalClick(page, t.first());
                    human.think(page, 300, 700);
                }
            } catch (Exception ignored) {
            }
        }
        try {
            Locator anyInput = page.locator("input:visible, textarea:visible");
            for (int i = 0; i < anyInput.count(); i++) {
                Locator input = anyInput.nth(i);
                String ph = String.valueOf(input.getAttribute("placeholder"));
                if (ph.contains("搜索") || ph.contains("检索")) {
                    continue;
                }
                human.humanType(page, input, title);
                return true;
            }
        } catch (Exception ignored) {
        }
        // JS 兜底：顶部区域挑可能的标题输入并触发 React 事件
        try {
            Object ok = page.evaluate(
                "(val) => {\n" +
                "  const top = document.querySelector('header, [class*=\"header\"], [class*=\"top\"], body') || document.body;\n" +
                "  const cands = [...top.querySelectorAll('input, textarea')].filter(el => {\n" +
                "    const cs = window.getComputedStyle(el);\n" +
                "    if (cs.display === 'none' || cs.visibility === 'hidden') return false;\n" +
                "    const r = el.getBoundingClientRect();\n" +
                "    if (r.width < 40 || r.height < 16) return false;\n" +
                "    const ph = (el.getAttribute('placeholder') || '').toLowerCase();\n" +
                "    return !ph.includes('search') && !ph.includes('搜索') && !ph.includes('检索');\n" +
                "  });\n" +
                "  for (const el of cands) {\n" +
                "    try {\n" +
                "      el.focus();\n" +
                "      const proto = el.tagName.toLowerCase() === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;\n" +
                "      const desc = Object.getOwnPropertyDescriptor(proto, 'value');\n" +
                "      if (desc && desc.set) desc.set.call(el, val); else el.value = val;\n" +
                "      el.dispatchEvent(new Event('input', { bubbles: true }));\n" +
                "      el.dispatchEvent(new Event('change', { bubbles: true }));\n" +
                "      return true;\n" +
                "    } catch (e) {}\n" +
                "  }\n" +
                "  return false;\n" +
                "}",
                title
            );
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String summarizeEndOutputs(com.alibaba.fastjson2.JSONObject draft) {
        com.alibaba.fastjson2.JSONArray outputs = draft.getJSONArray("endOutputs");
        if (outputs == null || outputs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < outputs.size(); i++) {
            com.alibaba.fastjson2.JSONObject out = outputs.getJSONObject(i);
            if (out == null) {
                continue;
            }
            String name = out.getString("name");
            String type = out.getString("type");
            String source = out.getString("source");
            if (name == null || name.isBlank()) {
                continue;
            }
            sb.append("- ").append(name);
            if (type != null && !type.isBlank()) {
                sb.append(" (").append(type).append(")");
            }
            if (source != null && !source.isBlank()) {
                sb.append(" <= ").append(source);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String summarizeInvokeHints(com.alibaba.fastjson2.JSONObject draft) {
        com.alibaba.fastjson2.JSONArray hints = draft.getJSONArray("invokeHints");
        if (hints == null || hints.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hints.size(); i++) {
            com.alibaba.fastjson2.JSONObject h = hints.getJSONObject(i);
            if (h == null) {
                continue;
            }
            String tip = h.getString("tip");
            if (tip == null || tip.isBlank()) {
                continue;
            }
            sb.append("- ").append(tip).append('\n');
        }
        return sb.toString().trim();
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_VALIDATE：工作流编辑器校验（检查/调试前置）
    // =========================================================================

    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_VALIDATE",
        description = "打开工作流编辑器并采集检查结果（检查/发布前置）",
        timeout = 120000L
    )
    public void handleWorkflowValidate(EngineMessage message) {
        String userId       = message.getUserId();
        String requestId    = extractRoutingField(message, "requestId");
        String indexStr     = extractPayload(message, "workflowIndex");
        String workflowName = extractPayload(message, "workflowName");
        String workflowCreator = extractPayload(message, "workflowCreator");

        int targetIndex = -1;
        if (indexStr != null && !indexStr.isBlank()) {
            try { targetIndex = Integer.parseInt(indexStr.trim()); } catch (Exception ignored) {}
        }

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;
        try {
            SessionPage sp = acquireSessionPageWithRecovery(userId);
            session = sp.session();
            Page page = sp.page();
            session.touch();

            navigateToWorkflowTab(page, userId);
            String loginStatus = loginUtil.checkLoginStatus(page, false);
            if ("false".equals(loginStatus)) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }

            human.think(page, 800, 1500);
            Locator rows = page.locator(WORKFLOW_ROW_SELECTOR);
            int rowCount = rows.count();
            if (rowCount == 0) {
                sendErrorResult(message, "工作流列表为空，无法校验");
                return;
            }
            int resolvedIndex = resolveWorkflowIndex(rows, rowCount, targetIndex, workflowName, workflowCreator);
            if (resolvedIndex < 0) {
                sendErrorResult(message, "未找到目标工作流：index=" + targetIndex + ", name=" + workflowName
                    + ", creator=" + workflowCreator);
                return;
            }

            Locator targetRow = rows.nth(resolvedIndex);
            human.naturalScroll(page, resolvedIndex * 50, 600);
            human.think(page, 400, 900);

            Locator editBtn = targetRow.locator("button:has-text('编辑'), a:has-text('编辑'), [class*='edit']");
            if (editBtn.count() == 0) {
                BoundingBox rowBox = targetRow.boundingBox();
                if (rowBox != null) {
                    human.moveMouse(page, rowBox.x + rowBox.width / 2, rowBox.y + rowBox.height / 2);
                    human.think(page, 300, 700);
                }
                editBtn = targetRow.locator("button:has-text('编辑'), a:has-text('编辑'), [class*='edit']");
            }
            if (editBtn.count() > 0) {
                human.naturalClick(page, editBtn.first());
            } else {
                human.naturalClick(page, targetRow);
            }
            human.think(page, 2000, 3500);

            Page editorPage = page;
            var pages = page.context().pages();
            if (pages.size() > 1) {
                editorPage = pages.get(pages.size() - 1);
                human.think(editorPage, 1500, 3000);
            }
            gateIfLoginQrBlocking(editorPage);

            boolean editorReady = waitForEditorReady(editorPage);
            boolean checkClicked = tryClickAny(editorPage, "检查", "校验", "校对");
            human.think(editorPage, 800, 1800);
            boolean debugVisible = hasAnyVisible(editorPage, "button:has-text('调试'), button:has-text('发布'), button:has-text('保存')");
            List<Map<String, Object>> issues = collectValidationIssues(editorPage);
            Map<String, Object> validationSummary = summarizeValidationIssues(issues, editorReady, checkClicked, debugVisible);

            String screenshotUrl = captureAndUpload(editorPage, userId,
                "qyweixin_wf_validate_" + System.currentTimeMillis());
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("targetIndex", resolvedIndex);
            resultData.put("editorUrl", editorPage.url());
            resultData.put("editorReady", editorReady);
            resultData.put("checkClicked", checkClicked);
            resultData.put("debugVisible", debugVisible);
            resultData.put("issues", issues);
            resultData.put("summary", validationSummary);
            resultData.put("checks", defaultValidationChecklist(editorReady, checkClicked, issues));
            resultData.put("screenshotUrl", screenshotUrl);
            resultData.put("timestamp", System.currentTimeMillis());
            recordQywxAutomation("QYWEIXIN_WORKFLOW_VALIDATE", message, resultData);
            sendResult(message, resultData);
        } catch (Exception e) {
            log.error("[企业微信工作流校验] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            recordQywxAutomation("QYWEIXIN_WORKFLOW_VALIDATE", message, Map.of(
                    "ok", false,
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            ));
            sendErrorResult(message, "工作流校验失败: " + e.getMessage());
        } finally {
            releaseSession(session, "工作流校验");
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_LEARNING_FEEDBACK：克隆记忆纠偏（分支不可穷举时靠统计学习）
    // =========================================================================

    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_LEARNING_FEEDBACK",
        description = "合并人工纠偏样本，强化企微克隆的菜单/面板记忆",
        timeout = 60000L
    )
    public void handleWorkflowLearningFeedback(EngineMessage message) {
        String userId = message.getUserId();
        try {
            List<Map<String, Object>> corrections = readLearningCorrectionsFromMessage(message);
            if (corrections.isEmpty()) {
                sendErrorResult(message, "缺少 payload.learningCorrections（非空数组）");
                return;
            }
            workflowCloneLearning.mergeFeedback(corrections);
            Map<String, Object> data = new HashMap<>();
            data.put("merged", corrections.size());
            data.put("cloneLearningStats", workflowCloneLearning.memoryStats());
            data.put("timestamp", System.currentTimeMillis());
            sendResult(message, data);
            log.info("[QywxCloneLearning] 已合并 {} 条纠偏 - 用户: {}", corrections.size(), userId);
        } catch (Exception e) {
            sendErrorResult(message, "LEARNING_FEEDBACK 失败: " + e.getMessage());
        }
    }

    /**
     * 从 HTTP/WS 消息的 payload.learningCorrections 读取纠偏列表并写入记忆（若存在）。
     */
    private void applyLearningCorrectionsFromPayload(EngineMessage message) {
        try {
            List<Map<String, Object>> corrections = readLearningCorrectionsFromMessage(message);
            if (!corrections.isEmpty()) {
                workflowCloneLearning.mergeFeedback(corrections);
                log.debug("[QywxCloneLearning] 本次请求合并纠偏 {} 条", corrections.size());
            }
        } catch (Exception e) {
            log.debug("[QywxCloneLearning] applyLearningCorrectionsFromPayload: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> readLearningCorrectionsFromMessage(EngineMessage message) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            String raw = message.getRawJson();
            if (raw == null || raw.isBlank()) {
                return out;
            }
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(raw);
            if (root == null) {
                return out;
            }
            com.alibaba.fastjson2.JSONObject payload = root.getJSONObject("payload");
            if (payload == null) {
                return out;
            }
            com.alibaba.fastjson2.JSONArray arr = payload.getJSONArray("learningCorrections");
            if (arr == null || arr.isEmpty()) {
                return out;
            }
            for (int i = 0; i < arr.size(); i++) {
                com.alibaba.fastjson2.JSONObject o = arr.getJSONObject(i);
                if (o == null) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (String k : o.keySet()) {
                    row.put(k, o.get(k));
                }
                out.add(row);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_EXPORT_FULL：完整采样（画布节点序 + 每节点配置面板字段）
    // =========================================================================

    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_EXPORT_FULL",
        description = "完整导出企微工作流：逐节点点选并采集配置面板表单字段（用于等价克隆）",
        timeout = 600000L
    )
    public void handleWorkflowExportFull(EngineMessage message) {
        String userId = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");
        String indexStr = extractPayload(message, "workflowIndex");
        String workflowName = extractPayload(message, "workflowName");
        String workflowCreator = extractPayload(message, "workflowCreator");
        int targetIndex = -1;
        if (indexStr != null && !indexStr.isBlank()) {
            try { targetIndex = Integer.parseInt(indexStr.trim()); } catch (Exception ignored) {}
        }
        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;
        try {
            SessionPage sp = acquireSessionPageWithRecovery(userId);
            session = sp.session();
            Page page = sp.page();
            session.touch();
            navigateToWorkflowTab(page, userId);
            if ("false".equals(loginUtil.checkLoginStatus(page, false))) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }
            human.think(page, 800, 1500);
            Locator rows = page.locator(WORKFLOW_ROW_SELECTOR);
            int rowCount = rows.count();
            if (rowCount == 0) {
                sendErrorResult(message, "工作流列表为空");
                return;
            }
            int resolvedIndex = resolveWorkflowIndex(rows, rowCount, targetIndex, workflowName, workflowCreator);
            if (resolvedIndex < 0) {
                sendErrorResult(message, "未找到目标工作流");
                return;
            }
            Page editorPage = openWorkflowEditorFromListRow(page, rows.nth(resolvedIndex));
            gateIfLoginQrBlocking(editorPage);
            boolean editorReady = waitForEditorReady(editorPage);
            human.warmUp(editorPage, HumanBehaviorUtil.Intensity.LOW);
            applyLearningCorrectionsFromPayload(message);
            Map<String, Object> exportBundle = exportFullWorkflowPanels(editorPage, userId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> exportRows =
                    (List<Map<String, Object>>) exportBundle.getOrDefault("rows", List.of());
            String screenshotUrl = captureAndUpload(editorPage, userId,
                    "qyweixin_wf_export_full_" + System.currentTimeMillis());
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("editorUrl", editorPage.url());
            resultData.put("editorReady", editorReady);
            resultData.put("targetIndex", resolvedIndex);
            resultData.put("exportRows", exportRows);
            resultData.put("exportCount", exportRows.size());
            resultData.put("graphHints", exportBundle.get("graphHints"));
            resultData.put("cloneLearningStats", workflowCloneLearning.memoryStats());
            resultData.put("workflowDraftSkeleton", buildWorkflowCloneDraftFromExports(
                "（请改名）克隆草稿-" + System.currentTimeMillis(), exportRows));
            resultData.put("screenshotUrl", screenshotUrl);
            resultData.put("timestamp", System.currentTimeMillis());
            recordQywxAutomation("QYWEIXIN_WORKFLOW_EXPORT_FULL", message, resultData);
            sendResult(message, resultData);
        } catch (Exception e) {
            log.error("[EXPORT_FULL] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "EXPORT_FULL 失败: " + e.getMessage());
        } finally {
            releaseSession(session, "EXPORT_FULL");
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_PUBLISH：编辑器内发布
    // =========================================================================

    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_PUBLISH",
        description = "在工作流编辑器内点击「发布」并尽量确认（需已能编辑并校验通过）",
        timeout = 180000L
    )
    public void handleWorkflowPublish(EngineMessage message) {
        String userId = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");
        boolean skipNav = parsePayloadBoolean(message, "skipNavigation", false);
        String indexStr = extractPayload(message, "workflowIndex");
        String workflowName = extractPayload(message, "workflowName");
        String workflowCreator = extractPayload(message, "workflowCreator");
        int targetIndex = -1;
        if (indexStr != null && !indexStr.isBlank()) {
            try { targetIndex = Integer.parseInt(indexStr.trim()); } catch (Exception ignored) {}
        }
        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;
        try {
            SessionPage sp = acquireSessionPageWithRecovery(userId);
            session = sp.session();
            Page page = sp.page();
            session.touch();
            Page editorPage = page;
            if (!skipNav) {
                navigateToWorkflowTab(page, userId);
                if ("false".equals(loginUtil.checkLoginStatus(page, false))) {
                    sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                    return;
                }
                human.think(page, 800, 1500);
                Locator rows = page.locator(WORKFLOW_ROW_SELECTOR);
                int rowCount = rows.count();
                if (rowCount == 0) {
                    sendErrorResult(message, "工作流列表为空");
                    return;
                }
                int resolvedIndex = resolveWorkflowIndex(rows, rowCount, targetIndex, workflowName, workflowCreator);
                if (resolvedIndex < 0) {
                    sendErrorResult(message, "未找到目标工作流");
                    return;
                }
                editorPage = openWorkflowEditorFromListRow(page, rows.nth(resolvedIndex));
            }
            gateIfLoginQrBlocking(editorPage);
            waitForEditorReady(editorPage);
            boolean published = tryPublishWorkflow(editorPage);
            String screenshotUrl = captureAndUpload(editorPage, userId,
                    "qyweixin_wf_publish_" + System.currentTimeMillis());
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("editorUrl", editorPage.url());
            resultData.put("published", published);
            resultData.put("screenshotUrl", screenshotUrl);
            resultData.put("timestamp", System.currentTimeMillis());
            recordQywxAutomation("QYWEIXIN_WORKFLOW_PUBLISH", message, resultData);
            sendResult(message, resultData);
        } catch (Exception e) {
            log.error("[WORKFLOW_PUBLISH] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "发布失败: " + e.getMessage());
        } finally {
            releaseSession(session, "WORKFLOW_PUBLISH");
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_CLONE_PUBLISH：等价克隆（导出→新建→录入→面板回放→保存→发布）
    // =========================================================================

    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_CLONE_PUBLISH",
        description = "完整克隆工作流并发布：EXPORT_FULL + IMPORT + 面板回放 + 保存/发布",
        timeout = 900000L
    )
    public void handleWorkflowClonePublish(EngineMessage message) {
        String userId = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");
        String newTitle = extractPayload(message, "newWorkflowTitle");
        if (newTitle == null || newTitle.isBlank()) {
            sendErrorResult(message, "缺少 payload.newWorkflowTitle");
            return;
        }
        String indexStr = extractPayload(message, "workflowIndex");
        String workflowName = extractPayload(message, "workflowName");
        String workflowCreator = extractPayload(message, "workflowCreator");
        int targetIndex = -1;
        if (indexStr != null && !indexStr.isBlank()) {
            try { targetIndex = Integer.parseInt(indexStr.trim()); } catch (Exception ignored) {}
        }
        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;
        try {
            SessionPage sp = acquireSessionPageWithRecovery(userId);
            session = sp.session();
            Page page = sp.page();
            session.touch();
            navigateToWorkflowTab(page, userId);
            if ("false".equals(loginUtil.checkLoginStatus(page, false))) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }
            human.think(page, 800, 1500);
            Locator rows = page.locator(WORKFLOW_ROW_SELECTOR);
            int rowCount = rows.count();
            if (rowCount == 0) {
                sendErrorResult(message, "工作流列表为空");
                return;
            }
            int resolvedIndex = resolveWorkflowIndex(rows, rowCount, targetIndex, workflowName, workflowCreator);
            if (resolvedIndex < 0) {
                sendErrorResult(message, "未找到源工作流");
                return;
            }
            Page editorPage = openWorkflowEditorFromListRow(page, rows.nth(resolvedIndex));
            gateIfLoginQrBlocking(editorPage);
            waitForEditorReady(editorPage);
            applyLearningCorrectionsFromPayload(message);
            Map<String, Object> exportBundle = exportFullWorkflowPanels(editorPage, userId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> exportRows =
                    (List<Map<String, Object>>) exportBundle.getOrDefault("rows", List.of());
            String exportShot = captureAndUpload(editorPage, userId,
                    "qyweixin_wf_clone_export_" + System.currentTimeMillis());

            Page listPage = returnToWorkflowListPage(editorPage, page);
            navigateToWorkflowTab(listPage, userId);
            human.think(listPage, 800, 1500);

            Locator addBtn = listPage.locator(
                    "button:has-text('添加工作流'), " +
                            "[class*='create_btn_by_query'], " +
                            "button:has-text('新建工作流'), " +
                            "button:has-text('+ 工作流'), " +
                            "[class*='add'][class*='workflow']");
            if (addBtn.count() == 0) {
                addBtn = listPage.locator("button").filter(new Locator.FilterOptions().setHasText("添加"));
            }
            if (addBtn.count() == 0) {
                sendErrorResult(message, "未找到「添加工作流」按钮");
                return;
            }
            human.naturalClick(listPage, addBtn.first());
            human.think(listPage, 2000, 3500);
            Page targetPage = listPage;
            if (listPage.context().pages().size() > 1) {
                targetPage = listPage.context().pages().get(listPage.context().pages().size() - 1);
                human.think(targetPage, 1500, 3000);
            }
            com.alibaba.fastjson2.JSONObject draft = buildWorkflowCloneDraftFromExports(newTitle.trim(), exportRows);
            boolean editorMode = isWorkflowEditorPage(targetPage);
            Map<String, Object> fillResult;
            if (editorMode) {
                fillResult = fillWorkflowEditorDraft(targetPage, draft, true, userId);
            } else {
                fillResult = fillWorkflowCreateWizard(targetPage, draft);
                human.think(targetPage, 800, 1500);
                tryClickWizardPrimary(targetPage);
                human.think(targetPage, 2000, 3500);
                if (listPage.context().pages().size() > 1) {
                    targetPage = listPage.context().pages().get(listPage.context().pages().size() - 1);
                }
                if (!isWorkflowEditorPage(targetPage)) {
                    sendErrorResult(message, "克隆失败：未进入工作流编辑器（创建向导未落到编辑页）");
                    return;
                }
                fillResult = fillWorkflowEditorDraft(targetPage, draft, true, userId);
            }
            Map<String, Object> replay = replayClonePanelExports(targetPage, exportRows);
            boolean renamedAfterReplay = tryRenameEditorTitle(targetPage, newTitle.trim());
            String verifyClonePayload = extractPayload(message, "verifyClone");
            boolean doVerify = !"false".equalsIgnoreCase(verifyClonePayload);
            String verifyMaxStr = extractPayload(message, "verifyMaxRounds");
            int verifyMaxRounds = 2;
            if (verifyMaxStr != null && !verifyMaxStr.isBlank()) {
                try {
                    verifyMaxRounds = Math.max(1, Math.min(5, Integer.parseInt(verifyMaxStr.trim())));
                } catch (Exception ignored) {
                }
            }
            Map<String, Object> cloneVerify = null;
            if (doVerify) {
                for (int vr = 0; vr < verifyMaxRounds; vr++) {
                    if (vr > 0) {
                        replay = replayClonePanelExports(targetPage, exportRows);
                        renamedAfterReplay = tryRenameEditorTitle(targetPage, newTitle.trim()) || renamedAfterReplay;
                    }
                    Map<String, Object> cloneExportBundle = exportFullWorkflowPanels(targetPage, userId);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> cloneRows =
                            (List<Map<String, Object>>) cloneExportBundle.getOrDefault("rows", List.of());
                    cloneVerify = QyWeixinWorkflowExportComparator.compare(exportRows, cloneRows);
                    cloneVerify.put("cloneGraphHints", cloneExportBundle.get("graphHints"));
                    cloneVerify.put("verifyRound", vr + 1);
                    cloneVerify.put("verifyMaxRounds", verifyMaxRounds);
                    if (Boolean.TRUE.equals(cloneVerify.get("pass"))) {
                        break;
                    }
                }
                appendQywxCloneVerifyLine(userId, newTitle.trim(), resolvedIndex, cloneVerify);
            }
            tryClickAny(targetPage, "保存");
            human.think(targetPage, 500, 1000);
            boolean published = tryPublishWorkflow(targetPage);
            String finalShot = captureAndUpload(targetPage, userId,
                    "qyweixin_wf_clone_pub_" + System.currentTimeMillis());
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("sourceIndex", resolvedIndex);
            resultData.put("exportCount", exportRows.size());
            resultData.put("graphHints", exportBundle.get("graphHints"));
            resultData.put("cloneLearningStats", workflowCloneLearning.memoryStats());
            resultData.put("branchHeavyRunsHint", workflowCloneLearning.getBranchHeavyRuns());
            resultData.put("exportScreenshotUrl", exportShot);
            resultData.put("workflowDraftUsed", draft);
            resultData.put("fill", fillResult);
            resultData.put("replay", replay);
            resultData.put("renamedAfterReplay", renamedAfterReplay);
            if (cloneVerify != null) {
                resultData.put("cloneVerify", cloneVerify);
            }
            resultData.put("editorWorkflowId", extractWorkflowIdFromEditorUrl(targetPage.url()));
            resultData.put("published", published);
            resultData.put("finalScreenshotUrl", finalShot);
            resultData.put("createUrl", targetPage.url());
            resultData.put("timestamp", System.currentTimeMillis());
            recordQywxAutomation("QYWEIXIN_WORKFLOW_CLONE_PUBLISH", message, resultData);
            sendResult(message, resultData);
        } catch (Exception e) {
            log.error("[CLONE_PUBLISH] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "CLONE_PUBLISH 失败: " + e.getMessage());
        } finally {
            releaseSession(session, "CLONE_PUBLISH");
            userLock.unlock();
        }
    }

    private Page openWorkflowEditorFromListRow(Page listPage, Locator targetRow) throws Exception {
        human.naturalScroll(listPage, 0, 400);
        human.think(listPage, 300, 600);
        Locator editBtn = targetRow.locator("button:has-text('编辑'), a:has-text('编辑'), [class*='edit']");
        if (editBtn.count() == 0) {
            BoundingBox rowBox = targetRow.boundingBox();
            if (rowBox != null) {
                human.moveMouse(listPage, rowBox.x + rowBox.width / 2, rowBox.y + rowBox.height / 2);
            }
            human.think(listPage, 500, 900);
            editBtn = targetRow.locator("button:has-text('编辑'), a:has-text('编辑'), [class*='edit']");
        }
        if (editBtn.count() == 0) {
            human.naturalClick(listPage, targetRow);
        } else {
            human.naturalClick(listPage, editBtn.first());
        }
        human.think(listPage, 2000, 3500);
        Page editorPage = listPage;
        if (listPage.context().pages().size() > 1) {
            editorPage = listPage.context().pages().get(listPage.context().pages().size() - 1);
            human.think(editorPage, 1500, 3000);
        }
        return editorPage;
    }

    private Page returnToWorkflowListPage(Page editorPage, Page listCandidate) {
        try {
            if (editorPage.context().pages().size() > 1) {
                editorPage.close();
                return listCandidate.context().pages().get(0);
            }
        } catch (Exception ignored) {
        }
        try {
            editorPage.navigate(WORKFLOW_URL);
            human.think(editorPage, 1200, 2000);
            return editorPage;
        } catch (Exception e) {
            return listCandidate;
        }
    }

    private static String extractWorkflowIdFromEditorUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        int i = url.indexOf("workflow_id=");
        if (i < 0) {
            return "";
        }
        int start = i + "workflow_id=".length();
        int end = url.indexOf('&', start);
        if (end < 0) {
            end = url.length();
        }
        return url.substring(start, end).trim();
    }

    private void appendQywxCloneVerifyLine(String userId, String newTitle, int sourceIndex,
            Map<String, Object> cloneVerify) {
        try {
            Path p = Paths.get("logs/qywx-clone-verify.jsonl").toAbsolutePath().normalize();
            Path parent = p.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("ts", System.currentTimeMillis());
            line.put("userId", userId);
            line.put("newTitle", newTitle);
            line.put("sourceIndex", sourceIndex);
            line.put("verify", cloneVerify);
            Files.writeString(p, com.alibaba.fastjson2.JSON.toJSONString(line) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            log.warn("[CLONE_PUBLISH] 写入 qywx-clone-verify.jsonl 失败: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportFullWorkflowPanels(Page editorPage, String userId) {
        Map<String, Object> graphHints = new HashMap<>();
        try {
            graphHints = (Map<String, Object>) editorPage.evaluate(
                    QyWeixinWorkflowFullPanelSampler.jsCanvasGraphHints());
            int br = parseIntSafe(String.valueOf(graphHints.getOrDefault("branchPathCount", "0")), 0);
            int lp = parseIntSafe(String.valueOf(graphHints.getOrDefault("loopPathCount", "0")), 0);
            workflowCloneLearning.observeGraphHints(br, lp);
        } catch (Exception e) {
            log.debug("[EXPORT_FULL] 画布分支/循环启发: {}", e.getMessage());
            graphHints = new HashMap<>();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        List<Map<String, Object>> ordered;
        try {
            ordered = (List<Map<String, Object>>) editorPage.evaluate(
                    QyWeixinWorkflowFullPanelSampler.jsListOrderedCanvasNodes());
        } catch (Exception e) {
            log.warn("[EXPORT_FULL] 列举节点失败: {}", e.getMessage());
            ordered = new ArrayList<>();
        }
        if (ordered == null) {
            ordered = new ArrayList<>();
        }
        int limit = Math.min(ordered.size(), 30);
        for (int i = 0; i < limit; i++) {
            Map<String, Object> row = ordered.get(i);
            String nodeId = String.valueOf(row.getOrDefault("nodeId", "")).trim();
            if (nodeId.isEmpty()) {
                continue;
            }
            if (!clickCanvasDataNodeId(editorPage, nodeId)) {
                Map<String, Object> merged = new LinkedHashMap<>(row);
                merged.put("panelSnapshot", Map.of("error", "click-node-failed", "nodeId", nodeId));
                merged.put("inferredMenu", "大模型问答");
                out.add(merged);
                continue;
            }
            human.think(editorPage, 450, 1000);
            Map<String, Object> panel;
            try {
                panel = (Map<String, Object>) editorPage.evaluate(
                        QyWeixinWorkflowFullPanelSampler.jsExtractTopConfigPanelSnapshot());
            } catch (Exception e) {
                panel = Map.of("error", "panel-eval-failed", "detail", e.getMessage() != null ? e.getMessage() : "");
            }
            Map<String, Object> merged = new LinkedHashMap<>(row);
            merged.put("panelSnapshot", panel);
            merged.put("inferredMenu", inferCloneMenuFromExportRow(row, panel));
            out.add(merged);
            try {
                editorPage.keyboard().press("Escape");
            } catch (Exception ignored) {
            }
            human.think(editorPage, 250, 600);
        }
        Map<String, Object> bundle = new HashMap<>();
        bundle.put("rows", out);
        bundle.put("graphHints", graphHints);
        return bundle;
    }

    private boolean clickCanvasDataNodeId(Page page, String rawNodeId) {
        if (rawNodeId == null || rawNodeId.isBlank()) {
            return false;
        }
        String esc = rawNodeId.replace("\\", "\\\\").replace("\"", "\\\"");
        try {
            Locator loc = page.locator("[data-node-id=\"" + esc + "\"]");
            if (loc.count() == 0) {
                return false;
            }
            Locator first = loc.first();
            first.scrollIntoViewIfNeeded();
            human.naturalClick(page, first);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 规则推断后再经 {@link QyWeixinWorkflowCloneLearning} 纠偏：适应无法穷举的分支/面板变体。
     */
    private String inferCloneMenuFromExportRow(Map<String, Object> row, Map<String, Object> panel) {
        String pt = "";
        if (panel != null) {
            pt = String.valueOf(panel.getOrDefault("panelTitle", ""));
        }
        String digest = "";
        if (panel != null) {
            digest = String.valueOf(panel.getOrDefault("panelTextDigest", ""));
        }
        String ruleMenu = inferCloneMenuRulesOnly(row, panel, pt, digest);
        return workflowCloneLearning.biasMenuChoice(pt, digest, ruleMenu);
    }

    private String inferCloneMenuRulesOnly(Map<String, Object> row, Map<String, Object> panel,
                                        String pt, String digest) {
        String card = String.valueOf(row.getOrDefault("labelLine", row.getOrDefault("cardText", "")));
        if (pt.contains("设置变量") || digest.contains("变量列表") || card.contains("设置变量")) {
            return "设置变量";
        }
        if (pt.contains("HTTP") || digest.contains("http://") || digest.contains("https://")) {
            return "HTTP请求";
        }
        if (pt.contains("知识库") || digest.contains("知识库")) {
            return "知识库问答";
        }
        if (pt.contains("问题分类") || pt.contains("条件")) {
            return "问题分类";
        }
        if (pt.contains("Python") || digest.contains("Python")) {
            return "Python";
        }
        if (pt.contains("大模型") || digest.contains("提示词")) {
            return "大模型问答";
        }
        if (pt.contains("结束") || digest.contains("输出参数") || digest.contains("可配置输出")
                || card.contains("结束可配置") || card.contains("输出参数给到")) {
            return "结束";
        }
        return "大模型问答";
    }

    private com.alibaba.fastjson2.JSONObject buildWorkflowCloneDraftFromExports(String newTitle,
                                                                                List<Map<String, Object>> exportRows) {
        com.alibaba.fastjson2.JSONObject d = new com.alibaba.fastjson2.JSONObject();
        d.put("title", newTitle);
        d.put("description", "由 QYWEIXIN_WORKFLOW_EXPORT_FULL / CLONE 生成；节点菜单来自面板标题推断，需以回放结果为准。");
        d.put("editorImportModel", "qywx_full_clone_v1");
        d.put("cloneMaxNodesPerRun", 20);
        com.alibaba.fastjson2.JSONArray nodes = new com.alibaba.fastjson2.JSONArray();
        int idx = 0;
        for (Map<String, Object> ex : exportRows) {
            String labelLine = String.valueOf(ex.getOrDefault("labelLine", "节点")).trim();
            if (labelLine.length() > 120) {
                labelLine = labelLine.substring(0, 120);
            }
            CanvasGuess g = guessCanvasNodeFromText(labelLine);
            String kind = g.kind();
            if ("unknown".equals(kind)) {
                kind = "process";
            }
            String inferred = String.valueOf(ex.getOrDefault("inferredMenu", "大模型问答"));
            com.alibaba.fastjson2.JSONObject n = new com.alibaba.fastjson2.JSONObject();
            n.put("id", "clone_n_" + idx);
            n.put("type", kind);
            n.put("label", labelLine.isEmpty() ? ("节点_" + idx) : labelLine);
            com.alibaba.fastjson2.JSONObject spec = new com.alibaba.fastjson2.JSONObject();
            spec.put("preferredMenu", inferred);
            spec.put("menuCandidates", new com.alibaba.fastjson2.JSONArray(List.of(inferred, "大模型问答")));
            n.put("editorNodeSpec", spec);
            Object ps = ex.get("panelSnapshot");
            if (ps != null) {
                n.put("qywxPanelExport",
                    ps instanceof com.alibaba.fastjson2.JSONObject ? ps
                        : com.alibaba.fastjson2.JSON.parseObject(com.alibaba.fastjson2.JSON.toJSONString(ps)));
            }
            nodes.add(n);
            idx++;
        }
        d.put("nodes", nodes);
        return d;
    }

    /** 按空间顺序对齐导出与当前画布，尽最大努力回放表单值。 */
    private Map<String, Object> replayClonePanelExports(Page page, List<Map<String, Object>> exportRows) {
        Map<String, Object> report = new HashMap<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, String>> current = extractEditorNodeList(page);
        current = sortSpatialEditorNodes(current);
        int n = Math.min(exportRows.size(), current.size());
        report.put("aligned", n);
        for (int i = 0; i < n; i++) {
            Map<String, Object> ex = exportRows.get(i);
            String nodeId = current.get(i).get("nodeId");
            @SuppressWarnings("unchecked")
            Map<String, Object> panel = ex.get("panelSnapshot") instanceof Map
                    ? (Map<String, Object>) ex.get("panelSnapshot")
                    : new HashMap<>();
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("index", i);
            step.put("nodeId", nodeId);
            if (nodeId == null || nodeId.isBlank() || !clickCanvasDataNodeId(page, nodeId)) {
                step.put("ok", false);
                step.put("reason", "click-node");
                steps.add(step);
                continue;
            }
            human.think(page, 400, 900);
            com.alibaba.fastjson2.JSONArray fields = new com.alibaba.fastjson2.JSONArray();
            Object rawFields = panel.get("fields");
            if (rawFields instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object v = m.get("value");
                        com.alibaba.fastjson2.JSONObject one = new com.alibaba.fastjson2.JSONObject();
                        one.put("value", v != null ? String.valueOf(v) : "");
                        fields.add(one);
                    }
                }
            }
            Object replayed = null;
            try {
                replayed = page.evaluate(QyWeixinWorkflowFullPanelSampler.jsReplayFieldValuesByOrder(), fields);
            } catch (Exception e) {
                step.put("replayError", e.getMessage());
            }
            step.put("replay", replayed);
            step.put("ok", true);
            boolean replayOk = fields.isEmpty();
            if (!replayOk && replayed instanceof Map<?, ?> rm) {
                Object rc = rm.get("replayed");
                if (rc instanceof Number num) {
                    replayOk = num.intValue() > 0;
                }
            }
            if (replayOk) {
                String pt = String.valueOf(panel.getOrDefault("panelTitle", ""));
                String dig = String.valueOf(panel.getOrDefault("panelTextDigest", ""));
                String menu = String.valueOf(ex.getOrDefault("inferredMenu", ""));
                workflowCloneLearning.recordReplayStep(pt, dig, menu, true);
            }
            steps.add(step);
            tryClickAny(page, "确定", "应用", "保存");
            human.think(page, 300, 700);
            try {
                page.keyboard().press("Escape");
            } catch (Exception ignored) {
            }
            human.think(page, 200, 500);
        }
        report.put("steps", steps);
        return report;
    }

    private List<Map<String, String>> sortSpatialEditorNodes(List<Map<String, String>> raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        List<Map<String, String>> copy = new ArrayList<>(raw);
        copy.sort((a, b) -> {
            int ya = parseIntSafe(a.get("y"), 0);
            int yb = parseIntSafe(b.get("y"), 0);
            if (ya != yb) {
                return Integer.compare(ya, yb);
            }
            int xa = parseIntSafe(a.get("x"), 0);
            int xb = parseIntSafe(b.get("x"), 0);
            return Integer.compare(xa, xb);
        });
        return copy;
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private boolean tryPublishWorkflow(Page editorPage) {
        boolean clicked = tryClickInteractiveText(editorPage, "发布")
                || tryClickAny(editorPage, "发布");
        if (!clicked) {
            return false;
        }
        human.think(editorPage, 800, 1600);
        tryClickAny(editorPage, "确定", "确认", "发布", "我知道了");
        human.think(editorPage, 500, 1000);
        return true;
    }

    private boolean tryClickAny(Page page, String... texts) {
        for (String t : texts) {
            try {
                if (t == null || t.isBlank()) {
                    continue;
                }
                Locator btn = page.locator("button:has-text('" + t.replace("\\", "\\\\").replace("'", "\\'") + "')");
                if (btn.count() > 0 && btn.first().isVisible()) {
                    human.naturalClick(page, btn.first());
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * 多策略点击可见文案：企微画布上「+ 添加指定操作」等常为 div/span 非原生 button，
     * 仅靠 {@code button:has-text} 会永远点不中。
     */
    private boolean tryClickInteractiveText(Page page, String text) {
        if (page == null || text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        String esc = t.replace("\\", "\\\\").replace("'", "\\'");
        String[] selectors = {
            "button:has-text('" + esc + "')",
            "[role=\"button\"]:has-text('" + esc + "')",
            "a:has-text('" + esc + "')",
            "span:has-text('" + esc + "')",
            "div:has-text('" + esc + "')"
        };
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel);
                int n = loc.count();
                for (int i = 0; i < n; i++) {
                    Locator one = loc.nth(i);
                    if (!one.isVisible()) {
                        continue;
                    }
                    String inner = "";
                    try {
                        inner = one.textContent() != null ? one.textContent().trim() : "";
                    } catch (Exception ignored) {
                    }
                    if (!inner.contains(t)) {
                        continue;
                    }
                    human.naturalClick(page, one);
                    log.info("[企业微信工作流] tryClickInteractiveText 命中 selector={} text={}", sel, t);
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            Locator byText = page.getByText(t, new Page.GetByTextOptions().setExact(false));
            int bn = byText.count();
            for (int i = 0; i < bn; i++) {
                Locator c = byText.nth(i);
                if (!c.isVisible()) {
                    continue;
                }
                try {
                    String tx = c.textContent();
                    if (tx != null && tx.replace("\n", "").contains(t)) {
                        human.naturalClick(page, c);
                        log.info("[企业微信工作流] tryClickInteractiveText 命中 getByText 索引={} text={}", i, t);
                        return true;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] tryClickInteractiveText getByText 失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 同文案可出现多处（侧栏、说明区、画布连线）；只点第一个往往打不开「选择需要执行的操作」。
     * 合并选择器与 getByText 的可见命中，按画布偏下的元素优先依次点击，直至操作选择器出现。
     */
    private boolean tryClickInteractiveTextUntilPicker(Page page, String text) {
        if (page == null || text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        String esc = t.replace("\\", "\\\\").replace("'", "\\'");
        String[] selectors = {
            "button:has-text('" + esc + "')",
            "[role=\"button\"]:has-text('" + esc + "')",
            "a:has-text('" + esc + "')",
            "span:has-text('" + esc + "')",
            "div:has-text('" + esc + "')"
        };
        List<Locator> raw = new ArrayList<>();
        for (String sel : selectors) {
            try {
                Locator loc = page.locator(sel);
                int n = Math.min(loc.count(), 48);
                for (int i = 0; i < n; i++) {
                    Locator one = loc.nth(i);
                    try {
                        one.scrollIntoViewIfNeeded();
                    } catch (Exception ignored) {
                    }
                    try {
                        human.think(page, 40, 90);
                    } catch (Exception ignored) {
                    }
                    if (!one.isVisible()) {
                        continue;
                    }
                    try {
                        String inner = one.textContent() != null ? one.textContent().trim() : "";
                        if (inner.contains(t)) {
                            raw.add(one);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        try {
            Locator byText = page.getByText(t, new Page.GetByTextOptions().setExact(false));
            int bn = Math.min(byText.count(), 48);
            for (int i = 0; i < bn; i++) {
                Locator c = byText.nth(i);
                try {
                    c.scrollIntoViewIfNeeded();
                } catch (Exception ignored) {
                }
                try {
                    human.think(page, 40, 90);
                } catch (Exception ignored) {
                }
                if (!c.isVisible()) {
                    continue;
                }
                try {
                    String tx = c.textContent();
                    if (tx != null && tx.replace("\n", "").contains(t)) {
                        raw.add(c);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] tryClickInteractiveTextUntilPicker getByText: {}", e.getMessage());
        }
        final double topSkip = 72;
        List<Locator> ranked = new ArrayList<>();
        Set<String> seenBox = new HashSet<>();
        for (Locator one : raw) {
            BoundingBox box = safeBoundingBox(one);
            // 仅剔除左上角顶栏/侧栏命中，避免误杀画布顶部连线上的「添加」
            if (box != null && box.y + box.height < topSkip && box.x + box.width < 420) {
                continue;
            }
            String key = box == null ? "null:" + ranked.size()
                    : String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f,%.1f", box.x, box.y, box.width, box.height);
            if (!seenBox.add(key)) {
                continue;
            }
            ranked.add(one);
        }
        ranked.sort((a, b) -> {
            BoundingBox ba = safeBoundingBox(a);
            BoundingBox bb = safeBoundingBox(b);
            double da = ba == null ? -1 : ba.y + ba.height;
            double db = bb == null ? -1 : bb.y + bb.height;
            return Double.compare(db, da);
        });
        if (ranked.isEmpty()) {
            log.debug("[企业微信工作流] tryClickInteractiveTextUntilPicker 无可见候选 raw={} text={}", raw.size(), t);
        }
        int max = Math.min(ranked.size(), 28);
        for (int i = 0; i < max; i++) {
            Locator one = ranked.get(i);
            try {
                one.scrollIntoViewIfNeeded();
            } catch (Exception ignored) {
            }
            reliableClickLocator(page, one);
            log.info("[企业微信工作流] tryClickInteractiveTextUntilPicker 候选 {}/{} text={}", i + 1, max, t);
            human.think(page, 280, 650);
            if (isQywxOperationPickerVisible(page)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyVisible(Page page, String selector) {
        try {
            Locator loc = page.locator(selector);
            for (int i = 0; i < loc.count(); i++) {
                if (loc.nth(i).isVisible()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private List<Map<String, Object>> collectValidationIssues(Page page) {
        List<Map<String, Object>> issues = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> js = (List<Map<String, Object>>) page.evaluate(
                "() => {" +
                "const sels=['[class*=error]','[class*=warn]','.t-message__content','.t-alert__description','[role=alert]'];" +
                "const out=[];" +
                "for(const sel of sels){" +
                "  const list=[...document.querySelectorAll(sel)].filter(el=>!!el && !!el.textContent && el.textContent.trim().length>0);" +
                "  for(const el of list.slice(0,20)){" +
                "    const txt=(el.textContent||'').trim().replace(/\\s+/g,' ').substring(0,220);" +
                "    if(!txt) continue;" +
                "    const low=txt.toLowerCase();" +
                "    let sev='info';" +
                "    if(low.includes('错误')||low.includes('error')||low.includes('失败')) sev='error';" +
                "    else if(low.includes('警告')||low.includes('warn')||low.includes('未配置')||low.includes('为空')) sev='warn';" +
                "    out.push({code: sel, message: txt, severity: sev});" +
                "  }" +
                "}" +
                "return out.slice(0,30);" +
                "}"
            );
            if (js != null) {
                issues.addAll(js);
            }
        } catch (Exception e) {
            Map<String, Object> m = new HashMap<>();
            m.put("code", "collect-error");
            m.put("message", "采集校验信息异常: " + e.getMessage());
            m.put("severity", "warn");
            issues.add(m);
        }
        return issues;
    }

    private Map<String, Object> summarizeValidationIssues(List<Map<String, Object>> issues,
                                                          boolean editorReady,
                                                          boolean checkClicked,
                                                          boolean debugVisible) {
        int error = 0;
        int warn = 0;
        int info = 0;
        for (Map<String, Object> row : issues) {
            String sev = String.valueOf(row.getOrDefault("severity", "info"));
            switch (sev) {
                case "error" -> error++;
                case "warn" -> warn++;
                default -> info++;
            }
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("errors", error);
        summary.put("warnings", warn);
        summary.put("infos", info);
        summary.put("pass", editorReady && error == 0);
        summary.put("checkClicked", checkClicked);
        summary.put("debugVisible", debugVisible);
        return summary;
    }

    private List<Map<String, Object>> defaultValidationChecklist(boolean editorReady,
                                                                  boolean checkClicked,
                                                                  List<Map<String, Object>> issues) {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(checkItem("editor-ready", editorReady ? "pass" : "fail", "编辑器可用性"));
        checks.add(checkItem("check-button-clicked", checkClicked ? "pass" : "unknown", "是否触发“检查/校验”按钮"));
        checks.add(checkItem("issue-count", issues == null || issues.isEmpty() ? "pass" : "warn", "页面是否采集到异常文案"));
        return checks;
    }

    private Map<String, Object> checkItem(String name, String status, String note) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("status", status);
        m.put("note", note);
        return m;
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_EDITOR_PROBE：编辑器诊断（视口、文本命中、菜单采样）
    // =========================================================================

    /**
     * 不改动业务状态，仅采集当前页面 DOM/视口信息，用于调试「添加指定操作」点不中、分辨率等问题。
     * <p>
     * payload（可选）：
     * <ul>
     *   <li>{@code skipNavigation}：默认 false；为 true 时不调用 {@link #navigateToWorkflowTab(Page, String)}，
     *       直接对当前 Tab 页做快照（适合人已在编辑器内卡住时从 Admin 触发探测）。</li>
     *   <li>{@code setViewportWidth} / {@code setViewportHeight}：二者均有效时先改视口再快照（如 1920×1080）。</li>
     *   <li>{@code capturePlaywrightTrace}：为 true 时在探测前后开启 Playwright Trace 并上传到截图服务（若可用）。</li>
     * </ul>
     */
    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_EDITOR_PROBE",
        description = "工作流编辑器诊断快照（视口、文本定位策略、可见菜单采样）",
        timeout = 300000L
    )
    public void handleWorkflowEditorProbe(EngineMessage message) {
        String userId = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");
        boolean skipNav = parsePayloadBoolean(message, "skipNavigation", false);

        log.info("[企微工作流编辑器诊断] 开始 - 用户: {}, skipNavigation={}", userId, skipNav);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;
        try {
            SessionPage sp = acquireSessionPageWithRecovery(userId);
            session = sp.session();
            Page page = sp.page();
            session.touch();

            if (!skipNav) {
                navigateToWorkflowTab(page, userId);
            }
            String loginStatus = loginUtil.checkLoginStatus(page, false);
            if ("false".equals(loginStatus)) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }

            human.think(page, 500, 1000);
            Page targetPage = page;
            var ctxPages = page.context().pages();
            if (ctxPages.size() > 1) {
                targetPage = ctxPages.get(ctxPages.size() - 1);
                human.think(targetPage, 400, 1000);
            }
            gateIfLoginQrBlocking(targetPage);

            Integer vw = parsePayloadInteger(message, "setViewportWidth");
            Integer vh = parsePayloadInteger(message, "setViewportHeight");
            if (vw != null && vh != null && vw >= 800 && vw <= 3840 && vh >= 600 && vh <= 2160) {
                try {
                    targetPage.setViewportSize(vw, vh);
                    human.think(targetPage, 600, 1200);
                    log.info("[企微工作流编辑器诊断] 已设置视口 {}x{}", vw, vh);
                } catch (Exception e) {
                    log.warn("[企微工作流编辑器诊断] setViewport 失败: {}", e.getMessage());
                }
            }

            boolean trace = parsePayloadBoolean(message, "capturePlaywrightTrace", false);
            java.nio.file.Path tracePath = null;
            if (trace) {
                try {
                    tracePath = java.nio.file.Files.createTempFile("qywx-pw-trace-", ".zip");
                    targetPage.context().tracing().start(
                            new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
                } catch (Exception e) {
                    log.warn("[企微工作流编辑器诊断] 启动 Trace 失败: {}", e.getMessage());
                    tracePath = null;
                }
            }

            List<String> triggers = workflowEditorNodeMapping != null
                    ? new ArrayList<>(workflowEditorNodeMapping.getAddPanelTriggers())
                    : List.of("添加指定操作", "添加运营操作", "添加节点");
            Map<String, Object> snapshot = PlaywrightWorkflowEditorDiagnostics.fullSnapshot(targetPage, triggers);
            snapshot.put("url", targetPage.url());
            snapshot.put("onWorkflowEditorClient", targetPage.url() != null
                    && targetPage.url().contains("workflow_editor"));
            snapshot.put("editorReady", waitForEditorReady(targetPage));
            snapshot.put("probeRunAt", System.currentTimeMillis());

            String screenshotUrl = captureAndUpload(targetPage, userId,
                    "qyweixin_wf_probe_" + System.currentTimeMillis());
            snapshot.put("screenshotUrl", screenshotUrl);

            if (trace && tracePath != null) {
                try {
                    targetPage.context().tracing().stop(new Tracing.StopOptions().setPath(tracePath));
                    snapshot.put("playwrightTraceZipPath", tracePath.toAbsolutePath().toString());
                    snapshot.put("playwrightTraceHint", "用 npx playwright show-trace <path> 打开");
                } catch (Exception e) {
                    snapshot.put("playwrightTraceError", e.getMessage());
                }
            }

            recordQywxAutomation("QYWEIXIN_WORKFLOW_EDITOR_PROBE", message, snapshot);
            sendResult(message, snapshot);
            log.info("[企微工作流编辑器诊断] 完成 - 用户: {}, url={}", userId, targetPage.url());
        } catch (Exception e) {
            log.error("[企微工作流编辑器诊断] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            recordQywxAutomation("QYWEIXIN_WORKFLOW_EDITOR_PROBE", message, Map.of(
                    "ok", false,
                    "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            ));
            sendErrorResult(message, "编辑器诊断失败: " + e.getMessage());
        } finally {
            releaseSession(session, "工作流编辑器诊断");
            userLock.unlock();
        }
    }

    /** 与录入页默认标题一致（列表首列） */
    private static final String DEFAULT_UNNAMED_WORKFLOW_TITLE = "未命名工作流";

    /**
     * {@code workflowLifecycle=PRODUCTION} / {@code PROD} 视为正式生产；否则（含缺省）为临时测试。
     */
    private boolean isProductionWorkflowRun(EngineMessage message) {
        String mode = extractPayload(message, "workflowLifecycle");
        if (mode == null || mode.isBlank()) {
            mode = extractPayload(message, "workflowRunMode");
        }
        if (mode == null || mode.isBlank()) {
            return false;
        }
        String m = mode.trim().toUpperCase(Locale.ROOT);
        return "PRODUCTION".equals(m) || "PROD".equals(m);
    }

    private boolean skipTemporaryCleanup(EngineMessage message) {
        return parsePayloadBoolean(message, "skipTemporaryWorkflowCleanup", false)
                || parsePayloadBoolean(message, "skipTempWorkflowCleanup", false);
    }

    /** 临时测试（默认）则录入完成后尝试删列表行；正式生产或显式 skip 则不删 */
    private boolean shouldCleanupTemporaryWorkflowAfterImport(EngineMessage message) {
        if (skipTemporaryCleanup(message)) {
            return false;
        }
        return !isProductionWorkflowRun(message);
    }

    private String resolveWorkflowCleanupDisplayName(EngineMessage message,
                                                    com.alibaba.fastjson2.JSONObject draft) {
        String override = extractPayload(message, "workflowCleanupDisplayName");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String title = draft != null ? draft.getString("title") : null;
        if (title == null || title.isBlank()) {
            return DEFAULT_UNNAMED_WORKFLOW_TITLE;
        }
        return title.trim();
    }

    /**
     * 在工作流列表页删除一行；调用前须已 {@link #navigateToWorkflowTab(Page, String)}。
     *
     * @param workflowIndexHint {@code >=0} 按索引优先；{@code -1} 仅用名称/创建人匹配
     * @return 含 {@code success}；成功时返回与单独删除 API 一致的字段
     */
    private Map<String, Object> runDeleteWorkflowOnListPage(Page page, String userId,
                                                            int workflowIndexHint,
                                                            String workflowName,
                                                            String workflowCreator) {
        Map<String, Object> out = new LinkedHashMap<>();
        Locator rows = page.locator(WORKFLOW_ROW_SELECTOR);
        int rowCount = rows.count();
        if (rowCount == 0) {
            out.put("success", false);
            out.put("errorMessage", "工作流列表为空");
            return out;
        }

        int resolvedIndex = resolveWorkflowIndex(rows, rowCount, workflowIndexHint, workflowName, workflowCreator);
        if (resolvedIndex < 0) {
            out.put("success", false);
            out.put("errorMessage", "未找到目标工作流：index=" + workflowIndexHint + ", name=" + workflowName
                    + ", creator=" + workflowCreator);
            return out;
        }

        Locator targetRow = rows.nth(resolvedIndex);
        String deletedName = extractWorkflowNameFromRow(targetRow);
        log.info("[企业微信工作流删除] 目标: #{} - {}", resolvedIndex, deletedName);

        human.naturalScroll(page, resolvedIndex * 50, 600);
        human.think(page, 300, 600);

        BoundingBox bbox = targetRow.boundingBox();
        if (bbox != null) {
            human.moveMouse(page, bbox.x + bbox.width / 2, bbox.y + bbox.height / 2);
            human.think(page, 600, 1000);
        }

        if (!clickDeleteWithOverflowExploration(page, targetRow)) {
            out.put("success", false);
            out.put("errorMessage",
                    "未找到「删除」入口：已尝试行内直接删除、操作列悬停及「⋯/更多」菜单。若权限不足或 UI 变更，请截图排查。");
            return out;
        }

        human.think(page, 800, 1500);

        boolean confirmed = handleDeleteConfirmDialog(page);
        if (!confirmed) {
            log.warn("[企业微信工作流删除] 未找到确认弹窗，可能已直接删除或出错");
        }

        human.think(page, 1000, 2000);

        int remainCount = page.locator(WORKFLOW_ROW_SELECTOR).count();
        String screenshotUrl = captureAndUpload(page, userId,
                "qyweixin_wf_deleted_" + System.currentTimeMillis());

        out.put("success", true);
        out.put("deleted", true);
        out.put("deletedName", deletedName);
        out.put("deletedIndex", resolvedIndex);
        out.put("remainCount", remainCount);
        out.put("screenshotUrl", screenshotUrl);
        out.put("currentUrl", page.url());
        out.put("timestamp", System.currentTimeMillis());
        return out;
    }

    // =========================================================================
    // QYWEIXIN_WORKFLOW_DELETE：删除指定工作流
    // =========================================================================

    /**
     * 删除指定工作流
     *
     * 请求示例：
     * {
     *   "type": "QYWEIXIN_WORKFLOW_DELETE",
     *   "engineId": "engine-001",
     *   "userId": "1",
     *   "workflowIndex": 0,         // 按行索引（0起），优先
     *   "workflowName": "工作流名称" // 按首列标题匹配（见 resolveWorkflowIndex）
     *   "workflowCreator": "曹敏昊"    // 可选：创建人列，多条同名时消歧
     * }
     *
     * 操作流程：
     * 1. 导航工作流 Tab
     * 2. 定位目标行，悬停触发操作按钮显示
     * 3. 点击"删除"按钮
     * 4. 处理确认弹窗（点击"确定"/"确认删除"）
     * 5. 验证删除成功，截图返回
     *
     * 返回数据：
     * - deleted:        是否成功删除
     * - deletedName:    删除的工作流名称
     * - remainCount:    删除后剩余工作流数量
     * - screenshotUrl
     */
    @OnceCapability(
        type = "QYWEIXIN_WORKFLOW_DELETE",
        description = "删除指定企业微信工作流（自然人浏览模式，需确认弹窗）",
        timeout = 90000L
    )
    public void handleWorkflowDelete(EngineMessage message) {
        String userId       = message.getUserId();
        String requestId    = extractRoutingField(message, "requestId");
        String indexStr      = extractPayload(message, "workflowIndex");
        String workflowName  = extractPayload(message, "workflowName");
        String workflowCreator = extractPayload(message, "workflowCreator");

        int targetIndex = -1;
        if (indexStr != null && !indexStr.isBlank()) {
            try { targetIndex = Integer.parseInt(indexStr.trim()); } catch (Exception ignored) {}
        }

        log.info("[企业微信工作流删除] 开始 - 用户: {}, 索引: {}, 名称: {}, 创建人: {}",
                userId, targetIndex, workflowName, workflowCreator);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            SessionPage sp = acquireSessionPageWithRecovery(userId);
            session = sp.session();
            Page page = sp.page();
            session.touch();

            // 导航到工作流页
            navigateToWorkflowTab(page, userId);

            // 登录检查
            String loginStatus = loginUtil.checkLoginStatus(page, false);
            if ("false".equals(loginStatus)) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }

            human.think(page, 800, 1500);

            Map<String, Object> resultData = runDeleteWorkflowOnListPage(page, userId, targetIndex, workflowName,
                    workflowCreator);
            if (!Boolean.TRUE.equals(resultData.get("success"))) {
                sendErrorResult(message, String.valueOf(resultData.getOrDefault("errorMessage", "删除失败")));
                return;
            }
            resultData.remove("success");

            sendResult(message, resultData);
            log.info("[企业微信工作流删除] 完成 - 用户: {}, 删除: {}, 剩余: {}",
                    userId, resultData.get("deletedName"), resultData.get("remainCount"));

        } catch (Exception e) {
            log.error("[企业微信工作流删除] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "删除工作流失败: " + e.getMessage());
        } finally {
            releaseSession(session, "工作流删除");
            userLock.unlock();
        }
    }

    // =========================================================================
    // 私有辅助方法 - 导航
    // =========================================================================

    /**
     * 自然人方式导航到工作流 Tab（优先直接 URL，备用点击 Tab）
     */
    private void navigateToWorkflowTab(Page page, String userId) {
        String currentUrl = page.url();

        // 如果已经在工作流 Tab，只需等待稳定
        if (currentUrl.contains("tab=workflow")) {
            log.info("[企业微信工作流] 已在工作流 Tab，等待稳定...");
            human.think(page, 500, 1000);
            return;
        }

        // 如果已在 AI 助手页，尝试点击"工作流"Tab
        if (currentUrl.contains("aiHelper")) {
            log.info("[企业微信工作流] 在 AI 助手页，点击工作流 Tab...");
            boolean tabClicked = human.clickTab(page, "工作流", 3000);
            if (tabClicked) {
                human.think(page, 1500, 2500);
                return;
            }
        }

        // 直接导航（最可靠）
        log.info("[企业微信工作流] 直接导航到工作流 Tab...");
        human.naturalNavigate(page, WORKFLOW_URL, HumanBehaviorUtil.Intensity.MEDIUM);
        human.think(page, 2000, 4000);

        // 验证导航成功
        if (!page.url().contains("aiHelper")) {
            log.warn("[企业微信工作流] 导航后 URL 异常: {}", page.url());
        }
    }

    // =========================================================================
    // 私有辅助方法 - 工作流列表
    // =========================================================================

    /**
     * 提取结构化工作流列表（解析每行的名称、描述、创建人、状态、更新时间）
     */
    private List<Map<String, Object>> extractStructuredWorkflowList(Page page) {
        List<Map<String, Object>> workflows = new ArrayList<>();
        try {
            Locator rows = page.locator(WORKFLOW_ROW_SELECTOR);
            int count = Math.min(rows.count(), 50);
            log.info("[企业微信工作流] 找到 {} 行", count);

            for (int i = 0; i < count; i++) {
                try {
                    Locator row = rows.nth(i);
                    String rowText = row.textContent().trim();

                    Map<String, Object> wf = new HashMap<>();
                    wf.put("index", i);
                    wf.put("rowText", rowText.length() > 400 ? rowText.substring(0, 400) : rowText);

                    // 从单元格中解析字段（td 1=名称+描述, td 2=创建人, td 3=状态, td 4=更新时间）
                    Locator cells = row.locator("td");
                    int cellCount = cells.count();
                    if (cellCount >= 4) {
                        String nameDesc = cells.nth(0).textContent().trim();
                        String creator  = cells.nth(1).textContent().trim();
                        String status   = cells.nth(2).textContent().trim();
                        String updateTime = cells.nth(3).textContent().trim();

                        // 拆分名称和描述（名称通常在前，描述在后）
                        String name = nameDesc;
                        String description = "";
                        int newlinePos = nameDesc.indexOf('\n');
                        if (newlinePos > 0) {
                            name = nameDesc.substring(0, newlinePos).trim();
                            description = nameDesc.substring(newlinePos).trim();
                        }

                        wf.put("name", name.length() > 100 ? name.substring(0, 100) : name);
                        wf.put("description", description.length() > 200 ? description.substring(0, 200) : description);
                        wf.put("creator", creator);
                        wf.put("status", status);
                        wf.put("updateTime", updateTime);
                    } else {
                        // 没有标准单元格，用全文
                        wf.put("name", rowText.length() > 100 ? rowText.substring(0, 100) : rowText);
                    }

                    workflows.add(wf);
                } catch (Exception e) {
                    log.debug("[企业微信工作流] 第{}行解析失败: {}", i, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[企业微信工作流] 提取工作流列表失败: {}", e.getMessage());
        }
        return workflows;
    }

    /**
     * 根据索引或名称定位目标行索引。
     * <p>
     * 名称匹配以「首列标题」为准，避免整行 contains 把「协会AI秘书工作流（赵梓琪开发）」与
     * 「协会AI秘书工作流」混成一条。当多条标题均以用户输入为前缀时，取<strong>最短标题</strong>一行（基础名优先于带后缀副本）。
     * 可选 payload {@code workflowCreator} 与第二列创建人对照，用于完全同名时的消歧。
     * </p>
     */
    private int resolveWorkflowIndex(
            Locator rows, int rowCount, int targetIndex, String workflowName, String workflowCreator) {
        if (targetIndex >= 0 && targetIndex < rowCount) {
            return targetIndex;
        }

        if (workflowName == null || workflowName.isBlank()) {
            if (targetIndex < 0) {
                return 0;
            }
            return -1;
        }

        String query = workflowName.trim();
        String lowerQ = query.toLowerCase();
        String creatorFilter = workflowCreator != null ? workflowCreator.trim() : "";
        boolean useCreator = !creatorFilter.isEmpty();

        List<String> titles = new ArrayList<>();
        List<String> creators = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            try {
                Locator row = rows.nth(i);
                titles.add(parsePrimaryNameFromRow(row));
                creators.add(extractCreatorFromRow(row));
            } catch (Exception e) {
                titles.add("");
                creators.add("");
            }
        }

        // 1) 标题精确匹配（忽略大小写；中文场景下等价于逐字相等）
        List<Integer> exact = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            if (query.equalsIgnoreCase(titles.get(i))) {
                exact.add(i);
            }
        }
        if (exact.size() == 1) {
            log.info("[企业微信工作流] 首列标题精确匹配: #{} — {}", exact.get(0), titles.get(exact.get(0)));
            return exact.get(0);
        }
        if (exact.size() > 1) {
            if (useCreator) {
                for (int idx : exact) {
                    if (creators.get(idx).contains(creatorFilter)) {
                        log.info("[企业微信工作流] 精确同名 + 创建人消歧: #{} — {}", idx, creators.get(idx));
                        return idx;
                    }
                }
            }
            log.warn("[企业微信工作流] 多条首列标题完全相同，请传 workflowCreator（创建人）消歧");
            return -1;
        }

        // 2) 前缀匹配：多行命中时取最短首列标题（避免「基础名」输给「基础名（后缀）」）
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            String t = titles.get(i);
            if (t.isEmpty()) {
                continue;
            }
            if (t.toLowerCase().startsWith(lowerQ)) {
                starts.add(i);
            }
        }
        Integer byShortest = pickShortestTitleMatch(starts, titles, creators, creatorFilter, useCreator);
        if (byShortest != null) {
            log.info("[企业微信工作流] 首列标题前缀匹配（取最短）: #{} — {}", byShortest, titles.get(byShortest));
            return byShortest;
        }

        // 3) 首列标题子串包含 + 最短优先
        List<Integer> subs = new ArrayList<>();
        for (int i = 0; i < rowCount; i++) {
            String t = titles.get(i);
            if (!t.isEmpty() && t.toLowerCase().contains(lowerQ)) {
                subs.add(i);
            }
        }
        Integer bySub = pickShortestTitleMatch(subs, titles, creators, creatorFilter, useCreator);
        if (bySub != null) {
            log.info("[企业微信工作流] 首列标题含关键字（取最短）: #{} — {}", bySub, titles.get(bySub));
            return bySub;
        }

        // 4) 兜底：整行文本包含（兼容非表格结构）；仍尊重创建人过滤
        for (int i = 0; i < rowCount; i++) {
            try {
                if (useCreator && !creators.get(i).contains(creatorFilter)) {
                    continue;
                }
                String text = rows.nth(i).textContent().trim().toLowerCase();
                if (text.contains(lowerQ)) {
                    log.info("[企业微信工作流] 行全文兜底匹配: #{}", i);
                    return i;
                }
            } catch (Exception ignored) {
            }
        }

        return -1;
    }

    /** 在候选索引中选取首列标题最短的行；若指定 creator 则必须第二列包含该字符串 */
    private Integer pickShortestTitleMatch(
            List<Integer> candidates,
            List<String> titles,
            List<String> creators,
            String creatorFilter,
            boolean useCreator) {
        if (candidates.isEmpty()) {
            return null;
        }
        int best = -1;
        int bestLen = Integer.MAX_VALUE;
        for (int idx : candidates) {
            if (useCreator && !creators.get(idx).contains(creatorFilter)) {
                continue;
            }
            int len = titles.get(idx).length();
            if (len < bestLen || (len == bestLen && (best < 0 || idx < best))) {
                bestLen = len;
                best = idx;
            }
        }
        return best >= 0 ? best : null;
    }

    /** 首列第一行 = 工作流标题（与列表解析一致） */
    private String parsePrimaryNameFromRow(Locator row) {
        try {
            Locator cells = row.locator("td");
            if (cells.count() > 0) {
                String text = cells.nth(0).textContent().trim();
                int newline = text.indexOf('\n');
                return newline > 0 ? text.substring(0, newline).trim() : text;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String extractCreatorFromRow(Locator row) {
        try {
            Locator cells = row.locator("td");
            if (cells.count() >= 2) {
                return cells.nth(1).textContent().trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 从行中提取工作流名称
     */
    private String extractWorkflowNameFromRow(Locator row) {
        try {
            String name = parsePrimaryNameFromRow(row);
            if (!name.isEmpty()) {
                return name;
            }
            String full = row.textContent().trim();
            return full.length() > 50 ? full.substring(0, 50) : full;
        } catch (Exception e) {
            return "unknown";
        }
    }

    // =========================================================================
    // 私有辅助方法 - 编辑器
    // =========================================================================

    /**
     * 等待工作流编辑器就绪（最多等 10 秒）
     */
    private boolean waitForEditorReady(Page page) {
        try {
            page.waitForSelector(EDITOR_READY_SELECTOR,
                new Page.WaitForSelectorOptions()
                    .setTimeout(10000)
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED)
            );
            log.info("[企业微信工作流] 编辑器就绪: {}", page.url());
            return true;
        } catch (Exception e) {
            log.warn("[企业微信工作流] 编辑器等待超时，URL: {}, 原因: {}", page.url(), e.getMessage());
            return false;
        }
    }

    /**
     * 提取编辑器 HTML 片段
     */
    private String extractEditorHtml(Page page, int maxLen) {
        try {
            // 优先提取编辑器容器 HTML
            @SuppressWarnings("unchecked")
            String html = (String) page.evaluate(
                "() => {\n" +
                "  const editor = document.querySelector('[class*=\"workflow-editor\"],[class*=\"flowEditor\"],"
                    + "[class*=\"flow-canvas\"],[class*=\"editor-wrap\"],[class*=\"editorWrap\"],.wd-flow-wrap,main');\n"
                + "  const el = editor || document.body;\n"
                + "  return el.innerHTML.substring(0, " + maxLen + ");\n"
                + "}"
            );
            return html != null ? html : "";
        } catch (Exception e) {
            return "提取失败: " + e.getMessage();
        }
    }

    /**
     * 提取编辑器 DOM 结构摘要
     */
    private String extractEditorDomSummary(Page page) {
        try {
            return (String) page.evaluate(
                "() => {\n" +
                "  const getStructure = (el, depth) => {\n" +
                "    if (depth > 4 || !el) return '';\n" +
                "    const tag = el.tagName?.toLowerCase() || '';\n" +
                "    const classes = [...el.classList].slice(0,3).join('.');\n" +
                "    const id = el.id ? '#' + el.id : '';\n" +
                "    const label = `${tag}${id}${classes ? '.' + classes : ''}`;\n" +
                "    const kids = [...el.children].slice(0,6)\n" +
                "      .map(c => getStructure(c, depth+1)).filter(Boolean)\n" +
                "      .join('\\n' + '  '.repeat(depth+1));\n" +
                "    return `${'  '.repeat(depth)}${label}${kids ? '\\n' + '  '.repeat(depth+1) + kids : ''}`;\n" +
                "  };\n" +
                "  const el = document.querySelector(\n" +
                "    '[class*=\"workflow-editor\"],[class*=\"flowEditor\"],[class*=\"flow-canvas\"],main,body'\n" +
                "  );\n" +
                "  return getStructure(el, 0)?.substring(0, 3000);\n" +
                "}"
            );
        } catch (Exception e) {
            return "DOM提取失败: " + e.getMessage();
        }
    }

    /**
     * 提取编辑器中的节点列表（工作流节点卡片）
     */
    private List<Map<String, String>> extractEditorNodeList(Page page) {
        List<Map<String, String>> nodes = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> jsResult = (List<Map<String, Object>>) page.evaluate(
                "() => {\n" +
                "  const normalize = (s) => (s || '').replace(/\\s+/g, ' ').trim();\n" +
                "  const isVisible = (el) => {\n" +
                "    if (!el) return false;\n" +
                "    const style = window.getComputedStyle(el);\n" +
                "    if (!style || style.display === 'none' || style.visibility === 'hidden') return false;\n" +
                "    const r = el.getBoundingClientRect();\n" +
                "    return r.width >= 100 && r.height >= 40;\n" +
                "  };\n" +
                "  const isControlLike = (el, txt) => {\n" +
                "    const t = normalize(txt);\n" +
                "    if (!t) return true;\n" +
                "    if (t.includes('添加节点') || t === '+' || t.includes('新增节点')) return true;\n" +
                "    if (el.tagName && ['BUTTON', 'A', 'SVG', 'PATH'].includes(el.tagName.toUpperCase())) return true;\n" +
                "    const cls = String(el.className || '');\n" +
                "    if (/add|plus|portal|drawer|tooltip|popover|menu|trigger/i.test(cls)) return true;\n" +
                "    return false;\n" +
                "  };\n" +
                "  const getNodeId = (el) => el.getAttribute('data-node-id') || el.getAttribute('data-id') || '';\n" +
                "  const selectorStrategies = [\n" +
                "    '[data-node-id]',\n" +
                "    'div[data-id][class*=\"node\"]',\n" +
                "    '[class*=\"node\"][class*=\"rounded\"]',\n" +
                "    '[class*=\"flow-node\"]',\n" +
                "    '[class*=\"flowNode\"]',\n" +
                "    '[class*=\"workflow-node\"]',\n" +
                "    '[class*=\"node\"][class*=\"card\"]',\n" +
                "    '[class*=\"step\"][class*=\"card\"]'\n" +
                "  ];\n" +
                "  const seen = new Set();\n" +
                "  const out = [];\n" +
                "  const pushCandidate = (el, selector) => {\n" +
                "    if (!isVisible(el)) return;\n" +
                "    const txt = normalize(el.textContent);\n" +
                "    if (isControlLike(el, txt)) return;\n" +
                "    const r = el.getBoundingClientRect();\n" +
                "    const nodeId = getNodeId(el);\n" +
                "    const key = nodeId || `${Math.round(r.left)}:${Math.round(r.top)}:${Math.round(r.width)}:${Math.round(r.height)}`;\n" +
                "    if (seen.has(key)) return;\n" +
                "    seen.add(key);\n" +
                "    const firstLine = normalize((el.textContent || '').split(/\\n+/)[0] || '');\n" +
                "    out.push({\n" +
                "      index: out.length,\n" +
                "      text: txt.substring(0, 180),\n" +
                "      label: firstLine.substring(0, 80),\n" +
                "      classes: String(el.className || '').substring(0, 200),\n" +
                "      selector,\n" +
                "      nodeId,\n" +
                "      x: String(Math.round(r.left)),\n" +
                "      y: String(Math.round(r.top)),\n" +
                "      width: String(Math.round(r.width)),\n" +
                "      height: String(Math.round(r.height))\n" +
                "    });\n" +
                "  };\n" +
                "  for (const sel of selectorStrategies) {\n" +
                "    const els = document.querySelectorAll(sel);\n" +
                "    for (const el of els) {\n" +
                "      pushCandidate(el, sel);\n" +
                "      if (out.length >= 50) break;\n" +
                "    }\n" +
                "    if (out.length >= 50) break;\n" +
                "  }\n" +
                "  // 兜底：针对 data-node-id 的父层容器（某些版本文本挂在子元素，卡片结构在父层）\n" +
                "  if (out.length === 0) {\n" +
                "    const inner = document.querySelectorAll('[data-node-id]');\n" +
                "    for (const child of inner) {\n" +
                "      const p = child.closest('div');\n" +
                "      if (p) pushCandidate(p, 'fallback:[data-node-id]-closest-div');\n" +
                "      if (out.length >= 50) break;\n" +
                "    }\n" +
                "  }\n" +
                "  return out;\n" +
                "}"
            );
            if (jsResult != null) {
                for (Map<String, Object> item : jsResult) {
                    Map<String, String> node = new HashMap<>();
                    item.forEach((k, v) -> node.put(k, String.valueOf(v)));
                    nodes.add(node);
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] 提取节点列表失败: {}", e.getMessage());
        }
        return nodes;
    }

    /**
     * 将画布 DOM 采样的 nodeList 转为逐要素摘要（供无「复制」时的迁移：解读 → 校对 → IMPORT_DRAFT）。
     */
    private List<Map<String, Object>> buildCanvasElementsFromNodeList(List<Map<String, String>> nodeList) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (nodeList == null) {
            return out;
        }
        int i = 0;
        for (Map<String, String> row : nodeList) {
            String text = row.getOrDefault("text", "");
            if (text == null || text.isBlank()) {
                text = row.getOrDefault("label", "");
            }
            Map<String, Object> el = new LinkedHashMap<>();
            el.put("index", row.getOrDefault("index", String.valueOf(i)));
            el.put("text", text);
            el.put("label", row.getOrDefault("label", ""));
            el.put("classes", row.get("classes"));
            el.put("selector", row.get("selector"));
            el.put("nodeId", row.getOrDefault("nodeId", ""));
            el.put("x", row.getOrDefault("x", ""));
            el.put("y", row.getOrDefault("y", ""));
            el.put("width", row.getOrDefault("width", ""));
            el.put("height", row.getOrDefault("height", ""));
            CanvasGuess g = guessCanvasNodeFromText(text);
            el.put("guessKind", g.kind());
            el.put("guessPreferredMenu", g.preferredMenu() == null ? "" : g.preferredMenu());
            el.put("guessMenuCandidates", g.menuCandidates());
            out.add(el);
            i++;
        }
        return out;
    }

    private record CanvasGuess(String kind, String preferredMenu, List<String> menuCandidates) {
    }

    /** 根据画布卡片可见文案猜测节点类型与首选添加菜单（启发式，需人工校对）。 */
    private CanvasGuess guessCanvasNodeFromText(String text) {
        String t = text == null ? "" : text.replace('\n', ' ').trim();
        if (t.isEmpty()) {
            return new CanvasGuess("unknown", null, List.of());
        }
        if (t.contains("开始")) {
            return new CanvasGuess("start", null, List.of());
        }
        if (t.contains("结束")) {
            return new CanvasGuess("end", null, List.of());
        }
        if (t.contains("设置变量") || t.contains("变量") && t.length() < 40) {
            return new CanvasGuess("input", "设置变量", List.of("设置变量", "变量"));
        }
        if (t.contains("HTTP") || t.contains("接口请求") || (t.contains("请求") && t.contains("http"))) {
            return new CanvasGuess("process", "HTTP 请求", List.of("HTTP 请求", "HTTP"));
        }
        if (t.contains("知识库")) {
            return new CanvasGuess("process", "知识库问答", List.of("知识库问答", "知识库"));
        }
        if (t.contains("问题分类") || (t.contains("分类") && !t.contains("大类"))) {
            return new CanvasGuess("check", "问题分类", List.of("问题分类", "分类"));
        }
        if (t.contains("循环")) {
            return new CanvasGuess("process", "循环", List.of("循环", "循环执行"));
        }
        if (t.contains("代码") || t.contains("Python")) {
            return new CanvasGuess("process", "Python", List.of("Python", "代码"));
        }
        return new CanvasGuess("process", "大模型问答", List.of("大模型问答", "大模型回答"));
    }

    /**
     * 由画布要素摘要拼最小 {@code workflowDraft}：线性边、占位结束输出；仅供迁移起点，细粒度配置需打开节点或 FBS-IR。
     */
    private Map<String, Object> buildHeuristicWorkflowDraftFromCanvas(String draftTitle,
                                                                      List<Map<String, Object>> canvasElements) {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("title", draftTitle);
        draft.put("description", "由 OPEN_EDITOR 画布文案启发式生成；节点类型与菜单为猜测，迁移后请在编辑器内逐项校对并补全变量/提示词/URL。");
        draft.put("editorImportModel", "canvas_heuristic_v1");

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        int seq = 0;
        String lastNodeId = "mig_0";
        for (Map<String, Object> el : canvasElements) {
            String kind = String.valueOf(el.getOrDefault("guessKind", "process"));
            String label = String.valueOf(el.getOrDefault("text", "节点")).trim();
            if (label.length() > 80) {
                label = label.substring(0, 80);
            }
            if (label.isEmpty()) {
                label = "节点" + seq;
            }
            String id = "mig_" + seq;
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", id);
            n.put("type", kind);
            n.put("label", label);
            @SuppressWarnings("unchecked")
            List<String> candidates = (List<String>) el.get("guessMenuCandidates");
            String pm = String.valueOf(el.getOrDefault("guessPreferredMenu", ""));
            if (!"start".equals(kind) && !"end".equals(kind) && !pm.isEmpty()) {
                Map<String, Object> spec = new LinkedHashMap<>();
                spec.put("preferredMenu", pm);
                spec.put("menuCandidates", candidates != null ? candidates : List.of(pm));
                n.put("editorNodeSpec", spec);
            }
            nodes.add(n);
            lastNodeId = id;
            if (seq > 0) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("from", "mig_" + (seq - 1));
                e.put("to", id);
                edges.add(e);
            }
            seq++;
        }

        draft.put("nodes", nodes);
        draft.put("edges", edges);
        if (nodes.isEmpty()) {
            draft.put("endOutputs", List.of());
        } else {
            Map<String, Object> endOut = new LinkedHashMap<>();
            endOut.put("name", "response");
            endOut.put("type", "String");
            endOut.put("source", lastNodeId);
            endOut.put("description", "占位：请改为实际结束节点输出绑定");
            draft.put("endOutputs", List.of(endOut));
        }
        draft.put("invokeHints", List.of(
            Map.of("id", "no-list-clone", "tip", "企微列表无复制工作流；本稿由画布可见文案生成，与真实连线/条件分支可能不一致。")
        ));
        return draft;
    }

    // =========================================================================
    // 私有辅助方法 - 创建向导
    // =========================================================================

    /**
     * 检查创建弹窗或编辑器是否就绪
     */
    private boolean checkDialogOrEditorReady(Page page) {
        try {
            // 等待弹窗或编辑器出现（5 秒）
            page.waitForSelector(
                "[class*='dialog'], [class*='modal'], [class*='popup'], " +
                EDITOR_READY_SELECTOR,
                new Page.WaitForSelectorOptions()
                    .setTimeout(5000)
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
            );
            return true;
        } catch (Exception e) {
            // 如果超时，检查 URL 是否变化
            return page.url().contains("create") || page.url().contains("edit") ||
                   !page.url().contains("tab=workflow");
        }
    }

    /**
     * 提取创建弹窗/向导 HTML
     */
    private String extractDialogHtml(Page page, int maxLen) {
        try {
            return (String) page.evaluate(
                "() => {\n" +
                "  const dialog = document.querySelector('[class*=\"dialog\"],[class*=\"modal\"],[class*=\"popup\"],"
                    + "[class*=\"create\"],[class*=\"wizard\"]');\n"
                + "  const el = dialog || document.body;\n"
                + "  return el.innerHTML.substring(0, " + maxLen + ");\n"
                + "}"
            );
        } catch (Exception e) {
            return "提取失败: " + e.getMessage();
        }
    }

    /**
     * 提取创建弹窗/向导 DOM 摘要
     */
    private String extractDialogDomSummary(Page page) {
        try {
            return (String) page.evaluate(
                "() => {\n" +
                "  const getStructure = (el, depth) => {\n" +
                "    if (depth > 4 || !el) return '';\n" +
                "    const tag = el.tagName?.toLowerCase() || '';\n" +
                "    const classes = [...el.classList].slice(0,3).join('.');\n" +
                "    const id = el.id ? '#' + el.id : '';\n" +
                "    const label = `${tag}${id}${classes ? '.' + classes : ''}`;\n" +
                "    const kids = [...el.children].slice(0,5)\n" +
                "      .map(c => getStructure(c, depth+1)).filter(Boolean)\n" +
                "      .join('\\n' + '  '.repeat(depth+1));\n" +
                "    return `${'  '.repeat(depth)}${label}${kids ? '\\n' + '  '.repeat(depth+1) + kids : ''}`;\n" +
                "  };\n" +
                "  const el = document.querySelector(\n" +
                "    '[class*=\"dialog\"],[class*=\"modal\"],[class*=\"popup\"],[class*=\"create\"],body'\n" +
                "  );\n" +
                "  return getStructure(el, 0)?.substring(0, 2000);\n" +
                "}"
            );
        } catch (Exception e) {
            return "DOM提取失败: " + e.getMessage();
        }
    }

    /**
     * 提取表单输入字段列表
     */
    private List<Map<String, String>> extractInputFields(Page page) {
        List<Map<String, String>> fields = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> jsResult = (List<Map<String, Object>>) page.evaluate(
                "() => [...document.querySelectorAll(\n" +
                "  'input, textarea, select, [contenteditable=\"true\"]'\n" +
                ")].map((el, i) => ({\n" +
                "  index: i,\n" +
                "  tag: el.tagName.toLowerCase(),\n" +
                "  type: el.type || el.tagName.toLowerCase(),\n" +
                "  name: el.name || '',\n" +
                "  placeholder: el.placeholder || '',\n" +
                "  value: (el.value || el.textContent || '').substring(0, 100),\n" +
                "  classes: el.className?.substring(0, 80),\n" +
                "  id: el.id || ''\n" +
                "}))"
            );
            if (jsResult != null) {
                for (Map<String, Object> item : jsResult) {
                    Map<String, String> field = new HashMap<>();
                    item.forEach((k, v) -> field.put(k, String.valueOf(v)));
                    fields.add(field);
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流] 提取表单字段失败: {}", e.getMessage());
        }
        return fields;
    }

    // =========================================================================
    // 私有辅助方法 - 删除（行内 / ⋯ 菜单）
    // =========================================================================

    /**
     * 通过浏览器自动化定位「删除」：优先行内直接点击；否则悬停操作列并尝试点开「⋯/更多」后再点菜单内「删除」。
     * 企微工作流表常见形态：仅「编辑」常驻，「删除」在省略号下拉（TDesign 浮层挂在 body）。
     */
    private boolean clickDeleteWithOverflowExploration(Page page, Locator targetRow) {
        // ---- A：行内可直接见的「删除」
        Locator direct = targetRow.locator(
            "button:has-text('删除'), a:has-text('删除'), span:has-text('删除')"
        );
        if (direct.count() > 0) {
            try {
                if (direct.first().isVisible()) {
                    log.info("[企业微信工作流删除] 使用行内可见「删除」");
                    human.naturalClick(page, direct.first());
                    return true;
                }
            } catch (Exception ignored) {
            }
        }

        // ---- B：操作列（最后一格）再悬停，便于显出 ⋯
        try {
            Locator lastTd = targetRow.locator("td").last();
            BoundingBox opBox = lastTd.boundingBox();
            if (opBox != null) {
                human.moveMouse(page, opBox.x + Math.max(2, opBox.width - 8), opBox.y + opBox.height / 2.0);
                human.think(page, 400, 900);
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流删除] 操作列悬停: {}", e.getMessage());
        }

        // ---- C：再次尝试行内（悬停后显形）
        direct = targetRow.locator("button:has-text('删除'), a:has-text('删除')");
        if (direct.count() > 0 && direct.first().isVisible()) {
            log.info("[企业微信工作流删除] 悬停后行内「删除」可点");
            human.naturalClick(page, direct.first());
            return true;
        }

        // ---- D：展开「更多 / ⋯」：多种 DOM 形态（无文字的图标按钮、aria-haspopup 等）
        for (int attempt = 0; attempt < 2; attempt++) {
            Locator opsCell = targetRow.locator("td").last();
            Locator overflow = null;

            // D1: aria-haspopup（TDesign Dropdown 常用）
            Locator withPopup = opsCell.locator("[aria-haspopup='true'], [aria-haspopup='menu'], button[aria-expanded]");
            if (withPopup.count() > 0) {
                overflow = withPopup.last();
                log.info("[企业微信工作流删除] 尝试 aria-haspopup 按钮");
            }
            // D2: 操作区 非「编辑」的按钮（多为 ⋯ 纯图标）
            if (overflow == null || overflow.count() == 0) {
                Locator btns = opsCell.locator("button");
                int bn = btns.count();
                for (int i = 0; i < bn; i++) {
                    Locator b = btns.nth(i);
                    try {
                        String tx = b.textContent() != null ? b.textContent().trim() : "";
                        if (!tx.contains("编辑") && !tx.contains("添加")) {
                            overflow = b;
                            log.info("[企业微信工作流删除] 尝试非「编辑」操作按钮 index={} text={}", i, tx);
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            // D3: row 内任意含 svg 且非编辑文案的按钮
            if (overflow == null || overflow.count() == 0) {
                Locator svgBtns = targetRow.locator("button:has(svg), a:has(svg)");
                int sn = svgBtns.count();
                for (int i = 0; i < sn; i++) {
                    Locator b = svgBtns.nth(i);
                    try {
                        String tx = b.textContent() != null ? b.textContent().trim() : "";
                        if (!tx.contains("编辑")) {
                            overflow = b;
                            log.info("[企业微信工作流删除] 尝试 SVG 操作按钮 index={}", i);
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            // D4: 文案「更多」
            if (overflow == null || overflow.count() == 0) {
                Locator more = targetRow.locator("button:has-text('更多'), [class*='more'] button, :text('⋯')");
                if (more.count() > 0) {
                    overflow = more.first();
                    log.info("[企业微信工作流删除] 尝试「更多/⋯」文本按钮");
                }
            }

            if (overflow != null) {
                try {
                    human.naturalClick(page, overflow);
                    human.think(page, 500, 1200);
                } catch (Exception e) {
                    log.warn("[企业微信工作流删除] 点击溢出按钮失败: {}", e.getMessage());
                }
            }

            // ---- E：在全局浮层/下拉中点「删除」（挂在 body，不能只在 row 内找）
            if (clickDeleteInOpenMenus(page)) {
                return true;
            }

            human.think(page, 300, 600);
        }

        // ---- F：最后兜底 —— page 级可见「删除」文本（仅限当前列表区域，避免点到其它 Tab）
        try {
            Locator inTable = page.locator("tbody tr button:has-text('删除'), tbody tr :text('删除')");
            if (inTable.count() > 0 && inTable.first().isVisible()) {
                log.info("[企业微信工作流删除] 兜底：tbody 内可见「删除」");
                human.naturalClick(page, inTable.first());
                return true;
            }
        } catch (Exception e) {
            log.debug("[企业微信工作流删除] 兜底删除失败: {}", e.getMessage());
        }

        return false;
    }

    /** 在当前已弹出的 menu/popup/dropdown 中点击「删除」 */
    private boolean clickDeleteInOpenMenus(Page page) {
        String[] menuRoots = new String[] {
            "[role='menu']",
            "[role='listbox']",
            ".t-dropdown__menu",
            ".t-popup__content",
            "[class*='dropdown'][class*='menu']",
            "[class*='Dropdown'] [class*='menu']"
        };

        for (String rootSel : menuRoots) {
            try {
                Locator root = page.locator(rootSel);
                if (root.count() == 0) {
                    continue;
                }
                Locator last = root.last();
                if (!last.isVisible()) {
                    continue;
                }
                Locator del = last.locator(
                    "button:has-text('删除'), " +
                    "[role='menuitem']:has-text('删除'), " +
                    ".t-dropdown__item:has-text('删除'), " +
                    "li:has-text('删除'), " +
                    "div:has-text('删除')"
                );
                if (del.count() > 0) {
                    Locator d0 = del.first();
                    if (d0.isVisible()) {
                        log.info("[企业微信工作流删除] 在浮层 [{}] 内点击「删除」", rootSel);
                        human.naturalClick(page, d0);
                        return true;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 无容器时：直接找可见的「删除」菜单项（浮层可能无统一 role）
        try {
            Locator any = page.locator(
                "[role='menuitem']:has-text('删除'), .t-dropdown__item:has-text('删除')"
            );
            int n = any.count();
            for (int i = 0; i < n; i++) {
                Locator item = any.nth(i);
                if (item.isVisible()) {
                    log.info("[企业微信工作流删除] 全局可见 menuitem「删除」 index={}", i);
                    human.naturalClick(page, item);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    /**
     * 处理删除确认弹窗（点击"确定"/"确认删除"/"删除"）
     */
    private boolean handleDeleteConfirmDialog(Page page) {
        try {
            // 等待确认弹窗
            page.waitForSelector(
                "[class*='dialog']:has-text('确认'), [class*='modal']:has-text('确认'), " +
                "[class*='dialog']:has-text('删除'), [class*='modal']:has-text('删除')",
                new Page.WaitForSelectorOptions().setTimeout(3000)
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
            );
        } catch (Exception e) {
            log.debug("[企业微信工作流] 等待确认弹窗超时: {}", e.getMessage());
        }

        // 尝试多种确认按钮
        String[] confirmSelectors = {
            "button:has-text('确认删除')",
            "button:has-text('确定删除')",
            "button:has-text('确认')",
            "button:has-text('确定')",
            "[class*='confirm']:has-text('删除')",
            "[class*='dialog'] button[class*='primary']",
            "[class*='modal'] button[class*='primary']",
            ".t-dialog__footer button[class*='primary']",
        };

        for (String sel : confirmSelectors) {
            try {
                Locator btn = page.locator(sel);
                if (btn.count() > 0 && btn.first().isVisible()) {
                    log.info("[企业微信工作流] 点击确认按钮: {}", sel);
                    human.naturalClick(page, btn.first());
                    return true;
                }
            } catch (Exception ignored) {}
        }

        log.warn("[企业微信工作流] 未找到确认按钮");
        return false;
    }

    // =========================================================================
    // 私有辅助方法 - Session / Lock / Screenshot / Message
    // =========================================================================

    private BrowserSession acquireFixedQyWeixinSession(String userId) {
        return browserPool.acquire(userId, "qyweixin", QYWEIXIN_FIXED_INSTANCE, true, false);
    }

    private ReentrantLock getUserSerialLock(String userId) {
        return USER_SERIAL_LOCKS.computeIfAbsent(userId, k -> new ReentrantLock());
    }

    private SessionPage acquireSessionPageWithRecovery(String userId) {
        BrowserSession session = acquireFixedQyWeixinSession(userId);
        try {
            Page page = session.getOrCreatePage();
            page.url();
            return new SessionPage(session, page);
        } catch (Exception firstEx) {
            if (!isTargetClosed(firstEx)) throw firstEx;
            log.warn("[企业微信工作流] 固定实例失效，重建 - 用户: {}", userId);
            try { browserPool.destroy(session); } catch (Exception ignored) {}
            BrowserSession rebuilt = acquireFixedQyWeixinSession(userId);
            Page rebuiltPage = rebuilt.getOrCreatePage();
            rebuiltPage.url();
            return new SessionPage(rebuilt, rebuiltPage);
        }
    }

    private void releaseSession(BrowserSession session, String context) {
        if (session != null) {
            try {
                session.touch();
                browserPool.release(session);
            } catch (Exception e) {
                log.warn("[企业微信工作流{}] 归还会话失败: {}", context, e.getMessage());
            }
        }
    }

    private String captureAndUpload(Page page, String userId, String name) {
        try {
            byte[] screenshotBytes = page.screenshot();
            com.wx.fbsir.engine.playwright.util.ScreenshotUploadClient.UploadResult result =
                    uploadClient.uploadScreenshot(userId, name, screenshotBytes);
            if (result.isSuccess()) {
                return result.getUrl();
            }
            log.warn("[企业微信工作流] 截图上传失败: {}", result);
            return null;
        } catch (Exception e) {
            log.warn("[企业微信工作流] 截图上传异常: {}", e.getMessage());
            return null;
        }
    }

    private boolean isTargetClosed(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String cn = t.getClass().getName();
            if (cn.contains("TargetClosedError")) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("target page, context or browser has been closed")
                        || lower.contains("targetclosederror")
                        || lower.contains("browser has been closed")) {
                    return true;
                }
            }
        }
        return false;
    }

    private record SessionPage(BrowserSession session, Page page) {}

    private void sendResult(EngineMessage message, Map<String, Object> data) {
        String userId         = message.getUserId();
        String requestId      = extractRoutingField(message, "requestId");
        String sourceType     = extractRoutingField(message, "sourceType");
        String sourceClientId = extractRoutingField(message, "sourceClientId");

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

    private void sendErrorResult(EngineMessage message, String errorMessage) {
        String userId         = message.getUserId();
        String requestId      = extractRoutingField(message, "requestId");
        String sourceType     = extractRoutingField(message, "sourceType");
        String sourceClientId = extractRoutingField(message, "sourceClientId");

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
        log.error("[企业微信工作流] 发送错误 - 用户: {}, 错误: {}", userId, errorMessage);
    }

    /**
     * Admin {@code POST /ws/engine/request} 将 requestId、sourceType、sourceClientId 置于 payload；
     * 直连 WebSocket 客户端可能在 JSON 顶层。二者都需支持，否则 HTTP 等不到 TASK_RESULT。
     */
    /**
     * 会话中途可能弹出微信/企微扫码登录浮层，挡住画布与后续点击；此处若检测到则阻塞等待扫码完成（最长约 3 分钟）。
     */
    private void gateIfLoginQrBlocking(Page page) {
        try {
            if (loginUtil == null || page == null) {
                return;
            }
            if (!loginUtil.isLoginQrLayerVisible(page)) {
                return;
            }
            log.warn("[企业微信工作流] 检测到登录二维码浮层，可能会话过期；请用手机扫码/确认，自动化将暂停等待");
            loginUtil.waitUntilLoginQrLayerAbsent(page, 120_000L);
        } catch (Exception e) {
            log.debug("[企业微信工作流] 登录浮层处理: {}", e.getMessage());
        }
    }

    /**
     * 将本次执行结果写入 {@link QyWeixinAutomationRunRecorder}（见 application.yml wxfbsir.engine.qyweixin）。
     */
    private void recordQywxAutomation(String capabilityType, EngineMessage message, Map<String, Object> data) {
        try {
            if (qyweixinAutomationRunRecorder == null) {
                return;
            }
            String reqId = extractRoutingField(message, "requestId");
            qyweixinAutomationRunRecorder.append(capabilityType, message.getUserId(), reqId, data);
        } catch (Exception e) {
            log.debug("[QywxAutomationLog] 记录跳过: {}", e.getMessage());
        }
    }

    private String extractRoutingField(EngineMessage message, String key) {
        String v = extractTopLevel(message, key);
        if (v != null && !v.isBlank()) {
            return v;
        }
        return extractPayload(message, key);
    }

    private boolean parsePayloadBoolean(EngineMessage message, String key, boolean defaultVal) {
        try {
            String raw = message.getRawJson();
            if (raw == null || raw.isBlank()) {
                return defaultVal;
            }
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(raw);
            if (root == null) {
                return defaultVal;
            }
            if (root.containsKey(key)) {
                return root.getBooleanValue(key);
            }
            com.alibaba.fastjson2.JSONObject payload = root.getJSONObject("payload");
            if (payload != null && payload.containsKey(key)) {
                return payload.getBooleanValue(key);
            }
        } catch (Exception ignored) {
        }
        return defaultVal;
    }

    private Integer parsePayloadInteger(EngineMessage message, String key) {
        try {
            String raw = message.getRawJson();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(raw);
            if (root == null) {
                return null;
            }
            if (root.containsKey(key)) {
                return root.getInteger(key);
            }
            com.alibaba.fastjson2.JSONObject payload = root.getJSONObject("payload");
            return payload != null ? payload.getInteger(key) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

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

    private String extractPayload(EngineMessage message, String key) {
        try {
            String raw = message.getRawJson();
            if (raw == null || raw.isBlank()) return null;
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(raw);
            if (root == null) return null;
            // 先试顶层
            String val = root.getString(key);
            if (val != null) return val;
            // 再试 payload
            com.alibaba.fastjson2.JSONObject payload = root.getJSONObject("payload");
            return payload != null ? payload.getString(key) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
