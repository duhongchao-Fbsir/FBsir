package com.wx.fbsir.engine.controller.qyweixin.mapping;

import com.wx.fbsir.engine.WxFbsirEngineApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * qyweixin-workflow-editor-node-mapping.json 已装入且含 FBS-IR 对齐的菜单文案（大模型问答/知识库问答）。
 */
@SpringBootTest(classes = WxFbsirEngineApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "wxfbsir.engine.ws-url=ws://127.0.0.1:59999/engine-placeholder",
        "spring.main.lazy-initialization=true"
})
class QyWeixinWorkflowEditorNodeMappingTest {

    @Autowired
    private QyWeixinWorkflowEditorNodeMapping mapping;

    @Test
    void yaml_global_fallback_contains_wecom_labels() {
        List<String> fb = mapping.getGlobalMenuFallback();
        assertFalse(fb.isEmpty());
        String joined = String.join(",", fb);
        assertTrue(
                joined.contains("大模型") || joined.contains("问答"),
                "回退菜单应含企微真实文案"
        );
    }

    @Test
    void add_panel_triggers_not_empty() {
        assertFalse(mapping.getAddPanelTriggers().isEmpty());
    }
}
