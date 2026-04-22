package com.wx.fbsir.engine.controller.qyweixin.mapping;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 企微工作流编辑器：按草稿节点类型选择「添加操作」菜单的可配置映射。
 * <p>
 * 资源文件：{@code classpath:qyweixin-workflow-editor-node-mapping.json}
 */
@Component
public class QyWeixinWorkflowEditorNodeMapping {

    private static final Logger log = LoggerFactory.getLogger(QyWeixinWorkflowEditorNodeMapping.class);

    private static final String RESOURCE = "qyweixin-workflow-editor-node-mapping.json";

    private int maxNodesPerImportRun = 3;
    private List<String> addPanelTriggers = List.of("添加指定操作", "添加运营操作", "添加节点");
    private List<List<String>> defaultStrategies = List.of(List.of("大模型问答", "大模型回答"));
    private List<String> globalMenuFallback = List.of("大模型问答", "知识库问答", "HTTP请求");
    /** draft type -> ordered strategies (each strategy = candidate menu texts) */
    private java.util.Map<String, List<List<String>>> menuStrategiesByDraftType = Collections.emptyMap();

    @PostConstruct
    public void load() {
        ClassPathResource res = new ClassPathResource(RESOURCE);
        if (!res.exists()) {
            log.warn("[QyWeixinNodeMapping] 未找到配置文件 {}，使用内置默认值", RESOURCE);
            return;
        }
        try (InputStream in = res.getInputStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JSONObject root = JSON.parseObject(json);
            if (root == null) {
                return;
            }
            Integer max = root.getInteger("maxNodesPerImportRun");
            if (max != null && max > 0 && max <= 20) {
                this.maxNodesPerImportRun = max;
            }
            this.addPanelTriggers = readStringList(root.getJSONArray("addPanelTriggers"));
            if (this.addPanelTriggers.isEmpty()) {
                this.addPanelTriggers = List.of("添加指定操作", "添加运营操作");
            }
            this.globalMenuFallback = readStringList(root.getJSONArray("globalMenuFallback"));
            JSONObject byType = root.getJSONObject("menuStrategiesByDraftType");
            java.util.Map<String, List<List<String>>> map = new java.util.LinkedHashMap<>();
            if (byType != null) {
                for (String key : byType.keySet()) {
                    JSONArray arrOfArr = byType.getJSONArray(key);
                    if (arrOfArr == null) {
                        continue;
                    }
                    List<List<String>> strategies = new ArrayList<>();
                    for (int i = 0; i < arrOfArr.size(); i++) {
                        JSONArray one = arrOfArr.getJSONArray(i);
                        if (one == null) {
                            continue;
                        }
                        List<String> cand = new ArrayList<>();
                        for (int j = 0; j < one.size(); j++) {
                            String s = one.getString(j);
                            if (s != null && !s.isBlank()) {
                                cand.add(s.trim());
                            }
                        }
                        if (!cand.isEmpty()) {
                            strategies.add(Collections.unmodifiableList(cand));
                        }
                    }
                    if (!strategies.isEmpty()) {
                        map.put(key, Collections.unmodifiableList(strategies));
                    }
                }
            }
            this.menuStrategiesByDraftType = Collections.unmodifiableMap(map);
            List<List<String>> def = map.get("default");
            if (def != null && !def.isEmpty()) {
                this.defaultStrategies = def;
            }
            log.info("[QyWeixinNodeMapping] 已加载 {} 类型策略，每轮最多 {} 个节点", map.size(), maxNodesPerImportRun);
        } catch (IOException e) {
            log.warn("[QyWeixinNodeMapping] 读取 {} 失败: {}", RESOURCE, e.getMessage());
        }
    }

    public int getMaxNodesPerImportRun() {
        return maxNodesPerImportRun;
    }

    public List<String> getAddPanelTriggers() {
        return addPanelTriggers;
    }

    public List<String> getGlobalMenuFallback() {
        return globalMenuFallback;
    }

    /**
     * 返回按顺序尝试的策略列表：先类型专属，再 default，最后内置单条。
     */
    public List<List<String>> resolveStrategies(String draftType) {
        String t = draftType == null || draftType.isBlank() ? "default" : draftType.trim();
        List<List<String>> fromType = menuStrategiesByDraftType.get(t);
        if (fromType != null && !fromType.isEmpty()) {
            return fromType;
        }
        List<List<String>> def = menuStrategiesByDraftType.get("default");
        if (def != null && !def.isEmpty()) {
            return def;
        }
        return defaultStrategies;
    }

    private static List<String> readStringList(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            String s = arr.getString(i);
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return Collections.unmodifiableList(out);
    }
}
