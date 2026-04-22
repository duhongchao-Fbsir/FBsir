package com.wx.fbsir.engine.controller.qyweixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 企微相关 Engine Once 能力清单（程序化），供 Admin/UI 与 EXPLORE 结果对表；
 * 与 {@link QyWeixinAiHelperController} / {@link QyWeixinWorkflowController} / {@link QyWeixinLoginController} 中的注解保持同步。
 */
public final class QyWeixinCapabilityCatalog {

    private QyWeixinCapabilityCatalog() {
    }

    public static List<Map<String, String>> asList() {
        List<Map<String, String>> rows = new ArrayList<>();
        row(rows, "QYWEIXIN_CHECK_LOGIN", "Once", "检测登录态", "payload 可选");
        row(rows, "QYWEIXIN_SCAN_LOGIN", "Stream", "扫码登录", "流式，非 HTTP once");
        row(rows, "QYWEIXIN_TEST_VIEW", "Stream", "测试视口截图", "流式");

        row(rows, "QYWEIXIN_EXPLORE_AIHELPER", "Once", "探索 AI 助手页 DOM", "");
        row(rows, "QYWEIXIN_LIST_ROBOTS", "Once", "管理 Tab 机器人列表", "");
        row(rows, "QYWEIXIN_EXPLORE_WORKFLOW", "Once", "工作流 Tab 列表探索", "");
        row(rows, "QYWEIXIN_EXPLORE_UI_MAP", "Once", "多路由 UI 细粒度清单", "");
        row(rows, "QYWEIXIN_CAPABILITY_CATALOG", "Once", "程序化能力清单（元）", "无页面依赖，供 Admin/UI 对表");

        row(rows, "QYWEIXIN_WORKFLOW_LIST", "Once", "工作流结构化列表", "payload: workflowIndex, workflowName, workflowCreator");
        row(rows, "QYWEIXIN_WORKFLOW_OPEN_EDITOR", "Once", "打开工作流编辑器（返回画布解读/heuristicWorkflowDraft）", "payload: migrationDraftTitle 可选");
        row(rows, "QYWEIXIN_WORKFLOW_CREATE", "Once", "新建工作流向导", "同上");
        row(rows, "QYWEIXIN_WORKFLOW_IMPORT_DRAFT", "Once", "按草稿填入创建向导", "payload: workflowDraft / workflowDraftJson; workflowLifecycle:TEMP_TEST(默认)/PRODUCTION; 临时测试完成后可自动执行列表删除(见Controller); skipTemporaryWorkflowCleanup; workflowCleanupDisplayName / workflowCleanupCreator; sourceExportRows; verifyImport; verifyMaxRounds; 返回 verifyReport + temporaryWorkflowCleanup");
        row(rows, "QYWEIXIN_WORKFLOW_VALIDATE", "Once", "工作流编辑器校验（检查/调试前置）", "同上");
        row(rows, "QYWEIXIN_WORKFLOW_EDITOR_PROBE", "Once", "编辑器诊断快照（视口/文本命中/菜单采样）", "payload: skipNavigation, setViewportWidth/Height, capturePlaywrightTrace");
        row(rows, "QYWEIXIN_WORKFLOW_DELETE", "Once", "删除工作流", "同上");
        row(rows, "QYWEIXIN_WORKFLOW_EXPORT_FULL", "Once", "完整导出（逐节点配置面板采样）", "payload: workflowIndex / workflowName / workflowCreator");
        row(rows, "QYWEIXIN_WORKFLOW_PUBLISH", "Once", "编辑器内发布", "payload: skipNavigation?, workflowIndex / workflowName / workflowCreator");
        row(rows, "QYWEIXIN_WORKFLOW_CLONE_PUBLISH", "Once", "克隆并发布", "payload: newWorkflowTitle, workflowIndex / workflowName / workflowCreator; learningCorrections? verifyClone?(默认true) verifyMaxRounds?(1-5,默认2); 返回 cloneVerify / editorWorkflowId");
        row(rows, "QYWEIXIN_WORKFLOW_LEARNING_FEEDBACK", "Once", "写入克隆纠偏记忆", "payload: learningCorrections: [{ panelTitle, panelDigest, correctMenu }]");

        return rows;
    }

    private static void row(List<Map<String, String>> rows, String type, String mode, String description, String payload) {
        Map<String, String> m = new HashMap<>();
        m.put("type", type);
        m.put("mode", mode);
        m.put("description", description);
        m.put("payload", payload == null ? "" : payload);
        rows.add(m);
    }
}
