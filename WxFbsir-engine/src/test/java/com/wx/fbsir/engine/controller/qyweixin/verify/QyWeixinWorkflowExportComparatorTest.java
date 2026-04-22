package com.wx.fbsir.engine.controller.qyweixin.verify;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QyWeixinWorkflowExportComparatorTest {

    @Test
    void alignStripsTerminalNodesAndKeepsBusinessOrderLabels() {
        Map<String, Object> e1 = row("接收问题变量", "设置变量");
        Map<String, Object> e2 = row("大模型生成回答", "大模型问答");
        Map<String, Object> e3 = row("返回结果", "结束");
        List<Map<String, Object>> expected = List.of(e1, e2, e3);

        List<Map<String, Object>> imported = List.of(
                row("开始可配置参数用户后续流程调用", "任意"),
                row("接收问题变量克隆文案", "设置变量"),
                row("大模型生成回答克隆", "大模型问答"),
                row("返回结果克隆", "结束"),
                row("结束可配置输出参数给到智能机器人", "任意")
        );

        List<Map<String, Object>> aligned =
                QyWeixinWorkflowExportComparator.alignImportedRowsForDraftVerification(expected, imported);

        assertEquals(3, aligned.size());
        assertEquals("接收问题变量克隆文案", aligned.get(0).get("labelLine"));
        assertEquals("大模型生成回答克隆", aligned.get(1).get("labelLine"));
        assertEquals("返回结果克隆", aligned.get(2).get("labelLine"));
    }

    @Test
    void compareAfterAlignPassesWhenMenusMatch() {
        Map<String, Object> e1 = row("接收问题变量", "设置变量");
        Map<String, Object> e2 = row("大模型生成回答", "大模型问答");
        List<Map<String, Object>> expected = List.of(e1, e2);

        List<Map<String, Object>> imported = List.of(
                row("开始可配置参数XXX", ""),
                row("接收问题变量", "设置变量"),
                row("大模型生成回答", "大模型问答"),
                row("结束可配置输出YYY", "")
        );

        List<Map<String, Object>> aligned =
                QyWeixinWorkflowExportComparator.alignImportedRowsForDraftVerification(expected, imported);
        Map<String, Object> report = QyWeixinWorkflowExportComparator.compare(expected, aligned);

        assertTrue(Boolean.TRUE.equals(report.get("sizeMatch")));
        assertTrue(Boolean.TRUE.equals(report.get("pass")), report.toString());
    }

    @Test
    void draftImportSemanticPassesWhenCanvasUsesLongPresetCardText() {
        Map<String, Object> e1 = row("接收问题变量", "设置变量");
        Map<String, Object> e2 = row("大模型生成回答", "大模型问答");
        Map<String, Object> e3 = row("返回结果", "结束");
        List<Map<String, Object>> expected = List.of(e1, e2, e3);

        List<Map<String, Object>> imported = List.of(
                row("设置变量对已有变量赋值或创建新的变量", "设置变量"),
                row("大模型问答可根据知识库与用户问题生成回答", "大模型问答"),
                row("结束可配置输出参数给到智能机器人", "结束"));

        List<Map<String, Object>> aligned =
                QyWeixinWorkflowExportComparator.alignImportedRowsForDraftVerification(expected, imported);
        Map<String, Object> report =
                QyWeixinWorkflowExportComparator.compare(expected, aligned, true);

        assertTrue(Boolean.TRUE.equals(report.get("pass")), report.toString());
    }

    private static Map<String, Object> row(String labelLine, String inferredMenu) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labelLine", labelLine);
        m.put("inferredMenu", inferredMenu);
        m.put("panelSnapshot", Map.of());
        return m;
    }
}
