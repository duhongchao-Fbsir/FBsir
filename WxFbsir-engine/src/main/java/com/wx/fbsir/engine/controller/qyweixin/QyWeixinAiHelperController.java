package com.wx.fbsir.engine.controller.qyweixin;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.wx.fbsir.engine.capability.annotation.OnceCapability;
import com.wx.fbsir.engine.capability.base.StreamTaskHelper;
import com.wx.fbsir.engine.playwright.pool.BrowserPoolManager;
import com.wx.fbsir.engine.playwright.session.BrowserSession;
import com.wx.fbsir.engine.playwright.util.HumanBehaviorUtil;
import com.wx.fbsir.engine.utils.qyweixin.QyWeixinLoginUtil;
import com.wx.fbsir.engine.websocket.message.EngineMessage;
import com.wx.fbsir.engine.websocket.message.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 企业微信 AI 助手（智能机器人）控制器
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 功能概述
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * 所有 Playwright 操作均使用 {@link HumanBehaviorUtil} 自然人浏览模式，
 * 通过贝塞尔曲线鼠标移动、随机延迟、偏移点击等手段规避自动化检测。
 *
 * 能力列表：
 *
 * | 消息类型                       | 功能                          |
 * |-------------------------------|-------------------------------|
 * | QYWEIXIN_EXPLORE_AIHELPER     | 探索 AI 助手页面 DOM（开发研究） |
 * | QYWEIXIN_LIST_ROBOTS          | 获取智能机器人列表（管理 Tab）   |
 * | QYWEIXIN_EXPLORE_WORKFLOW     | 进入工作流 Tab 探索工作流列表    |
 * | QYWEIXIN_EXPLORE_UI_MAP       | 多路由细粒度 Tab/按钮/表格等清单 |
 * | QYWEIXIN_CAPABILITY_CATALOG   | 程序化能力清单（供项目映射）     |
 *
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * 📌 自然人浏览模式说明
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 *
 * - navigate：导航后执行 warmUp（滚动+鼠标漫游+停顿）
 * - click：贝塞尔曲线移动鼠标 → 悬停预热 → 随机偏移点击
 * - tab 切换：先让鼠标漫游到侧边栏区域，再点击
 * - 列表读取：读取前先滚动浏览，模拟人眼扫描
 *
 * @author wxfbsir
 */
@Controller
public class QyWeixinAiHelperController extends StreamTaskHelper {

    private static final String QYWEIXIN_FIXED_INSTANCE = "fixed-main";
    private static final String AIHELPER_URL =
            "https://work.weixin.qq.com/wework_admin/frame#/aiHelper/list?from=manage_tools";

    /** 侧边栏 Tab 选择器 */
    private static final String[] TAB_SELECTORS = {
        ".sidebar_menu_item", ".menu_item", "[class*='side'][class*='item']",
        "[class*='nav'][class*='item']", "[class*='tab'][class*='item']"
    };

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

    // =========================================================================
    // QYWEIXIN_EXPLORE_AIHELPER：探索 AI 助手页面 DOM（开发研究用）
    // =========================================================================

