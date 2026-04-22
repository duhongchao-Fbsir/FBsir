package com.wx.fbsir.engine.controller.qyweixin.support;

import com.wx.fbsir.engine.WxFbsirEngineApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * classpath:qyweixin-workflow-validation-handlers.json 加载与匹配逻辑。
 */
@SpringBootTest(classes = WxFbsirEngineApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "wxfbsir.engine.ws-url=ws://127.0.0.1:59999/engine-placeholder",
        "spring.main.lazy-initialization=true"
})
class QyWeixinValidationHintHandlersTest {

    @Autowired
    private QyWeixinValidationHintHandlers handlers;

    @Test
    void handlers_loaded_from_json() {
        assertTrue(handlers.getRoundsMaxDefault() >= 1);
    }

    @Test
    void findFirstHandler_matches_set_variable_message() {
        Map<String, Object> m = handlers.findFirstHandler("通用问题 设置变量：变量列表不能为空");
        assertFalse(m.isEmpty());
        assertEquals("set-variable-list-empty", m.get("handlerId"));
        @SuppressWarnings("unchecked")
        List<String> steps = (List<String>) m.get("steps");
        assertEquals(List.of("SET_VARIABLE_MINIMAL"), steps);
    }

    @Test
    void findFirstHandler_matches_http_placeholder() {
        Map<String, Object> m = handlers.findFirstHandler("请求地址不能为空，请填写 URL");
        assertFalse(m.isEmpty());
        assertEquals("http-url-missing", m.get("handlerId"));
    }

    @Test
    void findFirstHandler_returns_empty_when_no_match() {
        assertTrue(handlers.findFirstHandler("没有任何已知关键词").isEmpty());
    }
}
