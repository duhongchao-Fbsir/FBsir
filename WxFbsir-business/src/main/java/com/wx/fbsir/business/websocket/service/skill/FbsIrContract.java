package com.wx.fbsir.business.websocket.service.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FBS-IR v1 契约定义（第0步）。
 * 该契约用于将不同来源的 Skill 统一到可迁移、可比对的数据结构。
 */
public final class FbsIrContract {

    public static final String VERSION = "fbs-ir-v1";

    private FbsIrContract() {
    }

    public static Map<String, Object> schema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("contractVersion", VERSION);
        schema.put("requiredTopLevel", requiredTopLevel());
        schema.put("fieldGuide", fieldGuide());
        return schema;
    }

    private static List<String> requiredTopLevel() {
        List<String> required = new ArrayList<>();
        required.add("contractVersion");
        required.add("skillMeta");
        required.add("intents");
        required.add("stages");
        required.add("inputs");
        required.add("gates");
        required.add("outputs");
        required.add("fallbacks");
        required.add("mappingHints");
        return required;
    }

    private static Map<String, String> fieldGuide() {
        Map<String, String> guide = new LinkedHashMap<>();
        guide.put("skillMeta", "Skill 基本信息（id/name/version/channels/scenePacks）");
        guide.put("intents", "用户可触发意图（写书、改写、质检等）");
        guide.put("stages", "流程阶段（如 S0/S1/S2/S3/S4/S5）");
        guide.put("inputs", "输入参数定义（字段名、类型、是否必填）");
        guide.put("gates", "规则门禁（阶段前置条件、质量阈值）");
        guide.put("outputs", "期望输出物（草稿、报告、交付件）");
        guide.put("fallbacks", "降级策略（离线模式、人工补充）");
        guide.put("mappingHints", "映射提示（企微工作流节点建议、降级说明）");
        return guide;
    }
}