    /**
     * 探索企业微信 AI 助手（智能机器人）页面的 DOM 结构
     *
     * 请求示例：
     * {"type": "QYWEIXIN_EXPLORE_AIHELPER", "engineId": "engine-001"}
     *
     * 返回数据：
     * - screenshotUrl:   页面截图 URL
     * - currentUrl:      当前 URL
     * - isLoggedIn:      是否已登录
     * - robots:          机器人列表（selector + text + index）
     * - domSummary:      关键 DOM 节点摘要
     * - iframeUrls:      所有 iframe URL
     * - allLinks:        页面链接（分析导航结构）
     * - workflowArea:    工作流相关区域分析（tabs、sidebarText、workflowElements）
     * - pageHtmlSample:  body innerHTML 前 4000 字
     */
    @OnceCapability(
        type = "QYWEIXIN_EXPLORE_AIHELPER",
        description = "探索企业微信AI助手页面DOM结构（使用自然人浏览模式）",
        timeout = 90000L
    )
    public void handleExploreAiHelper(EngineMessage message) {
        String userId    = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");

        log.info("[企业微信AI助手探索] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session();
            Page page = sessionPage.page();
            session.touch();

            // 恢复登录状态
            if (com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                    .hasLoginState("qyweixin", userId)) {
                com.wx.fbsir.engine.playwright.login.manager.LoginStateManager
                        .restoreLoginState(session, "qyweixin", userId);
            }

            // ★ 自然人浏览：导航 + 预热
            human.naturalNavigate(page, AIHELPER_URL, HumanBehaviorUtil.Intensity.MEDIUM);
            // 额外等待 SPA 内容渲染
            human.think(page, 2000, 3500);

            String currentUrl = page.url();
            log.info("[企业微信AI助手探索] 当前URL: {}", currentUrl);

            // 检查登录状态
            String loginStatus = loginUtil.checkLoginStatus(page, false);
            boolean isLoggedIn = !"false".equals(loginStatus);

            // ★ 自然人浏览：登录后继续浏览一下
            if (isLoggedIn) {
                human.warmUp(page, HumanBehaviorUtil.Intensity.LOW);
            }

            // 截图
            String screenshotUrl = captureAndUpload(page, userId,
                    "qyweixin_explore_" + System.currentTimeMillis());

            // 深度 DOM 分析
            List<Map<String, String>> allLinks  = extractLinks(page);
            List<Map<String, String>> robots     = extractRobotList(page);
            String domSummary                    = extractDomSummary(page);
            String htmlSample                    = extractHtmlSample(page, 4000);

            List<String> iframeUrls = new ArrayList<>();
            for (com.microsoft.playwright.Frame frame : page.frames()) {
                iframeUrls.add(frame.url());
            }

            Map<String, Object> workflowArea = new HashMap<>();
            if (isLoggedIn && currentUrl.contains("aiHelper")) {
                workflowArea = analyzeAiHelperPage(page);
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("screenshotUrl", screenshotUrl);
            resultData.put("currentUrl", currentUrl);
            resultData.put("isLoggedIn", isLoggedIn);
            resultData.put("loginStatus", loginStatus);
            resultData.put("robots", robots);
            resultData.put("allLinks", allLinks);
            resultData.put("domSummary", domSummary);
            resultData.put("iframeUrls", iframeUrls);
            resultData.put("pageHtmlSample", htmlSample);
            resultData.put("workflowArea", workflowArea);
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(message, resultData);
            log.info("[企业微信AI助手探索] 完成 - 用户: {}, 已登录: {}, 机器人数: {}",
                    userId, isLoggedIn, robots.size());

        } catch (Exception e) {
            log.error("[企业微信AI助手探索] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "AI助手页面探索失败: " + e.getMessage());
        } finally {
            releaseSession(session, "探索");
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_LIST_ROBOTS：管理 Tab → 机器人列表
    // =========================================================================

    /**
     * 获取企业微信智能机器人列表
     *
     * 请求示例：
     * {"type": "QYWEIXIN_LIST_ROBOTS", "engineId": "engine-001"}
     *
     * 操作流程（自然人模式）：
     * 1. 导航到 AI 助手页，warmUp
     * 2. 自然移动鼠标到侧边栏，点击"管理" Tab
     * 3. 等待内容加载，轻度滚动浏览
     * 4. 提取机器人列表
     * 5. 截图返回
     *
     * 返回：
     * - robots: [{name, status, description, index, ...}]
     * - count:  机器人总数
     * - screenshotUrl, currentUrl
     */
    @OnceCapability(
        type = "QYWEIXIN_LIST_ROBOTS",
        description = "获取企业微信智能机器人列表（自然人浏览模式）",
        timeout = 90000L
    )
    public void handleListRobots(EngineMessage message) {
        String userId    = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");

        log.info("[企业微信机器人列表] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session();
            Page page = sessionPage.page();
            session.touch();

            // ★ 自然人浏览：导航 + 预热
            human.naturalNavigate(page, AIHELPER_URL, HumanBehaviorUtil.Intensity.MEDIUM);
            human.think(page, 2000, 3500);

            String loginStatus = loginUtil.checkLoginStatus(page, false);
            boolean isLoggedIn = !"false".equals(loginStatus);

            if (!isLoggedIn) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }

            // ★ 自然人浏览：点击"管理" Tab
            boolean tabClicked = human.clickTab(page, "管理", 2500);
            if (!tabClicked) {
                log.warn("[企业微信机器人列表] 未找到'管理' Tab，尝试备用选择器");
                // 备用：直接通过 URL hash 跳转
                human.naturalNavigate(page,
                    "https://work.weixin.qq.com/wework_admin/frame#/aiHelper/manage",
                    HumanBehaviorUtil.Intensity.LOW);
                human.think(page, 1500, 2500);
            }

            // ★ 自然人浏览：轻度滚动浏览机器人列表
            human.naturalScroll(page, 200, 800);
            human.think(page, 500, 1200);

            List<Map<String, String>> robots = extractRobotList(page);
            String screenshotUrl = captureAndUpload(page, userId,
                    "qyweixin_robots_" + System.currentTimeMillis());

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("robots", robots);
            resultData.put("count", robots.size());
            resultData.put("screenshotUrl", screenshotUrl);
            resultData.put("currentUrl", page.url());
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(message, resultData);
            log.info("[企业微信机器人列表] 完成 - 用户: {}, 机器人数: {}", userId, robots.size());

        } catch (Exception e) {
            log.error("[企业微信机器人列表] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "获取机器人列表失败: " + e.getMessage());
        } finally {
            releaseSession(session, "机器人列表");
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_EXPLORE_WORKFLOW：工作流 Tab → 工作流列表探索
    // =========================================================================

    /**
     * 进入企业微信智能机器人工作流 Tab，探索工作流列表结构
     *
     * 请求示例：
     * {"type": "QYWEIXIN_EXPLORE_WORKFLOW", "engineId": "engine-001"}
     *
     * 操作流程（自然人模式）：
     * 1. 导航到 AI 助手页，warmUp
     * 2. 点击"工作流" Tab
     * 3. 等待工作流列表加载
     * 4. 滚动浏览、提取工作流列表信息
     * 5. 分析"新建工作流"按钮、工作流卡片 DOM
     * 6. 截图 + DOM 快照返回
     *
     * 返回：
     * - workflows:       工作流列表 [{name, status, description, ...}]
     * - domSummary:      工作流区域 DOM 摘要
     * - createBtnInfo:   "新建工作流"按钮信息
     * - screenshotUrl
     * - htmlSample:      工作流区域 HTML 片段
     */
    @OnceCapability(
        type = "QYWEIXIN_EXPLORE_WORKFLOW",
        description = "探索企业微信智能机器人工作流列表（自然人浏览模式）",
        timeout = 90000L
    )
    public void handleExploreWorkflow(EngineMessage message) {
        String userId    = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");

        log.info("[企业微信工作流探索] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session();
            Page page = sessionPage.page();
            session.touch();

            // ★ 自然人浏览：导航 + 预热
            human.naturalNavigate(page, AIHELPER_URL, HumanBehaviorUtil.Intensity.MEDIUM);
            human.think(page, 2000, 4000);

            String loginStatus = loginUtil.checkLoginStatus(page, false);
            boolean isLoggedIn = !"false".equals(loginStatus);

            if (!isLoggedIn) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }

            // ★ 自然人浏览：点击"工作流" Tab
            log.info("[企业微信工作流探索] 点击工作流 Tab...");
            boolean tabClicked = human.clickTab(page, "工作流", 3000);
            if (!tabClicked) {
                log.warn("[企业微信工作流探索] 未找到'工作流' Tab，尝试备用 URL 导航");
                human.naturalNavigate(page,
                    "https://work.weixin.qq.com/wework_admin/frame#/aiHelper/workflow",
                    HumanBehaviorUtil.Intensity.LOW);
                human.think(page, 2000, 3500);
            }

            // ★ 自然人浏览：等待工作流内容加载后浏览
            human.naturalScroll(page, 150, 700);
            human.think(page, 500, 1200);

            String currentUrl = page.url();
            log.info("[企业微信工作流探索] 当前URL: {}", currentUrl);

            // 截图（点击 Tab 后的状态）
            String screenshotUrl = captureAndUpload(page, userId,
                    "qyweixin_workflow_" + System.currentTimeMillis());

            // 分析工作流区域 DOM
            Map<String, Object> workflowAnalysis = analyzeWorkflowPage(page);

            // 提取工作流列表
            List<Map<String, String>> workflows = extractWorkflowList(page);

            // ★ 如果列表为空，滚动继续找
            if (workflows.isEmpty()) {
                log.info("[企业微信工作流探索] 列表为空，继续滚动查找...");
                human.naturalScroll(page, 400, 1000);
                human.think(page, 800, 1500);
                workflows = extractWorkflowList(page);
            }

            // DOM 结构
            String domSummary = extractDomSummary(page);
            String htmlSample = extractHtmlSample(page, 5000);

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("screenshotUrl", screenshotUrl);
            resultData.put("currentUrl", currentUrl);
            resultData.put("isLoggedIn", true);
            resultData.put("workflows", workflows);
            resultData.put("workflowCount", workflows.size());
            resultData.put("workflowAnalysis", workflowAnalysis);
            resultData.put("domSummary", domSummary);
            resultData.put("htmlSample", htmlSample);
            resultData.put("tabClickSuccess", tabClicked);
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(message, resultData);
            log.info("[企业微信工作流探索] 完成 - 用户: {}, 工作流数: {}", userId, workflows.size());

        } catch (Exception e) {
            log.error("[企业微信工作流探索] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "工作流探索失败: " + e.getMessage());
        } finally {
            releaseSession(session, "工作流探索");
            userLock.unlock();
        }
    }

    // =========================================================================
    // QYWEIXIN_EXPLORE_UI_MAP：多路由细粒度 UI 清单（供项目侧映射）
    // =========================================================================

    /**
     * 在已登录会话下依次打开若干与「智能机器人 / AI 助手」相关的 hash 路由，
     * 每站采集：Tab、按钮、表格表头、顶层 hash 链接、可见输入框等，用于本系统页面与 Engine 能力对齐。
     */
    @OnceCapability(
        type = "QYWEIXIN_EXPLORE_UI_MAP",
        description = "细粒度采集企微 AI 助手多路由 UI 结构（Tab/按钮/表格/链接）",
        timeout = 120000L
    )
    public void handleExploreUiMap(EngineMessage message) {
        String userId = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");

        log.info("[企业微信UI映射] 开始 - 用户: {}, 请求: {}", userId, requestId);

        ReentrantLock userLock = getUserSerialLock(userId);
        userLock.lock();
        BrowserSession session = null;

        try {
            SessionPage sessionPage = acquireSessionPageWithRecovery(userId);
            session = sessionPage.session();
            Page page = sessionPage.page();
            session.touch();

            String loginStatus = loginUtil.checkLoginStatus(page, false);
            if ("false".equals(loginStatus)) {
                sendErrorResult(message, "未登录，请先执行 QYWEIXIN_SCAN_LOGIN");
                return;
            }

            List<Map<String, Object>> views = new ArrayList<>();
            int idx = 0;
            for (String route : AI_HELPER_UI_MAP_ROUTES) {
                try {
                    log.info("[企业微信UI映射] 路由 #{}: {}", idx, route);
                    human.naturalNavigate(page, route, HumanBehaviorUtil.Intensity.MEDIUM);
                    human.think(page, 2200, 4500);

                    String shotUrl = captureAndUpload(page, userId,
                            "qyweixin_ui_map_" + idx + "_" + System.currentTimeMillis());

                    @SuppressWarnings("unchecked")
                    Map<String, Object> inventory = (Map<String, Object>) page.evaluate(
                            QyWeixinUiInventoryScript.fineInventoryExpression());

                    Map<String, Object> one = new HashMap<>();
                    one.put("routeIndex", idx);
                    one.put("requestedUrl", route);
                    one.put("actualUrl", page.url());
                    one.put("screenshotUrl", shotUrl);
                    one.put("inventory", inventory != null ? inventory : new HashMap<>());
                    views.add(one);
                } catch (Exception ex) {
                    log.warn("[企业微信UI映射] 路由 #{} 失败: {}", idx, ex.getMessage());
                    Map<String, Object> err = new HashMap<>();
                    err.put("routeIndex", idx);
                    err.put("requestedUrl", route);
                    err.put("error", ex.getMessage());
                    views.add(err);
                }
                idx++;
            }

            Map<String, Object> resultData = new HashMap<>();
            resultData.put("inventorySchemaVersion", QyWeixinUiInventoryScript.SCHEMA_VERSION);
            resultData.put("views", views);
            resultData.put("routeCount", views.size());
            resultData.put("projectRouteHints", buildProjectRouteHints());
            resultData.put("timestamp", System.currentTimeMillis());

            sendResult(message, resultData);
            log.info("[企业微信UI映射] 完成 - 用户: {}, 视图数: {}", userId, views.size());

        } catch (Exception e) {
            log.error("[企业微信UI映射] 失败 - 用户: {}, 请求: {}", userId, requestId, e);
            sendErrorResult(message, "UI 映射采集失败: " + e.getMessage());
        } finally {
            releaseSession(session, "UI映射");
            userLock.unlock();
        }
    }

    /** 与企微后台常见入口对应的采集顺序 */
    private static final String[] AI_HELPER_UI_MAP_ROUTES = new String[] {
            AIHELPER_URL,
            "https://work.weixin.qq.com/wework_admin/frame#/aiHelper/list?tab=workflow",
            "https://work.weixin.qq.com/wework_admin/frame#/aiHelper/manage",
    };

    // =========================================================================
    // QYWEIXIN_CAPABILITY_CATALOG：程序化能力清单（项目映射用）
    // =========================================================================

    /**
     * 返回企微相关 Engine 消息类型清单（不含实现），便于 Admin/UI 与 {@link #buildProjectRouteHints} 对表。
     */
    @OnceCapability(
        type = "QYWEIXIN_CAPABILITY_CATALOG",
        description = "企微智能机器人相关 Engine 能力类型与载荷说明（静态清单）",
        timeout = 10000L
    )
    public void handleCapabilityCatalog(EngineMessage message) {
        String userId = message.getUserId();
        String requestId = extractRoutingField(message, "requestId");
        log.info("[企业微信能力清单] 用户: {}, 请求: {}", userId, requestId);
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("entries", QyWeixinCapabilityCatalog.asList());
            data.put("projectRouteHints", buildProjectRouteHints());
            data.put("inventorySchemaVersion", QyWeixinUiInventoryScript.SCHEMA_VERSION);
            data.put("timestamp", System.currentTimeMillis());
            sendResult(message, data);
        } catch (Exception e) {
            sendErrorResult(message, "能力清单失败: " + e.getMessage());
        }
    }

    /** 项目侧路由/菜单与 Engine 能力对照（人工维护，与采集结果一并返回） */
    private static List<Map<String, String>> buildProjectRouteHints() {
        List<Map<String, String>> list = new ArrayList<>();
        putHint(list, "workflow", "#/aiHelper/list?tab=workflow", "QYWEIXIN_WORKFLOW_LIST, QYWEIXIN_WORKFLOW_OPEN_EDITOR, QYWEIXIN_WORKFLOW_CREATE, QYWEIXIN_WORKFLOW_IMPORT_DRAFT, QYWEIXIN_WORKFLOW_EXPORT_FULL, QYWEIXIN_WORKFLOW_CLONE_PUBLISH, QYWEIXIN_WORKFLOW_LEARNING_FEEDBACK, QYWEIXIN_WORKFLOW_PUBLISH, QYWEIXIN_WORKFLOW_VALIDATE, QYWEIXIN_WORKFLOW_EDITOR_PROBE, QYWEIXIN_WORKFLOW_DELETE");
        putHint(list, "manage", "#/aiHelper/manage", "QYWEIXIN_LIST_ROBOTS");
        putHint(list, "lab_explore", "QYWEIXIN_EXPLORE_* / CATALOG", "QYWEIXIN_EXPLORE_AIHELPER, QYWEIXIN_EXPLORE_WORKFLOW, QYWEIXIN_EXPLORE_UI_MAP, QYWEIXIN_CAPABILITY_CATALOG");
        putHint(list, "login", "扫码/会话", "QYWEIXIN_CHECK_LOGIN, QYWEIXIN_SCAN_LOGIN(流式)");
        return list;
    }

    private static void putHint(List<Map<String, String>> list, String pageId, String backendRef, String capabilities) {
        Map<String, String> m = new HashMap<>();
        m.put("pageId", pageId);
        m.put("backendRef", backendRef);
        m.put("engineCapabilities", capabilities);
        list.add(m);
    }

    // =========================================================================
    // 私有辅助方法 - DOM 分析
    // =========================================================================

    /**
     * 提取机器人列表（多选择器兜底策略）
     */
    private List<Map<String, String>> extractRobotList(Page page) {
        List<Map<String, String>> robots = new ArrayList<>();
        try {
            String[] candidateSelectors = {
                ".aiHelper-list .aiHelper-item",
                ".robot-list .robot-item",
                ".hl_list_content .hl_lc_line",
                "[class*='aiHelper'][class*='item']",
                "[class*='robot'][class*='item']",
                "[class*='helper'][class*='card']",
                ".list-item",
                "tbody tr",
                ".item-wrap",
                "[class*='bot'][class*='item']",
                "[class*='manage'][class*='item']",
            };

            for (String selector : candidateSelectors) {
                Locator items = page.locator(selector);
                if (items.count() > 0) {
                    log.info("[企业微信AI助手] 选择器 '{}' 找到 {} 个机器人", selector, items.count());
                    int count = Math.min(items.count(), 20);
                    for (int i = 0; i < count; i++) {
                        try {
                            Locator item = items.nth(i);
                            String text = item.textContent().trim();
                            if (text.length() > 200) text = text.substring(0, 200) + "...";
                            Map<String, String> robot = new HashMap<>();
                            robot.put("selector", selector);
                            robot.put("text", text);
                            robot.put("index", String.valueOf(i));
                            robots.add(robot);
                        } catch (Exception e) {
                            log.debug("[企业微信AI助手] 第{}项解析失败: {}", i, e.getMessage());
                        }
                    }
                    break;
                }
            }

            // evaluate 兜底
            if (robots.isEmpty()) {
                log.info("[企业微信AI助手] 常规选择器未找到，尝试 evaluate...");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> jsResult = (List<Map<String, Object>>) page.evaluate(
                    "() => {\n" +
                    "  const sels = ['.aiHelper-item','[class*=\"helper\"][class*=\"item\"]'," +
                    "   '[class*=\"robot\"][class*=\"item\"]','.list-item','.item-name'," +
                    "   '[class*=\"bot\"][class*=\"item\"]'];\n" +
                    "  for (const sel of sels) {\n" +
                    "    const els = document.querySelectorAll(sel);\n" +
                    "    if (els.length > 0) {\n" +
                    "      return [...els].map((el, i) => ({index: i, text: el.textContent?.trim()?.substring(0, 150), selector: sel}));\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return [];\n" +
                    "}"
                );
                if (jsResult != null) {
                    for (Map<String, Object> item : jsResult) {
                        Map<String, String> robot = new HashMap<>();
                        item.forEach((k, v) -> robot.put(k, String.valueOf(v)));
                        robots.add(robot);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[企业微信AI助手] 提取机器人列表失败: {}", e.getMessage());
        }
        return robots;
    }

    /**
     * 提取工作流列表
     */
    private List<Map<String, String>> extractWorkflowList(Page page) {
        List<Map<String, String>> workflows = new ArrayList<>();
        try {
            String[] selectors = {
                "[class*='workflow'][class*='item']",
                "[class*='flow'][class*='item']",
                "[class*='workflow'][class*='card']",
                "[class*='flow'][class*='card']",
                ".workflow-list .workflow-item",
                ".flow-list .flow-item",
                "[class*='workflow'][class*='list'] > *",
                "tbody tr",
                ".list-item",
            };

            for (String selector : selectors) {
                Locator items = page.locator(selector);
                if (items.count() > 0) {
                    log.info("[企业微信工作流] 选择器 '{}' 找到 {} 个工作流", selector, items.count());
                    int count = Math.min(items.count(), 30);
                    for (int i = 0; i < count; i++) {
                        try {
                            String text = items.nth(i).textContent().trim();
                            if (text.length() > 300) text = text.substring(0, 300) + "...";
                            Map<String, String> wf = new HashMap<>();
                            wf.put("selector", selector);
                            wf.put("text", text);
                            wf.put("index", String.valueOf(i));
                            workflows.add(wf);
                        } catch (Exception ignored) {}
                    }
                    break;
                }
            }

            // evaluate 兜底（工作流相关关键词）
            if (workflows.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> jsResult = (List<Map<String, Object>>) page.evaluate(
                    "() => {\n" +
                    "  const sels = ['[class*=\"workflow\"]','[class*=\"flow\"][class*=\"item\"]'," +
                    "   '[class*=\"flow\"][class*=\"card\"]','.list-item'];\n" +
                    "  for (const sel of sels) {\n" +
                    "    const els = document.querySelectorAll(sel);\n" +
                    "    if (els.length > 0) {\n" +
                    "      return [...els].slice(0,30).map((el, i) => ({\n" +
                    "        index: i,\n" +
                    "        text: el.textContent?.trim()?.substring(0, 200),\n" +
                    "        selector: sel,\n" +
                    "        classes: el.className?.substring(0, 100)\n" +
                    "      }));\n" +
                    "    }\n" +
                    "  }\n" +
                    "  return [];\n" +
                    "}"
                );
                if (jsResult != null) {
                    for (Map<String, Object> item : jsResult) {
                        Map<String, String> wf = new HashMap<>();
                        item.forEach((k, v) -> wf.put(k, String.valueOf(v)));
                        workflows.add(wf);
                    }
                }
            }
        } catch (Exception e) {
            log.error("[企业微信工作流] 提取工作流列表失败: {}", e.getMessage());
        }
        return workflows;
    }

    /**
     * 分析工作流页面的关键区域
     */
    private Map<String, Object> analyzeWorkflowPage(Page page) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 查找"新建工作流"按钮
            Locator createBtn = page.locator(
                "button:has-text('新建工作流'), button:has-text('创建工作流'), " +
                "[class*='create'][class*='btn'], [class*='new'][class*='btn']"
            );
            if (createBtn.count() > 0) {
                try {
                    result.put("createBtnText", createBtn.first().textContent().trim());
                    result.put("createBtnClasses", createBtn.first().evaluate("el => el.className").toString());
                    result.put("hasCreateBtn", true);
                } catch (Exception ignored) {}
            } else {
                result.put("hasCreateBtn", false);
            }

            // 查找工作流相关区域的全文
            @SuppressWarnings("unchecked")
            Map<String, Object> jsResult = (Map<String, Object>) page.evaluate(
                "() => {\n" +
                "  // 查找工作流相关的顶层容器\n" +
                "  const containers = document.querySelectorAll(\n" +
                "    '[class*=\"workflow\"], [class*=\"flow\"], .frameV2_main, .main-content'\n" +
                "  );\n" +
                "  const mainText = containers.length > 0\n" +
                "    ? containers[0].textContent?.trim()?.substring(0, 1000)\n" +
                "    : document.body.textContent?.trim()?.substring(0, 1000);\n" +
                "\n" +
                "  // 统计按钮\n" +
                "  const buttons = [...document.querySelectorAll('button, [role=\"button\"]')]\n" +
                "    .map(b => b.textContent?.trim())\n" +
                "    .filter(t => t && t.length > 0 && t.length < 20);\n" +
                "\n" +
                "  // 查找空状态（无工作流时的提示）\n" +
                "  const emptyEl = document.querySelector(\n" +
                "    '[class*=\"empty\"], [class*=\"no-data\"], [class*=\"noData\"]'\n" +
                "  );\n" +
                "\n" +
                "  return {\n" +
                "    mainText: mainText,\n" +
                "    buttons: buttons.slice(0, 15),\n" +
                "    isEmpty: !!emptyEl,\n" +
                "    emptyText: emptyEl ? emptyEl.textContent?.trim()?.substring(0, 200) : null,\n" +
                "    bodyText: document.body.textContent?.trim()?.substring(0, 1500)\n" +
                "  };\n" +
                "}"
            );
            if (jsResult != null) {
                result.putAll(jsResult);
            }

        } catch (Exception e) {
            log.error("[企业微信工作流] 深度分析失败: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 提取页面所有链接
     */
    private List<Map<String, String>> extractLinks(Page page) {
        List<Map<String, String>> links = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> jsLinks = (List<Map<String, Object>>) page.evaluate(
                "() => [...document.querySelectorAll('a, button, [role=\"button\"], [role=\"tab\"]')]\n" +
                "  .map(el => ({tag: el.tagName, text: el.textContent?.trim()?.substring(0, 80)," +
                "    href: el.href || '', classes: el.className?.substring(0, 100)}))\n" +
                "  .filter(l => l.text && l.text.length > 0)\n" +
                "  .slice(0, 60)"
            );
            if (jsLinks != null) {
                for (Map<String, Object> item : jsLinks) {
                    Map<String, String> link = new HashMap<>();
                    item.forEach((k, v) -> link.put(k, String.valueOf(v)));
                    links.add(link);
                }
            }
        } catch (Exception e) {
            log.debug("[企业微信AI助手] 提取链接失败: {}", e.getMessage());
        }
        return links;
    }

    /**
     * 提取关键 DOM 结构摘要
     */
    private String extractDomSummary(Page page) {
        try {
            return (String) page.evaluate(
                "() => {\n" +
                "  const getStructure = (el, depth) => {\n" +
                "    if (depth > 3 || !el) return '';\n" +
                "    const tag = el.tagName?.toLowerCase() || '';\n" +
                "    const classes = [...el.classList].join(' ');\n" +
                "    const id = el.id ? '#' + el.id : '';\n" +
                "    const label = `${tag}${id}${classes ? '.' + classes.replace(/\\s+/g, '.') : ''}`;\n" +
                "    const children = [...el.children].map(c => getStructure(c, depth + 1)).filter(Boolean).join('\\n' + '  '.repeat(depth + 1));\n" +
                "    return `${'  '.repeat(depth)}${label}${children ? '\\n' + '  '.repeat(depth + 1) + children : ''}`;\n" +
                "  };\n" +
                "  const main = document.querySelector('#main, main, .main-content, .app-body, [class*=\"main\"], body');\n" +
                "  return getStructure(main || document.body, 0)?.substring(0, 3000);\n" +
                "}"
            );
        } catch (Exception e) {
            log.debug("[企业微信AI助手] 提取DOM结构失败: {}", e.getMessage());
            return "DOM提取失败: " + e.getMessage();
        }
    }

    /**
     * 提取 body HTML 样本
     */
    private String extractHtmlSample(Page page, int maxLength) {
        try {
            return (String) page.evaluate(
                "() => document.body.innerHTML.substring(0, " + maxLength + ")"
            );
        } catch (Exception e) {
            log.debug("[企业微信AI助手] 提取HTML样本失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 深度分析 AI 助手页面（侧边栏 Tab、主内容、工作流元素）
     */
    private Map<String, Object> analyzeAiHelperPage(Page page) {
        Map<String, Object> result = new HashMap<>();
        try {
            // 侧边栏
            Locator sidebar = page.locator(
                ".sidebar_menu, .side-menu, [class*='sidebar'], [class*='side-nav']"
            ).first();
            if (sidebar.count() > 0) {
                result.put("hasSidebar", true);
                try {
                    String text = sidebar.textContent().trim();
                    result.put("sidebarText", text.length() > 500 ? text.substring(0, 500) : text);
                } catch (Exception ignored) {}
            } else {
                result.put("hasSidebar", false);
            }

            // Tab 列表
            Locator tabs = page.locator(
                "[role='tab'], .tab-item, [class*='tab'][class*='item'], .menu_item"
            );
            List<String> tabTexts = new ArrayList<>();
            for (int i = 0; i < Math.min(tabs.count(), 10); i++) {
                try { tabTexts.add(tabs.nth(i).textContent().trim()); } catch (Exception ignored) {}
            }
            result.put("tabs", tabTexts);

            // 主内容区
            Locator mainContent = page.locator(
                "[class*='main-content'], [class*='main_content'], #main, .content-wrap"
            ).first();
            if (mainContent.count() > 0) {
                try {
                    String text = mainContent.textContent().trim();
                    result.put("mainContentText", text.length() > 500 ? text.substring(0, 500) + "..." : text);
                } catch (Exception ignored) {}
            }

            // 工作流相关元素
            Locator workflowEls = page.locator(
                "[class*='workflow'], [class*='flow'], a:has-text('工作流'), button:has-text('工作流')"
            );
            List<Map<String, String>> wfList = new ArrayList<>();
            for (int i = 0; i < Math.min(workflowEls.count(), 10); i++) {
                try {
                    Locator el = workflowEls.nth(i);
                    Map<String, String> info = new HashMap<>();
                    info.put("text", el.textContent().trim().substring(0, Math.min(100, el.textContent().trim().length())));
                    info.put("tag", el.evaluate("el => el.tagName").toString());
                    info.put("classes", el.evaluate("el => el.className").toString()
                            .substring(0, Math.min(100, el.evaluate("el => el.className").toString().length())));
                    wfList.add(info);
                } catch (Exception ignored) {}
            }
            result.put("workflowElements", wfList);

        } catch (Exception e) {
            log.error("[企业微信AI助手] 深度分析失败: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        return result;
    }

    // =========================================================================
    // 私有辅助方法 - Session / Lock / Message
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
            log.warn("[企业微信会话] 固定实例失效，重建 - 用户: {}", userId);
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
                log.warn("[企业微信{}] 归还会话失败: {}", context, e.getMessage());
            }
        }
    }

    private boolean isTargetClosed(Exception ex) {
        if (ex == null || ex.getMessage() == null) return false;
        String msg = ex.getMessage().toLowerCase();
        return msg.contains("target page, context or browser has been closed")
            || msg.contains("targetclosederror")
            || msg.contains("browser has been closed");
    }

    private record SessionPage(BrowserSession session, Page page) {}

    private void sendResult(EngineMessage message, Map<String, Object> data) {
        String userId          = message.getUserId();
        String requestId       = extractRoutingField(message, "requestId");
        String sourceType      = extractRoutingField(message, "sourceType");
        String sourceClientId  = extractRoutingField(message, "sourceClientId");

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
        String userId          = message.getUserId();
        String requestId       = extractRoutingField(message, "requestId");
        String sourceType      = extractRoutingField(message, "sourceType");
        String sourceClientId  = extractRoutingField(message, "sourceClientId");

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
        log.error("[企业微信AI助手] 发送错误 - 用户: {}, 错误: {}", userId, errorMessage);
    }

    /**
     * Admin {@code POST /ws/engine/request} 将 requestId、sourceType、sourceClientId 置于 payload；
     * 直连 WebSocket 可能在 JSON 顶层。二者都需支持，否则 HTTP 等不到 TASK_RESULT。
     */
    private String extractRoutingField(EngineMessage message, String key) {
        String v = extractTopLevel(message, key);
        if (v != null && !v.isBlank()) {
            return v;
        }
        try {
            Object o = message.getPayloadValue(key);
            if (o != null) {
                String s = o.toString();
                if (!s.isBlank()) {
                    return s;
                }
            }
        } catch (Exception ignored) {
        }
        return extractFromPayloadJson(message, key);
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

    private String extractFromPayloadJson(EngineMessage message, String key) {
        try {
            String raw = message.getRawJson();
            if (raw == null || raw.isBlank()) return null;
            com.alibaba.fastjson2.JSONObject root = com.alibaba.fastjson2.JSON.parseObject(raw);
            if (root == null) return null;
            com.alibaba.fastjson2.JSONObject payload = root.getJSONObject("payload");
            return payload != null ? payload.getString(key) : null;
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
                log.info("[企业微信AI助手截图] 上传成功 - URL: {}", result.getUrl());
                return result.getUrl();
            } else {
                log.error("[企业微信AI助手截图] 上传失败: {}", result.getErrorMessage());
                return null;
            }
        } catch (Exception e) {
            log.error("[企业微信AI助手截图] 截图失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
