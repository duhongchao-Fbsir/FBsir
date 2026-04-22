package com.wx.fbsir.engine.controller.qyweixin.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载 {@code qyweixin-workflow-validation-handlers.json}：根据页面校验提示匹配占位处理链。
 */
@Component
public class QyWeixinValidationHintHandlers {

    private static final Logger log = LoggerFactory.getLogger(QyWeixinValidationHintHandlers.class);
    private static final String RESOURCE = "qyweixin-workflow-validation-handlers.json";

    private List<HandlerEntry> handlers = List.of();
    private int roundsMaxDefault = 6;

    @PostConstruct
    public void load() {
        ClassPathResource res = new ClassPathResource(RESOURCE);
        if (!res.exists()) {
            log.warn("[QywxValidationHints] 未找到 {}，占位扫尾不可用", RESOURCE);
            return;
        }
        try (InputStream in = res.getInputStream()) {
            JSONObject root = JSON.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (root == null) {
                return;
            }
            Integer rm = root.getInteger("roundsMaxDefault");
            if (rm != null && rm > 0 && rm <= 30) {
                this.roundsMaxDefault = rm;
            }
            JSONArray arr = root.getJSONArray("handlers");
            if (arr == null || arr.isEmpty()) {
                return;
            }
            List<HandlerEntry> list = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject h = arr.getJSONObject(i);
                if (h == null) {
                    continue;
                }
                String id = h.getString("id");
                List<String> matchAny = readStrings(h.getJSONArray("matchAny"));
                List<String> steps = readStrings(h.getJSONArray("steps"));
                if (id != null && !matchAny.isEmpty() && !steps.isEmpty()) {
                    list.add(new HandlerEntry(id, matchAny, steps));
                }
            }
            this.handlers = Collections.unmodifiableList(list);
            log.info("[QywxValidationHints] 已加载 {} 条校验提示处理规则", handlers.size());
        } catch (IOException e) {
            log.warn("[QywxValidationHints] 读取 {} 失败: {}", RESOURCE, e.getMessage());
        }
    }

    private static List<String> readStrings(JSONArray a) {
        if (a == null || a.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            String s = a.getString(i);
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    public int getRoundsMaxDefault() {
        return roundsMaxDefault;
    }

    /**
     * 在聚合后的页面提示文本中，找到第一个命中的处理器（按文件顺序）。
     */
    public Map<String, Object> findFirstHandler(String combinedHints) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (combinedHints == null || combinedHints.isBlank() || handlers.isEmpty()) {
            return m;
        }
        for (HandlerEntry h : handlers) {
            for (String needle : h.matchAny()) {
                if (needle != null && combinedHints.contains(needle)) {
                    m.put("handlerId", h.id());
                    m.put("steps", h.steps());
                    m.put("matchedBy", needle);
                    return m;
                }
            }
        }
        return m;
    }

    public record HandlerEntry(String id, List<String> matchAny, List<String> steps) {}
}
