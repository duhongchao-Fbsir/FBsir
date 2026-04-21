package com.wx.fbsir.engine.capability;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仿真：各 AI 能力规约扁平字段 / conversationProfile 与不支持项收集。
 */
class CapabilityNormalizerTest {

    @Test
    void doubao_profile_fast_expert_deepThinking() {
        JSONObject p = basePayload();
        p.put("conversationProfile", "fast");
        CapabilityNormalizer.NormalizedCapabilities n = CapabilityNormalizer.normalize("doubao", p);
        assertTrue(n.isFastMode());
        assertFalse(n.isExpertMode());
        assertEquals("fast", n.getDeepseekMode());

        p.put("conversationProfile", "expert");
        n = CapabilityNormalizer.normalize("doubao", p);
        assertTrue(n.isExpertMode());
        assertEquals("expert", n.getDeepseekMode());

        p = basePayload();
        p.put("conversationProfile", "deepThinking");
        n = CapabilityNormalizer.normalize("doubao", p);
        assertTrue(n.isReasoning());
        assertFalse(n.isWebSearch());
    }

    @Test
    void doubao_profile_webSearch_doesNotEnableWebSearchFlag() {
        JSONObject p = basePayload();
        p.put("conversationProfile", "webSearch");
        CapabilityNormalizer.NormalizedCapabilities n = CapabilityNormalizer.normalize("doubao", p);
        assertFalse(n.isWebSearch());
    }

    @Test
    void doubao_profile_deepThinkingPlusWebSearch_mapsToReasoningOnly() {
        JSONObject p = basePayload();
        p.put("conversationProfile", "deepThinking+webSearch");
        CapabilityNormalizer.NormalizedCapabilities n = CapabilityNormalizer.normalize("doubao", p);
        assertTrue(n.isReasoning());
        assertFalse(n.isWebSearch());
    }

    @Test
    void deepseek_profile_still_sets_webSearch() {
        JSONObject p = basePayload();
        p.put("conversationProfile", "webSearch");
        CapabilityNormalizer.NormalizedCapabilities n = CapabilityNormalizer.normalize("deepseek", p);
        assertTrue(n.isWebSearch());
    }

    @Test
    void qianwen_unsupported_options_collected() {
        JSONObject p = basePayload();
        p.put("enableDeepThinking", true);
        p.put("enableWebSearch", true);
        CapabilityNormalizer.NormalizedCapabilities n = CapabilityNormalizer.normalize("qianwen", p);
        assertTrue(n.getUnsupportedOptionIds().contains("enableDeepThinking"));
        assertTrue(n.getUnsupportedOptionIds().contains("enableWebSearch"));
    }

    @Test
    void doubao_supported_options_not_in_unsupported() {
        JSONObject p = basePayload();
        p.put("enableDeepThinking", true);
        p.put("enableFastMode", true);
        CapabilityNormalizer.NormalizedCapabilities n = CapabilityNormalizer.normalize("doubao", p);
        assertFalse(n.getUnsupportedOptionIds().contains("enableDeepThinking"));
        assertFalse(n.getUnsupportedOptionIds().contains("enableFastMode"));
    }

    @Test
    void file_upload_flat_fields() {
        JSONObject p = basePayload();
        p.put("enableFileUpload", true);
        p.put("uploadedFileUrl", "http://localhost/a.docx");
        CapabilityNormalizer.NormalizedCapabilities n = CapabilityNormalizer.normalize("mita", p);
        assertTrue(n.isFileUploadEnabled());
        assertEquals("http://localhost/a.docx", n.getFileUploadUrl());
    }

    private static JSONObject basePayload() {
        JSONObject p = new JSONObject();
        p.put("query", "hi");
        return p;
    }
}
