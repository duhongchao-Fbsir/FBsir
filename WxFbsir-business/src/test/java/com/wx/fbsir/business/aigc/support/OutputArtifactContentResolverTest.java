package com.wx.fbsir.business.aigc.support;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OutputArtifactContentResolverTest {

    @Test
    void results_multiAi_ok() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("aiType", "deepseek");
        r1.put("answer", "hello");
        Map<String, Object> r2 = new HashMap<>();
        r2.put("aiType", "gitee");
        r2.put("textContent", "world");
        Map<String, Object> data = new HashMap<>();
        data.put("results", List.of(r1, r2));
        OutputArtifactContentResolver.Resolution res = OutputArtifactContentResolver.resolve(data);
        assertTrue(res.isOk());
        assertTrue(res.getSummaryText().contains("deepseek"));
        assertTrue(res.getSummaryText().contains("hello"));
    }

    @Test
    void results_all_empty_fallback_nested_answer_ok() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("aiType", "deepseek");
        r1.put("answer", "");
        Map<String, Object> inner = new HashMap<>();
        inner.put("answer", "from nested");
        Map<String, Object> data = new HashMap<>();
        data.put("results", List.of(r1));
        data.put("data", inner);
        OutputArtifactContentResolver.Resolution res = OutputArtifactContentResolver.resolve(data);
        assertTrue(res.isOk());
        assertEquals("from nested", res.getSummaryText());
    }

    @Test
    void results_all_empty_no_fallback_fails() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("aiType", "deepseek");
        r1.put("answer", "");
        Map<String, Object> data = new HashMap<>();
        data.put("results", List.of(r1));
        OutputArtifactContentResolver.Resolution res = OutputArtifactContentResolver.resolve(data);
        assertFalse(res.isOk());
    }

    @Test
    void legacy_nested_data_answer() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("answer", "legacy");
        Map<String, Object> data = new HashMap<>();
        data.put("data", inner);
        OutputArtifactContentResolver.Resolution res = OutputArtifactContentResolver.resolve(data);
        assertTrue(res.isOk());
        assertEquals("legacy", res.getSummaryText());
    }
}
