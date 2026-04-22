package com.wx.fbsir.business.websocket.service.skill;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skill ZIP 迁移预览服务（L1 + L2）。
 */
@Service
public class SkillMigrationPreviewService {

    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private static final List<String> GRANULE_REFINEMENT_DOCS = List.of(
        "docs/功能说明/engine/Playwright框架完整指南.md",
        "docs/功能说明/engine/WebSocket通信完整指南.md",
        "docs/功能说明/engine/登录框架使用说明.md",
        "docs/功能说明/engine/文件上传工具使用说明.md",
        "docs/功能说明/engine/engine功能/AIGC框架完整功能说明.md",
        "docs/功能说明/engine/engine功能/engine知识库功能技术接口说明.md",
        "docs/功能说明/engine/engine功能/节点编辑管理和策略管理功能说明.md",
        "docs/功能说明/engine/engine功能/知识库管理功能说明.md"
    );
    private final SkillGranuleCatalogService granuleCatalogService;

    public SkillMigrationPreviewService(SkillGranuleCatalogService granuleCatalogService) {
        this.granuleCatalogService = granuleCatalogService;
    }

    public Map<String, Object> preview(MultipartFile file) throws IOException {
        byte[] zipBytes = file.getBytes();
        Map<String, String> entries = readRelevantEntries(zipBytes);

        JSONObject pluginMeta = parseJson(entries.get("_plugin_meta.json"));
        JSONObject workbuddyManifest = parseJson(entries.get("workbuddy/channel-manifest.json"));
        JSONObject codebuddyManifest = parseJson(entries.get("codebuddy/channel-manifest.json"));
        JSONObject sceneRegistry = parseJson(entries.get("scene-packs/registry.json"));
        JSONObject officialSchema = parseJson(entries.get("scene-packs/official-schema.json"));
        String skillMarkdown = entries.get("SKILL.md");
        Map<String, String> frontMatter = parseFrontMatter(skillMarkdown);
        Map<String, Object> sceneFlow = extractSceneFlow(entries, sceneRegistry);
        augmentSceneFlowBySkillMarkdown(sceneFlow, skillMarkdown);

        Map<String, Object> ir = buildIr(pluginMeta, workbuddyManifest, codebuddyManifest, sceneRegistry, officialSchema, frontMatter, sceneFlow);
        Map<String, Object> report = buildMigrationReport(pluginMeta, workbuddyManifest, codebuddyManifest, ir);
        Map<String, Object> workflowDraft = buildWorkflowDraft(ir, report);
        List<Map<String, Object>> runtimeValidation = buildRuntimeValidation(pluginMeta, ir, workflowDraft);
        report.put("runtimeValidation", runtimeValidation);
        report.put("runtimeValidationSummary", summarizeRuntimeValidation(runtimeValidation));
        Map<String, Object> migrationInsights = buildMigrationInsights(ir, report, workflowDraft, runtimeValidation);
        report.put("insights", migrationInsights);
        @SuppressWarnings("unchecked")
        Map<String, Object> skillMeta = (Map<String, Object>) ir.get("skillMeta");
        String skillId = extractSkillId(skillMeta);
        List<Map<String, Object>> granules = buildGranules(ir, report, workflowDraft, runtimeValidation);
        Map<String, Object> writeback = granuleCatalogService.upsert(skillId, skillMeta, granules, file.getOriginalFilename());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", FbsIrContract.schema());
        payload.put("skillMeta", ir.get("skillMeta"));
        payload.put("ir", ir);
        payload.put("migrationReport", report);
        payload.put("workflowDraft", workflowDraft);
        payload.put("runtimeValidation", runtimeValidation);
        payload.put("insights", migrationInsights);
        payload.put("granuleRefinement", buildGranuleRefinementMeta());
        payload.put("granuleCatalog", granuleCatalogService.getBySkillId(skillId));
        payload.put("writeback", writeback);
        return payload;
    }

    public List<Map<String, Object>> listRecentGranules(int limit) {
        return granuleCatalogService.listRecent(limit);
    }

    public Map<String, Object> getSkillGranules(String skillId) {
        return granuleCatalogService.getBySkillId(skillId);
    }

    public Map<String, Object> appendValidationWriteback(String skillId, Map<String, Object> validationResult) {
        List<Map<String, Object>> granules = buildValidationGranules(validationResult);
        if (granules.isEmpty()) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("granuleType", "WORKFLOW_VALIDATE");
            fallback.put("status", "unknown");
            fallback.put("key", "empty-validation-result");
            fallback.put("label", "未提取到有效校验信息");
            fallback.put("sourcePath", "validationResult");
            granules.add(fallback);
        }
        return granuleCatalogService.appendGranules(skillId, granules, "workflow-validate-writeback");
    }

    private String extractSkillId(Map<String, Object> skillMeta) {
        if (skillMeta == null) {
            return "unknown-skill";
        }
        Object id = skillMeta.get("id");
        String sid = id == null ? "" : String.valueOf(id).trim();
        return sid.isEmpty() ? "unknown-skill" : sid;
    }

    private List<Map<String, Object>> buildGranules(Map<String, Object> ir,
                                                    Map<String, Object> report,
                                                    Map<String, Object> workflowDraft,
                                                    List<Map<String, Object>> runtimeValidation) {
        List<Map<String, Object>> granules = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inputs = (List<Map<String, Object>>) ir.get("inputs");
        for (Map<String, Object> input : safeList(inputs)) {
            addGranule(granules, "INPUT_PARAM", "mapped", input.get("key"), input.get("note"), "ir.inputs");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gates = (List<Map<String, Object>>) ir.get("gates");
        for (Map<String, Object> gate : safeList(gates)) {
            addGranule(granules, "GATE_RULE", String.valueOf(gate.getOrDefault("status", "degraded")),
                gate.get("id"), gate.get("description"), "ir.gates");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) ir.get("outputs");
        for (Map<String, Object> output : safeList(outputs)) {
            addGranule(granules, "OUTPUT_ARTIFACT", String.valueOf(output.getOrDefault("status", "mapped")),
                output.get("id"), output.get("description"), "ir.outputs");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, String>> degraded = (List<Map<String, String>>) report.get("degraded");
        for (Map<String, String> row : safeList(degraded)) {
            addGranule(granules, "MIGRATION_RISK", "degraded", row.get("code"), row.get("note"), "report.degraded");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, String>> unsupported = (List<Map<String, String>>) report.get("unsupported");
        for (Map<String, String> row : safeList(unsupported)) {
            addGranule(granules, "MIGRATION_RISK", "unsupported", row.get("code"), row.get("note"), "report.unsupported");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) workflowDraft.get("nodes");
        for (Map<String, Object> node : safeList(nodes)) {
            addGranule(granules, "WORKFLOW_NODE_TEMPLATE", "mapped", node.get("id"), node.get("label"), "workflowDraft.nodes");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endOutputs = (List<Map<String, Object>>) workflowDraft.get("endOutputs");
        for (Map<String, Object> output : safeList(endOutputs)) {
            addGranule(granules, "WORKFLOW_END_OUTPUT", "mapped", output.get("name"), output.get("description"), "workflowDraft.endOutputs");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> invokeHints = (List<Map<String, Object>>) workflowDraft.get("invokeHints");
        for (Map<String, Object> hint : safeList(invokeHints)) {
            addGranule(granules, "WORKFLOW_INVOKE_HINT", "mapped", hint.get("id"), hint.get("tip"), "workflowDraft.invokeHints");
        }
        for (Map<String, Object> check : safeList(runtimeValidation)) {
            addGranule(granules, "RUNTIME_LIMIT_CHECK",
                String.valueOf(check.getOrDefault("status", "check_required")),
                check.get("id"), check.get("description"), "runtimeValidation");
        }
        return granules;
    }

    private void addGranule(List<Map<String, Object>> out,
                            String granuleType,
                            String status,
                            Object key,
                            Object label,
                            String sourcePath) {
        String normalizedKey = key == null ? "" : String.valueOf(key).trim();
        if (normalizedKey.isEmpty()) {
            return;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("granuleType", granuleType);
        row.put("status", status == null || status.isBlank() ? "mapped" : status.trim());
        row.put("key", normalizedKey);
        row.put("label", label == null ? "" : String.valueOf(label));
        row.put("sourcePath", sourcePath);
        row.put("domain", inferDomain(granuleType));
        row.put("lifecycle", inferLifecycle(sourcePath));
        row.put("executor", inferExecutor(granuleType));
        row.put("priority", inferPriority(status));
        row.put("docRefs", inferDocRefs(granuleType));
        out.add(row);
    }

    private String inferDomain(String granuleType) {
        if (granuleType == null) {
            return "general";
        }
        return switch (granuleType) {
            case "INPUT_PARAM", "OUTPUT_ARTIFACT", "WORKFLOW_END_OUTPUT", "WORKFLOW_NODE_TEMPLATE", "WORKFLOW_INVOKE_HINT" -> "workflow-orchestration";
            case "RUNTIME_LIMIT_CHECK", "WORKFLOW_VALIDATE_ISSUE", "WORKFLOW_VALIDATE_CHECK" -> "runtime-guardrail";
            case "MIGRATION_RISK" -> "migration-risk";
            case "GATE_RULE" -> "strategy-gate";
            default -> "general";
        };
    }

    private String inferLifecycle(String sourcePath) {
        if (sourcePath == null) {
            return "unknown";
        }
        if (sourcePath.startsWith("workflowDraft")) {
            return "design";
        }
        if (sourcePath.startsWith("ir.")) {
            return "mapping";
        }
        if (sourcePath.startsWith("report.")) {
            return "assessment";
        }
        if (sourcePath.startsWith("runtimeValidation")) {
            return "preflight";
        }
        if (sourcePath.startsWith("validate.")) {
            return "runtime-validation";
        }
        return "unknown";
    }

    private String inferExecutor(String granuleType) {
        if (granuleType == null) {
            return "business";
        }
        return switch (granuleType) {
            case "WORKFLOW_VALIDATE_ISSUE", "WORKFLOW_VALIDATE_CHECK", "RUNTIME_LIMIT_CHECK" -> "engine+playwright";
            case "INPUT_PARAM", "OUTPUT_ARTIFACT", "MIGRATION_RISK", "WORKFLOW_END_OUTPUT", "WORKFLOW_INVOKE_HINT", "WORKFLOW_NODE_TEMPLATE", "GATE_RULE" -> "business-migration";
            default -> "business";
        };
    }

    private String inferPriority(String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "unsupported", "error", "fail" -> "P0";
            case "degraded", "warn", "check_required" -> "P1";
            default -> "P2";
        };
    }

    private List<String> inferDocRefs(String granuleType) {
        if (granuleType == null) {
            return List.of();
        }
        return switch (granuleType) {
            case "RUNTIME_LIMIT_CHECK", "WORKFLOW_VALIDATE_ISSUE", "WORKFLOW_VALIDATE_CHECK" -> List.of(
                "docs/功能说明/engine/Playwright框架完整指南.md",
                "docs/功能说明/engine/WebSocket通信完整指南.md"
            );
            case "WORKFLOW_END_OUTPUT", "WORKFLOW_INVOKE_HINT", "WORKFLOW_NODE_TEMPLATE", "GATE_RULE" -> List.of(
                "docs/功能说明/engine/engine功能/节点编辑管理和策略管理功能说明.md",
                "docs/功能说明/engine/engine功能/AIGC框架完整功能说明.md"
            );
            case "INPUT_PARAM", "OUTPUT_ARTIFACT", "MIGRATION_RISK" -> List.of(
                "docs/功能说明/engine/engine功能/知识库管理功能说明.md",
                "docs/功能说明/engine/engine功能/engine知识库功能技术接口说明.md"
            );
            default -> List.of();
        };
    }

    private Map<String, Object> buildGranuleRefinementMeta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("version", "granule-refine-v2");
        meta.put("docs", GRANULE_REFINEMENT_DOCS);
        meta.put("dimensions", List.of(
            "granuleType",
            "status",
            "domain",
            "lifecycle",
            "executor",
            "priority",
            "docRefs"
        ));
        meta.put("note", "基于 engine/playwright/websocket/策略管理/知识库文档提炼");
        return meta;
    }

    private Map<String, Object> buildMigrationInsights(Map<String, Object> ir,
                                                       Map<String, Object> report,
                                                       Map<String, Object> workflowDraft,
                                                       List<Map<String, Object>> runtimeValidation) {
        Map<String, Object> insights = new LinkedHashMap<>();
        int triggerScore = calcTriggerFitnessScore(workflowDraft);
        int outputScore = calcOutputContractScore(workflowDraft);
        int automationScore = calcAutomationReadinessScore(report, runtimeValidation);
        int overall = (int) Math.round(triggerScore * 0.35 + outputScore * 0.30 + automationScore * 0.35);

        insights.put("triggerFitnessScore", triggerScore);
        insights.put("outputContractScore", outputScore);
        insights.put("automationReadinessScore", automationScore);
        insights.put("overallScore", Math.max(0, Math.min(100, overall)));
        insights.put("priorityActions", buildPriorityActions(triggerScore, outputScore, automationScore, runtimeValidation));
        insights.put("operatorChecklist", List.of(
            "先执行一键录入草稿，确认 editorMode=true",
            "执行 QYWEIXIN_WORKFLOW_VALIDATE 并回写颗粒目录",
            "按校验问题修复后再进行发布"
        ));
        return insights;
    }

    private int calcTriggerFitnessScore(Map<String, Object> workflowDraft) {
        String title = String.valueOf(workflowDraft.getOrDefault("title", ""));
        String desc = String.valueOf(workflowDraft.getOrDefault("description", ""));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hints = (List<Map<String, Object>>) workflowDraft.get("invokeHints");

        int score = 40;
        if (title.length() >= 8) score += 20;
        if (desc.length() >= 20) score += 15;
        if (hints != null && !hints.isEmpty()) score += 15;
        if (title.contains("工作流") || title.contains("问答") || title.contains("写作")) score += 10;
        return Math.min(100, score);
    }

    private int calcOutputContractScore(Map<String, Object> workflowDraft) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) workflowDraft.get("endOutputs");
        if (outputs == null || outputs.isEmpty()) {
            return 20;
        }
        int score = 40;
        score += Math.min(30, outputs.size() * 10);
        boolean hasResponse = outputs.stream().anyMatch(x -> "response".equals(String.valueOf(x.get("name"))));
        if (hasResponse) {
            score += 20;
        }
        return Math.min(100, score);
    }

    private int calcAutomationReadinessScore(Map<String, Object> report,
                                             List<Map<String, Object>> runtimeValidation) {
        int score = 70;
        @SuppressWarnings("unchecked")
        List<Map<String, String>> unsupported = (List<Map<String, String>>) report.get("unsupported");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> degraded = (List<Map<String, String>>) report.get("degraded");
        if (unsupported != null) {
            score -= unsupported.size() * 15;
        }
        if (degraded != null) {
            score -= degraded.size() * 5;
        }
        for (Map<String, Object> check : safeList(runtimeValidation)) {
            String status = String.valueOf(check.getOrDefault("status", "check_required"));
            if ("unsupported".equals(status)) score -= 10;
            else if ("degraded".equals(status)) score -= 6;
            else if ("check_required".equals(status)) score -= 3;
        }
        return Math.max(0, Math.min(100, score));
    }

    private List<Map<String, Object>> buildPriorityActions(int triggerScore,
                                                           int outputScore,
                                                           int automationScore,
                                                           List<Map<String, Object>> runtimeValidation) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (triggerScore < 80) {
            actions.add(action("P0", "优化工作流名称与描述，补充命中关键词，提升机器人触发概率"));
        }
        if (outputScore < 85) {
            actions.add(action("P0", "完善结束节点输出参数（至少 response/traceId），避免返回空内容"));
        }
        if (automationScore < 75) {
            actions.add(action("P1", "先执行校验并修复 runtimeValidation 中的 unsupported/degraded 项"));
        }
        for (Map<String, Object> c : safeList(runtimeValidation)) {
            String status = String.valueOf(c.getOrDefault("status", ""));
            if ("unsupported".equals(status)) {
                actions.add(action("P1", "存在不支持能力：" + c.get("id") + "，请保留人工兜底节点"));
            }
        }
        if (actions.isEmpty()) {
            actions.add(action("P2", "当前迁移质量较好，可直接执行录入→校验→发布闭环"));
        }
        return actions;
    }

    private Map<String, Object> action(String priority, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priority", priority);
        m.put("action", text);
        return m;
    }

    private <T> List<T> safeList(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private Map<String, String> readRelevantEntries(byte[] zipBytes) throws IOException {
        Set<String> targets = new LinkedHashSet<>(Arrays.asList(
            "SKILL.md",
            "_plugin_meta.json",
            "workbuddy/channel-manifest.json",
            "codebuddy/channel-manifest.json",
            "scene-packs/registry.json",
            "scene-packs/official-schema.json"
        ));
        Map<String, String> found = new LinkedHashMap<>();
        try (InputStream in = new ByteArrayInputStream(zipBytes);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String normalized = normalizeEntryName(entry.getName());
                String matchedTarget = matchTarget(normalized, targets);
                boolean keepAllSceneJson = isBundledScenePackDataJson(normalized);
                if (matchedTarget == null && !keepAllSceneJson) {
                    continue;
                }
                byte[] bytes = readEntryBytes(zis);
                String key = matchedTarget != null ? matchedTarget : normalized;
                found.put(key, new String(bytes, StandardCharsets.UTF_8));
            }
        }
        return found;
    }

    private String normalizeEntryName(String name) {
        if (name == null) {
            return "";
        }
        return name.replace('\\', '/');
    }

    private String matchTarget(String entryName, Set<String> targets) {
        for (String target : targets) {
            if (entryName.endsWith("/" + target) || entryName.equals(target)) {
                return target;
            }
        }
        return null;
    }

    /**
     * ZIP 常以根目录打包（如 {@code fbs-bookwriter/scene-packs/*.json}），仅靠 {@code startsWith("scene-packs/")}
     * 会漏掉真实 Workbuddy 分发包。
     */
    private boolean isBundledScenePackDataJson(String zipEntryKey) {
        if (zipEntryKey == null || !zipEntryKey.endsWith(".json")) {
            return false;
        }
        String p = zipEntryKey.replace('\\', '/');
        int idx = p.indexOf("scene-packs/");
        if (idx < 0) {
            return false;
        }
        String after = p.substring(idx + "scene-packs/".length());
        return !"registry.json".equals(after) && !"official-schema.json".equals(after);
    }

    private byte[] readEntryBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int len;
        while ((len = zis.read(buf)) > 0) {
            total += len;
            if (total > MAX_ENTRY_BYTES) {
                throw new IOException("ZIP entry too large (>" + MAX_ENTRY_BYTES + " bytes)");
            }
            out.write(buf, 0, len);
        }
        return out.toByteArray();
    }

    private JSONObject parseJson(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(text);
        } catch (Exception ex) {
            JSONObject fallback = new JSONObject();
            fallback.put("_parseError", ex.getMessage());
            return fallback;
        }
    }

    private Map<String, String> parseFrontMatter(String markdown) {
        Map<String, String> map = new LinkedHashMap<>();
        if (markdown == null || markdown.trim().isEmpty()) {
            return map;
        }
        String[] lines = markdown.split("\\r?\\n");
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            return map;
        }
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                end = i;
                break;
            }
        }
        if (end <= 1) {
            return map;
        }
        for (int i = 1; i < end; i++) {
            String line = lines[i];
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String k = line.substring(0, idx).trim();
            String v = line.substring(idx + 1).trim();
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            map.put(k, v);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private void augmentSceneFlowBySkillMarkdown(Map<String, Object> sceneFlow, String markdown) {
        if (sceneFlow == null || markdown == null || markdown.isBlank()) {
            return;
        }
        if (markdown.length() > 12000) {
            // 大型 Skill 文档通常包含运行守则/说明段落，直接回退会把说明文本误识别为流程步骤。
            return;
        }
        Object rawSteps = sceneFlow.get("steps");
        List<Map<String, Object>> current = rawSteps instanceof List<?> list
                ? (List<Map<String, Object>>) list : new ArrayList<>();
        if (current.size() >= 3) {
            return;
        }
        Object rawStages = sceneFlow.get("stages");
        List<Map<String, Object>> stages = rawStages instanceof List<?> list
                ? (List<Map<String, Object>>) list : new ArrayList<>();
        if (stages.size() >= 3) {
            // 优先走 stageFallback，避免 markdown 文本污染 sceneFlow.steps。
            return;
        }
        List<Map<String, Object>> mdSteps = extractStepsFromSkillMarkdown(markdown);
        if (mdSteps.isEmpty()) {
            return;
        }
        List<Map<String, Object>> merged = new ArrayList<>(current);
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> s : merged) {
            String id = String.valueOf(s.getOrDefault("id", "")).trim();
            String label = String.valueOf(s.getOrDefault("label", "")).trim();
            seen.add((id + "|" + label).toLowerCase(Locale.ROOT));
        }
        for (Map<String, Object> s : mdSteps) {
            String id = String.valueOf(s.getOrDefault("id", "")).trim();
            String label = String.valueOf(s.getOrDefault("label", "")).trim();
            String k = (id + "|" + label).toLowerCase(Locale.ROOT);
            if (seen.add(k)) {
                merged.add(s);
            }
        }
        sceneFlow.put("steps", normalizeSceneSteps(merged));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) sceneFlow.getOrDefault("edges", new ArrayList<>());
        if (edges.isEmpty() && merged.size() >= 2) {
            List<Map<String, Object>> linear = new ArrayList<>();
            for (int i = 1; i < merged.size(); i++) {
                String from = canonicalNodeId(String.valueOf(merged.get(i - 1).getOrDefault("id", "step_" + (i - 1))));
                String to = canonicalNodeId(String.valueOf(merged.get(i).getOrDefault("id", "step_" + i)));
                linear.add(edge(from, to));
            }
            sceneFlow.put("edges", dedupeEdges(linear));
        }
        sceneFlow.put("stats", Map.of(
                "stepCount", ((List<?>) sceneFlow.getOrDefault("steps", List.of())).size(),
                "edgeCount", ((List<?>) sceneFlow.getOrDefault("edges", List.of())).size(),
                "stageCount", ((List<?>) sceneFlow.getOrDefault("stages", List.of())).size(),
                "variableCount", ((List<?>) sceneFlow.getOrDefault("variables", List.of())).size(),
                "markdownFallbackUsed", true
        ));
    }

    private List<Map<String, Object>> extractStepsFromSkillMarkdown(String markdown) {
        List<Map<String, Object>> steps = new ArrayList<>();
        String[] lines = markdown.split("\\r?\\n");
        int seq = 0;
        for (String rawLine : lines) {
            if (rawLine == null) {
                continue;
            }
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            String label = null;
            if (line.startsWith("### ") || line.startsWith("## ")) {
                label = line.replaceFirst("^#+\\s*", "").trim();
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                label = line.substring(2).trim();
            } else if (line.matches("^\\d+[\\.)]\\s+.*")) {
                label = line.replaceFirst("^\\d+[\\.)]\\s+", "").trim();
            }
            if (label == null || label.length() < 2) {
                continue;
            }
            String low = label.toLowerCase(Locale.ROOT);
            if ("flow".equals(low) || "steps".equals(low) || "overview".equals(low)) {
                continue;
            }
            if (low.contains("copyright")
                    || low.contains("license")
                    || low.contains("changelog")
                    || low.contains("安装")
                    || low.contains("使用说明")
                    || low.contains("faq")) {
                continue;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", "md_step_" + seq);
            step.put("label", label.length() > 64 ? label.substring(0, 64) : label);
            step.put("stepType", inferStepTypeByLabel(label));
            step.put("detail", "from SKILL.md");
            step.put("sourcePath", "SKILL.md");
            steps.add(step);
            seq++;
            if (steps.size() >= 24) {
                break;
            }
        }
        return steps;
    }

    private String inferStepTypeByLabel(String label) {
        String low = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (low.contains("输入") || low.contains("参数") || low.contains("变量") || low.contains("prompt")) {
            return "input";
        }
        if (low.contains("校验") || low.contains("检查") || low.contains("审核") || low.contains("gate")) {
            return "check";
        }
        if (low.contains("输出") || low.contains("导出") || low.contains("交付")) {
            return "output";
        }
        return "process";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractSceneFlow(Map<String, String> entries, JSONObject sceneRegistry) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        List<Map<String, Object>> stages = new ArrayList<>();
        List<Map<String, Object>> variables = new ArrayList<>();

        if (entries != null) {
            for (Map.Entry<String, String> en : entries.entrySet()) {
                String path = en.getKey();
                if (!isBundledScenePackDataJson(path)) {
                    continue;
                }
                JSONObject json = parseJson(en.getValue());
                collectStages(json, stages);
                collectVariables(json, variables);
                collectStepsAndEdges(json, path, steps, edges);
                collectExplicitSceneEdges(json, edges);
            }
        }

        if (sceneRegistry != null && !sceneRegistry.isEmpty()) {
            Object packsObj = sceneRegistry.get("packs");
            if (packsObj instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    Map<String, Object> st = new LinkedHashMap<>();
                    st.put("id", key);
                    st.put("name", key);
                    st.put("required", true);
                    stages.add(st);
                }
            }
        }

        out.put("steps", normalizeSceneSteps(steps));
        out.put("edges", dedupeEdges(edges));
        out.put("stages", dedupeByKey(stages, "id"));
        out.put("variables", dedupeByKey(variables, "key"));
        out.put("stats", Map.of(
                "stepCount", ((List<?>) out.get("steps")).size(),
                "edgeCount", ((List<?>) out.get("edges")).size(),
                "stageCount", ((List<?>) out.get("stages")).size(),
                "variableCount", ((List<?>) out.get("variables")).size()
        ));
        return out;
    }

    private void collectStages(Object node, List<Map<String, Object>> out) {
        if (node instanceof JSONObject obj) {
            if (obj.containsKey("stages") && obj.get("stages") instanceof JSONArray arr) {
                for (int i = 0; i < arr.size(); i++) {
                    Object one = arr.get(i);
                    if (one instanceof JSONObject so) {
                        String id = firstNonBlank(so.getString("id"), so.getString("name"), "stage_" + (i + 1));
                        String name = firstNonBlank(so.getString("name"), so.getString("title"), id);
                        Map<String, Object> st = new LinkedHashMap<>();
                        st.put("id", id);
                        st.put("name", name);
                        st.put("required", true);
                        out.add(st);
                    } else if (one instanceof String s && !s.isBlank()) {
                        Map<String, Object> st = new LinkedHashMap<>();
                        st.put("id", s.trim());
                        st.put("name", s.trim());
                        st.put("required", true);
                        out.add(st);
                    }
                }
            }
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                collectStages(e.getValue(), out);
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                collectStages(arr.get(i), out);
            }
        }
    }

    private void collectVariables(Object node, List<Map<String, Object>> out) {
        if (node instanceof JSONObject obj) {
            for (String k : List.of("inputs", "params", "variables", "vars")) {
                Object maybe = obj.get(k);
                if (maybe instanceof JSONArray arr) {
                    for (int i = 0; i < arr.size(); i++) {
                        Object one = arr.get(i);
                        if (one instanceof JSONObject vo) {
                            String key = firstNonBlank(vo.getString("key"), vo.getString("name"), vo.getString("id"));
                            if (key == null || key.isBlank()) {
                                continue;
                            }
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("key", key);
                            row.put("type", firstNonBlank(vo.getString("type"), "string"));
                            out.add(row);
                        } else if (one instanceof String s && !s.isBlank()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("key", s.trim());
                            row.put("type", "string");
                            out.add(row);
                        }
                    }
                }
            }
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                collectVariables(e.getValue(), out);
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                collectVariables(arr.get(i), out);
            }
        }
    }

    private void collectStepsAndEdges(Object node, String path,
                                      List<Map<String, Object>> steps,
                                      List<Map<String, Object>> edges) {
        String lp = path == null ? "" : path.toLowerCase(Locale.ROOT);
        // 允许进入 enterprise.json 等文件中的 stages[]：此前跳过整个 stages 子树会导致 sceneFlow.steps 恒为空，
        // 只能依赖 stageFallback（线 A）。解析 stages[n] 元素为步骤即可启用 sceneFlow 驱动（线 B），仍跳过纯参数子树以免噪声。
        if (lp.contains(".variables[") || lp.contains(".columns[") || lp.contains(".inputs[")
                || lp.contains(".params[") || lp.contains(".outputs[")) {
            return;
        }
        if (node instanceof JSONObject obj) {
            Map<String, Object> step = maybeBuildSceneStep(obj, path);
            if (step != null) {
                steps.add(step);
                String fromId = String.valueOf(step.getOrDefault("id", ""));
                collectDirectEdges(obj, fromId, edges);
            }
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                collectStepsAndEdges(e.getValue(), path + "." + e.getKey(), steps, edges);
            }
        } else if (node instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                collectStepsAndEdges(arr.get(i), path + "[" + i + "]", steps, edges);
            }
        }
    }

    private Map<String, Object> maybeBuildSceneStep(JSONObject obj, String path) {
        String id = firstNonBlank(obj.getString("id"), obj.getString("stepId"), obj.getString("name"), obj.getString("title"));
        String label = firstNonBlank(obj.getString("title"), obj.getString("name"), obj.getString("label"), id);
        String type = firstNonBlank(obj.getString("type"), obj.getString("kind"), obj.getString("action"), "");
        String prompt = firstNonBlank(obj.getString("prompt"), obj.getString("instruction"), obj.getString("description"), "");
        if (type == null) {
            type = "";
        }
        if (prompt == null) {
            prompt = "";
        }
        String lowPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        boolean inStepLikePath = lowPath.contains(".steps[")
                || lowPath.contains(".nodes[")
                || lowPath.contains(".tasks[")
                || lowPath.contains(".actions[")
                || lowPath.contains(".pipeline[")
                || lowPath.contains(".flow[");
        boolean directStageElement = lowPath.matches("(?i).*\\bstages\\[\\d+]$");
        boolean hasNamedIdentity = (id != null && !id.isBlank()) || (label != null && !label.isBlank());
        boolean hasFlowHints = obj.containsKey("next")
                || obj.containsKey("to")
                || obj.containsKey("nextStep")
                || obj.containsKey("next_step")
                || obj.containsKey("transitions");
        boolean hasExecutionHints = obj.containsKey("tool") || obj.containsKey("model");
        boolean compactType = !type.isBlank() && (type.length() <= 24 || type.contains("_"));
        boolean promptLooksLikeTask = !prompt.isBlank() && prompt.length() > 8 && prompt.length() <= 256;
        boolean looksLikeStep = hasNamedIdentity && (
                hasFlowHints
                        || (inStepLikePath && (compactType || hasExecutionHints || promptLooksLikeTask))
                        || (directStageElement && (hasFlowHints || !type.isBlank() || hasExecutionHints
                        || promptLooksLikeTask || (label != null && label.length() >= 2)))
        );
        if (!looksLikeStep) {
            return null;
        }
        if (id == null || id.isBlank()) {
            id = "step_" + Integer.toHexString(path.hashCode());
        }
        if (type.isBlank()) {
            if (lowPath.contains("input") || lowPath.contains("param") || lowPath.contains("vars")) {
                type = "input";
            } else if (lowPath.contains("check") || lowPath.contains("gate") || lowPath.contains("verify")) {
                type = "check";
            } else if (lowPath.contains("output") || lowPath.contains("deliver")) {
                type = "output";
            } else {
                type = "process";
            }
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        String safeLabel = label != null && !label.isBlank() ? label : id;
        if (safeLabel.length() > 96) {
            safeLabel = safeLabel.substring(0, 96);
        }
        step.put("label", safeLabel);
        step.put("stepType", type.isBlank() ? "process" : type);
        step.put("detail", prompt);
        step.put("sourcePath", path);
        return step;
    }

    /**
     * Scene-pack JSON 顶层的 {@code edges: [{from,to}]}（与 steps 并列）此前未被遍历采集；补上以保证链路与编辑器录入顺序完整。
     */
    private void collectExplicitSceneEdges(JSONObject root, List<Map<String, Object>> edges) {
        if (root == null || !root.containsKey("edges")) {
            return;
        }
        Object raw = root.get("edges");
        if (!(raw instanceof JSONArray arr)) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            Object one = arr.get(i);
            if (!(one instanceof JSONObject e)) {
                continue;
            }
            String from = firstNonBlank(e.getString("from"), e.getString("source"));
            String to = firstNonBlank(e.getString("to"), e.getString("target"));
            if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
                edges.add(edge(canonicalNodeId(from), canonicalNodeId(to)));
            }
        }
    }

    private void collectDirectEdges(JSONObject obj, String fromId, List<Map<String, Object>> edges) {
        if (fromId == null || fromId.isBlank()) {
            return;
        }
        for (String key : List.of("next", "to", "nextStep", "next_step")) {
            String target = obj.getString(key);
            if (target != null && !target.isBlank()) {
                edges.add(edge(canonicalNodeId(fromId), canonicalNodeId(target)));
            }
        }
        Object transitions = obj.get("transitions");
        if (transitions instanceof JSONArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                Object one = arr.get(i);
                if (one instanceof JSONObject t) {
                    String to = firstNonBlank(t.getString("to"), t.getString("target"), t.getString("next"));
                    if (to != null && !to.isBlank()) {
                        edges.add(edge(canonicalNodeId(fromId), canonicalNodeId(to)));
                    }
                } else if (one instanceof String s && !s.isBlank()) {
                    edges.add(edge(canonicalNodeId(fromId), canonicalNodeId(s.trim())));
                }
            }
        }
    }

    private List<Map<String, Object>> normalizeSceneSteps(List<Map<String, Object>> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int idx = 0;
        for (Map<String, Object> s : safeList(steps)) {
            String id = String.valueOf(s.getOrDefault("id", "")).trim();
            String label = String.valueOf(s.getOrDefault("label", "")).trim();
            String st = String.valueOf(s.getOrDefault("stepType", "process")).trim();
            if (label.isBlank() && id.isBlank()) {
                continue;
            }
            if (id.isBlank()) {
                id = "step_" + idx;
            }
            String k = id + "|" + label + "|" + st;
            if (!seen.add(k)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("label", label.isBlank() ? id : label);
            row.put("stepType", st.isBlank() ? "process" : st);
            row.put("detail", String.valueOf(s.getOrDefault("detail", "")));
            row.put("sourcePath", String.valueOf(s.getOrDefault("sourcePath", "")));
            out.add(row);
            idx++;
        }
        return out;
    }

    private Map<String, Object> buildIr(JSONObject pluginMeta,
                                        JSONObject workbuddyManifest,
                                        JSONObject codebuddyManifest,
                                        JSONObject sceneRegistry,
                                        JSONObject officialSchema,
                                        Map<String, String> frontMatter,
                                        Map<String, Object> sceneFlow) {
        Map<String, Object> ir = new LinkedHashMap<>();
        ir.put("contractVersion", FbsIrContract.VERSION);
        ir.put("skillMeta", buildSkillMeta(pluginMeta, workbuddyManifest, codebuddyManifest, frontMatter, sceneRegistry));
        ir.put("intents", buildIntents(frontMatter, pluginMeta));
        ir.put("stages", buildStages(sceneFlow));
        ir.put("inputs", buildInputs(officialSchema, sceneFlow));
        ir.put("gates", buildGates(frontMatter));
        ir.put("outputs", buildOutputs(pluginMeta));
        ir.put("fallbacks", buildFallbacks(pluginMeta));
        ir.put("mappingHints", buildMappingHints());
        ir.put("sceneFlow", sceneFlow);
        return ir;
    }

    private Map<String, Object> buildSkillMeta(JSONObject pluginMeta,
                                               JSONObject workbuddyManifest,
                                               JSONObject codebuddyManifest,
                                               Map<String, String> frontMatter,
                                               JSONObject sceneRegistry) {
        Map<String, Object> meta = new LinkedHashMap<>();
        String id = firstNonBlank(
            pluginMeta.getString("id"),
            frontMatter.get("name"),
            "unknown-skill"
        );
        meta.put("id", id);
        meta.put("pluginId", firstNonBlank(frontMatter.get("plugin-id"), id));
        meta.put("name", firstNonBlank(frontMatter.get("name"), pluginMeta.getString("name"), id));
        meta.put("version", firstNonBlank(frontMatter.get("version"), pluginMeta.getString("version"), "unknown"));
        meta.put("description", firstNonBlank(frontMatter.get("description_en"), frontMatter.get("description"), pluginMeta.getString("description")));
        meta.put("channels", Arrays.asList(
            manifestChannel(workbuddyManifest, "workbuddy-marketplace"),
            manifestChannel(codebuddyManifest, "codebuddy-plugin")
        ));
        meta.put("scenePacks", extractScenePacks(pluginMeta, sceneRegistry));
        return meta;
    }

    private String manifestChannel(JSONObject manifest, String fallback) {
        String val = manifest.getString("channel");
        return val == null || val.trim().isEmpty() ? fallback : val;
    }

    private List<String> extractScenePacks(JSONObject pluginMeta, JSONObject sceneRegistry) {
        LinkedHashSet<String> packs = new LinkedHashSet<>();
        JSONObject sp = pluginMeta.getJSONObject("scene_packs");
        if (sp != null) {
            addAllStrings(packs, sp.getJSONArray("builtin"));
            addAllStrings(packs, sp.getJSONArray("available"));
        }
        JSONObject packsObj = sceneRegistry.getJSONObject("packs");
        if (packsObj != null) {
            packs.addAll(packsObj.keySet());
        }
        return new ArrayList<>(packs);
    }

    private List<Map<String, Object>> buildIntents(Map<String, String> frontMatter, JSONObject pluginMeta) {
        List<Map<String, Object>> intents = new ArrayList<>();
        intents.add(intent("compose", "生成长文主流程", "default"));
        intents.add(intent("rewrite", "改写/扩写", "optional"));
        intents.add(intent("quality_check", "质量检查与修订", "default"));
        intents.add(intent("delivery", "导出交付物", "optional"));

        JSONArray keywords = pluginMeta.getJSONArray("keywords");
        if (keywords != null) {
            List<String> sample = new ArrayList<>();
            for (int i = 0; i < keywords.size() && sample.size() < 8; i++) {
                String s = keywords.getString(i);
                if (s != null && !s.trim().isEmpty()) {
                    sample.add(s);
                }
            }
            if (!sample.isEmpty()) {
                Map<String, Object> inferred = new LinkedHashMap<>();
                inferred.put("id", "keyword_inference");
                inferred.put("label", "关键词推断意图");
                inferred.put("priority", "degraded");
                inferred.put("evidence", sample);
                intents.add(inferred);
            }
        }

        if (frontMatter.containsKey("description_en")) {
            Map<String, Object> inferred = new LinkedHashMap<>();
            inferred.put("id", "description_en_inference");
            inferred.put("label", "英文描述推断流程主线");
            inferred.put("priority", "mapped");
            inferred.put("evidence", frontMatter.get("description_en"));
            intents.add(inferred);
        }
        return intents;
    }

    private Map<String, Object> intent(String id, String label, String priority) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("priority", priority);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildStages(Map<String, Object> sceneFlow) {
        List<Map<String, Object>> inferred = new ArrayList<>();
        if (sceneFlow != null) {
            Object raw = sceneFlow.get("stages");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Map<String, Object> one = new LinkedHashMap<>();
                        Object idObj = m.get("id");
                        Object nameObj = m.get("name");
                        Object requiredObj = m.get("required");
                        one.put("id", String.valueOf(idObj != null ? idObj : ""));
                        one.put("name", String.valueOf(nameObj != null ? nameObj : ""));
                        one.put("required", Boolean.parseBoolean(String.valueOf(requiredObj != null ? requiredObj : "true")));
                        inferred.add(one);
                    }
                }
            }
        }
        if (!inferred.isEmpty()) {
            return inferred;
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(stage("S0", "素材准备", true));
        stages.add(stage("S1", "意图与大纲", true));
        stages.add(stage("S2", "章节计划", true));
        stages.add(stage("S3", "写作生成", true));
        stages.add(stage("S4", "整体验收", false));
        stages.add(stage("S5", "交付封装", false));
        return stages;
    }

    private Map<String, Object> stage(String id, String name, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("required", required);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildInputs(JSONObject officialSchema, Map<String, Object> sceneFlow) {
        List<Map<String, Object>> inputs = new ArrayList<>();
        inputs.add(input("genre", "string", true, "题材/场景包"));
        inputs.add(input("bookRoot", "string", false, "本地项目路径（运行时暂不接）"));
        inputs.add(input("outlineTarget", "string", false, "目标大纲或章节目标"));
        if (sceneFlow != null) {
            Object vars = sceneFlow.get("variables");
            if (vars instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object keyObj = m.get("key");
                        String key = String.valueOf(keyObj != null ? keyObj : "").trim();
                        if (key.isEmpty()) {
                            continue;
                        }
                        Object typeObj = m.get("type");
                        String type = String.valueOf(typeObj != null ? typeObj : "string");
                        String note = "来自 scene-packs 动态抽取";
                        inputs.add(input(key, type, false, note));
                    }
                }
            }
        }

        JSONObject sheets = officialSchema.getJSONObject("sheets");
        if (sheets != null) {
            JSONObject init = sheets.getJSONObject("init");
            if (init != null) {
                JSONArray cols = init.getJSONArray("columns");
                if (cols != null) {
                    for (int i = 0; i < cols.size(); i++) {
                        JSONObject col = cols.getJSONObject(i);
                        String name = col.getString("name");
                        if (name == null || name.trim().isEmpty()) {
                            continue;
                        }
                        inputs.add(input("init." + name, "string", false, "来自官方 scene-pack schema"));
                    }
                }
            }
        }
        return dedupeByKey(inputs, "key");
    }

    private Map<String, Object> input(String key, String type, boolean required, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("type", type);
        m.put("required", required);
        m.put("note", note);
        return m;
    }

    private List<Map<String, Object>> buildGates(Map<String, String> frontMatter) {
        List<Map<String, Object>> gates = new ArrayList<>();
        gates.add(gate("s0_exit_gate", "S0 完成后才能进入 S1", "mapped"));
        gates.add(gate("expansion_gate", "扩写前后需门禁校验", "degraded"));
        gates.add(gate("polish_gate", "润色前需质量校验", "degraded"));
        if (frontMatter.containsKey("version")) {
            gates.add(gate("version_guard", "按 skill 版本约束迁移策略", "mapped"));
        }
        return gates;
    }

    private Map<String, Object> gate(String id, String description, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("description", description);
        m.put("status", status);
        return m;
    }

    private List<Map<String, Object>> buildOutputs(JSONObject pluginMeta) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        outputs.add(output("draft", "章节草稿", "mapped"));
        outputs.add(output("quality_report", "质量报告", "degraded"));
        outputs.add(output("deliverables", "交付目录（MD/HTML/Docx）", "degraded"));
        JSONObject capabilities = pluginMeta.getJSONObject("capabilities");
        if (capabilities != null && Boolean.TRUE.equals(capabilities.getBoolean("local_disk_output"))) {
            outputs.add(output("local_disk", "本地磁盘输出", "unsupported"));
        }
        return outputs;
    }

    private Map<String, Object> output(String id, String description, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("description", description);
        m.put("status", status);
        return m;
    }

    private List<Map<String, Object>> buildFallbacks(JSONObject pluginMeta) {
        List<Map<String, Object>> fallbacks = new ArrayList<>();
        fallbacks.add(fallback("manual_adjust", "无法自动映射时人工补充节点"));
        fallbacks.add(fallback("skeleton_only", "仅生成骨架，不自动录入企微编辑器"));
        JSONObject capabilities = pluginMeta.getJSONObject("capabilities");
        if (capabilities != null && Boolean.TRUE.equals(capabilities.getBoolean("offline_mode"))) {
            fallbacks.add(fallback("offline_mode", "外部依赖缺失时离线策略"));
        }
        return fallbacks;
    }

    private Map<String, Object> fallback(String id, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("description", description);
        return m;
    }

    private Map<String, Object> buildMappingHints() {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("target", "QYWEIXIN_WORKFLOW");
        hints.put("strategy", "full_placeholder_structure");
        hints.put("legacyStrategy", "skeleton_only_insufficient_for_wecom");
        hints.put("importCapability", "QYWEIXIN_WORKFLOW_IMPORT_DRAFT");
        hints.put("note", "企微编辑器按节点类型强校验；仅「骨架」节点名无法通过检查。"
                + "迁移输出需带 editorNodeSpec（首选菜单）并由 Engine 做占位填写与 validationHintSweep；"
                + "完整行为需对照真实工作流或录制样本。");
        return hints;
    }

    private Map<String, Object> buildMigrationReport(JSONObject pluginMeta,
                                                     JSONObject workbuddyManifest,
                                                     JSONObject codebuddyManifest,
                                                     Map<String, Object> ir) {
        List<Map<String, String>> mapped = new ArrayList<>();
        List<Map<String, String>> degraded = new ArrayList<>();
        List<Map<String, String>> unsupported = new ArrayList<>();

        mapped.add(item("skill-meta", "Skill 基本信息", "可直接转换为迁移元数据"));
        mapped.add(item("scene-packs", "ScenePack 列表", "可映射为工作流参数与分支模板"));

        if (!workbuddyManifest.isEmpty() || !codebuddyManifest.isEmpty()) {
            mapped.add(item("channel-manifest", "渠道入口配置", "可映射为流程入口建议"));
        } else {
            degraded.add(item("channel-manifest", "渠道入口配置", "未发现完整 channel-manifest，将使用默认入口模板"));
        }

        JSONObject permissions = pluginMeta.getJSONObject("permissions");
        if (permissions != null && Boolean.TRUE.equals(permissions.getBoolean("bash"))) {
            unsupported.add(item("bash-runtime", "本地脚本执行", "本期不接入运行时，改为流程占位节点"));
        }
        JSONObject capabilities = pluginMeta.getJSONObject("capabilities");
        if (capabilities != null && Boolean.TRUE.equals(capabilities.getBoolean("local_disk_output"))) {
            unsupported.add(item("local-disk-output", "本地磁盘输出", "企微工作流中以输出说明节点替代"));
        }

        degraded.add(item("quality-gates", "质量门禁", "先做规则提示与检查节点，不做脚本级强校验"));
        degraded.add(item("host-runtime", "Host 运行时桥接", "先保留为能力颗粒候选，后续接运行时"));
        degraded.add(item("end-output-required", "结束节点输出参数", "若不配置结束输出，机器人可能无法获得工作流结果"));
        degraded.add(item("invoke-match", "工作流名称/描述触发匹配", "机器人是否调用工作流依赖名称描述与问题匹配度"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mapped", mapped.size());
        summary.put("degraded", degraded.size());
        summary.put("unsupported", unsupported.size());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("summary", summary);
        report.put("mapped", mapped);
        report.put("degraded", degraded);
        report.put("unsupported", unsupported);
        report.put("nextAction", "建议：预览 workflowDraft.nodes[].editorNodeSpec；一键录入后用 QYWEIXIN_WORKFLOW_VALIDATE / 编辑器诊断查看剩余占位。");
        report.put("irContractVersion", ir.get("contractVersion"));
        return report;
    }

    private Map<String, String> item(String code, String title, String note) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("title", title);
        m.put("note", note);
        return m;
    }

    private Map<String, Object> buildWorkflowDraft(Map<String, Object> ir, Map<String, Object> report) {
        Map<String, Object> draft = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) ir.get("skillMeta");
        String name = String.valueOf(meta.getOrDefault("name", "skill"));
        String version = String.valueOf(meta.getOrDefault("version", "v1"));
        draft.put("title", name + " -> 企微工作流(" + version + ")");
        draft.put("description", "由 FBS-IR 生成；nodes[].editorNodeSpec 描述首选菜单项，Engine 将做占位配置与按提示扫尾。");
        draft.put("editorImportModel", "full_placeholder_structure");
        @SuppressWarnings("unchecked")
        Map<String, Object> sceneFlow = (Map<String, Object>) ir.get("sceneFlow");
        List<Map<String, Object>> sceneNodes = buildWorkflowNodesFromScene(sceneFlow);
        int sceneBizCount = countBusinessNodes(sceneNodes);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) ir.get("stages");
        String nodeBuildSource = "sceneFlow";
        List<Map<String, Object>> finalNodes = sceneNodes;
        if (sceneBizCount < 3) {
            List<Map<String, Object>> stageNodes = buildWorkflowNodesFromStages(stages);
            int stageBizCount = countBusinessNodes(stageNodes);
            if (stageBizCount >= sceneBizCount) {
                finalNodes = stageNodes;
                nodeBuildSource = "stageFallback";
            }
        }
        if (finalNodes == null || finalNodes.isEmpty()) {
            finalNodes = defaultNodes();
            nodeBuildSource = "defaultFallback";
        }
        draft.put("nodes", finalNodes);
        draft.put("edges", buildWorkflowEdgesFromScene(sceneFlow, finalNodes));
        draft.put("endOutputs", defaultEndOutputs());
        draft.put("invokeHints", buildInvokeHints(meta, report));
        draft.put("inputBindings", ir.get("inputs"));
        draft.put("gateChecks", ir.get("gates"));
        draft.put("migrationSummary", report.get("summary"));
        draft.put("runtimeGuardNotes", List.of(
            "HTTP 请求仅支持公网域名/IP",
            "Python 节点当前不支持外网访问",
            "循环节点建议限制在 99 次以内",
            "结束节点需显式配置输出参数"
        ));
        Map<String, Object> buildDebug = new LinkedHashMap<>();
        buildDebug.put("nodeBuildSource", nodeBuildSource);
        buildDebug.put("sceneBusinessNodeCount", sceneBizCount);
        buildDebug.put("finalBusinessNodeCount", countBusinessNodes(finalNodes));
        buildDebug.put("sceneFlowStats", sceneFlow != null ? sceneFlow.get("stats") : null);
        draft.put("buildDebug", buildDebug);
        draft.put("recordingPlan", "QYWEIXIN_WORKFLOW_CREATE -> QYWEIXIN_WORKFLOW_OPEN_EDITOR -> QYWEIXIN_WORKFLOW_IMPORT_DRAFT");
        return draft;
    }

    private int countBusinessNodes(List<Map<String, Object>> nodes) {
        int c = 0;
        for (Map<String, Object> n : safeList(nodes)) {
            String t = String.valueOf(n.getOrDefault("type", ""));
            if (!"start".equals(t) && !"end".equals(t)) {
                c++;
            }
        }
        return c;
    }

    private List<Map<String, Object>> buildWorkflowNodesFromStages(List<Map<String, Object>> stages) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("n_start", "start", "开始"));
        int idx = 0;
        for (Map<String, Object> s : safeList(stages)) {
            String sid = String.valueOf(s.getOrDefault("id", "stage_" + idx));
            String name = String.valueOf(s.getOrDefault("name", sid));
            String type = inferStepTypeByLabel(name);
            String nodeType = "process";
            String preferred = "大模型问答";
            List<String> cands = new ArrayList<>(List.of("大模型问答"));
            if ("input".equals(type)) {
                nodeType = "input";
                preferred = "设置变量";
                cands = new ArrayList<>(List.of("设置变量", "变量"));
            } else if ("check".equals(type)) {
                nodeType = "check";
                preferred = "问题分类";
                cands = new ArrayList<>(List.of("问题分类", "条件判断"));
            } else if ("output".equals(type)) {
                nodeType = "output";
                preferred = "结束";
                cands = new ArrayList<>(List.of("结束", "知识库问答", "大模型问答"));
            }
            String cleanId = canonicalNodeId(sid);
            nodes.add(withEditorSpec(node(cleanId, nodeType, name), preferred, cands));
            idx++;
        }
        if (countBusinessNodes(nodes) < 1) {
            nodes.add(withEditorSpec(node("n_process_fallback", "process", "流程处理"), "大模型问答", List.of("大模型问答")));
        }
        nodes.add(node("n_end", "end", "结束"));
        return dedupeByKey(nodes, "id");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildWorkflowNodesFromScene(Map<String, Object> sceneFlow) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("n_start", "start", "开始"));
        if (sceneFlow == null) {
            return nodes;
        }
        Object rawSteps = sceneFlow.get("steps");
        if (!(rawSteps instanceof List<?> list) || list.isEmpty()) {
            return nodes;
        }
        int idx = 0;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> sm)) {
                continue;
            }
            Object sidObj = sm.get("id");
            String sid = String.valueOf(sidObj != null ? sidObj : ("step_" + idx)).trim();
            Object labelObj = sm.get("label");
            String label = String.valueOf(labelObj != null ? labelObj : sid).trim();
            Object typeObj = sm.get("stepType");
            String stepType = String.valueOf(typeObj != null ? typeObj : "process");
            Object detailObj = sm.get("detail");
            String detail = String.valueOf(detailObj != null ? detailObj : "");
            Map<String, Object> mapped = mapSceneStepToNode(sid, label, stepType, detail);
            nodes.add(mapped);
            idx++;
        }
        nodes.add(node("n_end", "end", "结束"));
        return dedupeByKey(nodes, "id");
    }

    private Map<String, Object> mapSceneStepToNode(String sid, String label, String stepType, String detail) {
        String st = stepType != null ? stepType.trim().toLowerCase(Locale.ROOT) : "";
        String nodeType = "process";
        String preferredMenu = "大模型问答";
        List<String> cands = new ArrayList<>(List.of("大模型问答"));
        switch (st) {
            case "input" -> {
                nodeType = "input";
                preferredMenu = "设置变量";
                cands = new ArrayList<>(List.of("设置变量", "变量"));
            }
            case "output" -> {
                nodeType = "output";
                preferredMenu = "结束";
                cands = new ArrayList<>(List.of("结束", "知识库问答", "大模型问答"));
            }
            case "llm" -> {
                nodeType = "process";
                preferredMenu = "大模型问答";
                cands = new ArrayList<>(List.of("大模型问答"));
            }
            case "check" -> {
                nodeType = "check";
                preferredMenu = "问题分类";
                cands = new ArrayList<>(List.of("问题分类", "条件判断"));
            }
            default -> {
                String text = (stepType + " " + label + " " + detail).toLowerCase(Locale.ROOT);
                if (text.contains("变量") || text.contains("input") || text.contains("param")) {
                    nodeType = "input";
                    preferredMenu = "设置变量";
                    cands = new ArrayList<>(List.of("设置变量", "变量"));
                } else if (text.contains("check") || text.contains("gate") || text.contains("审核")
                        || text.contains("判断") || text.contains("if")) {
                    nodeType = "check";
                    preferredMenu = "问题分类";
                    cands = new ArrayList<>(List.of("问题分类", "条件判断"));
                } else if (text.contains("http") || text.contains("api") || text.contains("request")) {
                    nodeType = "process";
                    preferredMenu = "HTTP请求";
                    cands = new ArrayList<>(List.of("HTTP请求", "大模型问答"));
                } else if (text.contains("python") || text.contains("script") || text.contains("bash") || text.contains("代码")) {
                    nodeType = "process";
                    preferredMenu = "代码执行";
                    cands = new ArrayList<>(List.of("代码执行", "大模型问答"));
                } else if (text.contains("输出") || text.contains("deliver") || text.contains("export")) {
                    nodeType = "output";
                    preferredMenu = "结束";
                    cands = new ArrayList<>(List.of("结束", "知识库问答", "大模型问答"));
                }
            }
        }
        String cleanId = sid == null || sid.isBlank() ? "step" : sid.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        if (!cleanId.startsWith("n_")) {
            cleanId = "n_" + cleanId;
        }
        String nodeLabel = label == null || label.isBlank() ? cleanId : label;
        return withEditorSpec(node(cleanId, nodeType, nodeLabel), preferredMenu, cands);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildWorkflowEdgesFromScene(Map<String, Object> sceneFlow, List<Map<String, Object>> nodes) {
        List<Map<String, Object>> edges = new ArrayList<>();
        if (nodes == null || nodes.size() < 2) {
            return defaultEdges();
        }
        if (sceneFlow != null) {
            Object raw = sceneFlow.get("edges");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        Object fromObj = m.get("from");
                        Object toObj = m.get("to");
                        String from = canonicalNodeId(String.valueOf(fromObj != null ? fromObj : ""));
                        String to = canonicalNodeId(String.valueOf(toObj != null ? toObj : ""));
                        if (!from.isBlank() && !to.isBlank()) {
                            edges.add(edge(from, to));
                        }
                    }
                }
            }
        }
        if (edges.isEmpty()) {
            String prev = "n_start";
            for (Map<String, Object> n : nodes) {
                String id = String.valueOf(n.getOrDefault("id", ""));
                if (id.isBlank() || "n_start".equals(id)) {
                    continue;
                }
                edges.add(edge(prev, id));
                prev = id;
            }
        } else {
            boolean linkedStart = edges.stream().anyMatch(e -> "n_start".equals(String.valueOf(e.get("from"))));
            String firstScene = nodes.stream()
                    .map(x -> String.valueOf(x.getOrDefault("id", "")))
                    .filter(x -> !x.isBlank() && !"n_start".equals(x) && !"n_end".equals(x))
                    .findFirst().orElse("");
            if (!linkedStart && !firstScene.isBlank()) {
                edges.add(0, edge("n_start", firstScene));
            }
            boolean linkedEnd = edges.stream().anyMatch(e -> "n_end".equals(String.valueOf(e.get("to"))));
            if (!linkedEnd) {
                String last = "";
                for (Map<String, Object> n : nodes) {
                    String id = String.valueOf(n.getOrDefault("id", ""));
                    if (!id.isBlank() && !"n_start".equals(id) && !"n_end".equals(id)) {
                        last = id;
                    }
                }
                if (!last.isBlank()) {
                    edges.add(edge(last, "n_end"));
                }
            }
        }
        return dedupeEdges(edges);
    }

    private String canonicalNodeId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return "";
        }
        String id = rawId.trim().replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return id.startsWith("n_") ? id : "n_" + id;
    }

    private List<Map<String, Object>> dedupeEdges(List<Map<String, Object>> edges) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> e : edges) {
            String from = String.valueOf(e.getOrDefault("from", ""));
            String to = String.valueOf(e.getOrDefault("to", ""));
            if (from.isBlank() || to.isBlank()) {
                continue;
            }
            String k = from + "->" + to;
            if (seen.add(k)) {
                out.add(edge(from, to));
            }
        }
        return out;
    }

    private List<Map<String, Object>> defaultEndOutputs() {
        List<Map<String, Object>> outputs = new ArrayList<>();
        outputs.add(endOutput("response", "String", "n_delivery.response", "返回机器人主回复文本"));
        outputs.add(endOutput("traceId", "String", "n_delivery.traceId", "用于问题排查与追踪"));
        outputs.add(endOutput("qualityScore", "Number", "n_quality.score", "质量门禁评分"));
        return outputs;
    }

    private Map<String, Object> endOutput(String name, String type, String source, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type);
        m.put("source", source);
        m.put("description", description);
        return m;
    }

    private List<Map<String, Object>> buildInvokeHints(Map<String, Object> skillMeta, Map<String, Object> report) {
        List<Map<String, Object>> hints = new ArrayList<>();
        String skillName = String.valueOf(skillMeta.getOrDefault("name", "工作流"));
        hints.add(invokeHint("name-desc-match", "工作流名称与描述需明确覆盖目标问题场景，避免机器人不触发。"));
        hints.add(invokeHint("query-samples", "建议调试问题包含关键词：" + skillName + "、写作、改写、质量检查。"));
        hints.add(invokeHint("knowledge-priority", "若机器人已配置知识集，可能优先命中知识集，请在机器人侧调整优先级。"));
        Object summary = report.get("summary");
        if (summary instanceof Map<?, ?> s) {
            Object unsupported = s.get("unsupported");
            if (unsupported != null) {
                try {
                    if (Integer.parseInt(String.valueOf(unsupported)) > 0) {
                        hints.add(invokeHint("unsupported-fallback", "存在暂不支持能力，请在工作流中保留人工兜底节点。"));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return hints;
    }

    private Map<String, Object> invokeHint(String id, String tip) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("tip", tip);
        return m;
    }

    private List<Map<String, Object>> defaultNodes() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("n_start", "start", "开始"));
        nodes.add(withEditorSpec(node("n_intake", "input", "收集输入参数"),
                "设置变量", List.of("设置变量", "变量")));
        nodes.add(withEditorSpec(node("n_outline", "process", "生成大纲骨架"),
                "大模型问答", List.of("大模型问答", "大模型回答")));
        nodes.add(withEditorSpec(node("n_write", "process", "章节写作"),
                "大模型问答", List.of("大模型问答", "大模型回答")));
        nodes.add(withEditorSpec(node("n_quality", "check", "质量门禁检查"),
                "问题分类", List.of("问题分类")));
        nodes.add(withEditorSpec(node("n_delivery", "output", "输出交付内容"),
                "大模型问答", List.of("大模型问答", "知识库问答")));
        nodes.add(node("n_end", "end", "结束"));
        return nodes;
    }

    private Map<String, Object> withEditorSpec(Map<String, Object> base, String preferredMenu,
                                               List<String> menuCandidates) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("preferredMenu", preferredMenu);
        spec.put("menuCandidates", menuCandidates);
        base.put("editorNodeSpec", spec);
        return base;
    }

    private List<Map<String, Object>> defaultEdges() {
        List<Map<String, Object>> edges = new ArrayList<>();
        edges.add(edge("n_start", "n_intake"));
        edges.add(edge("n_intake", "n_outline"));
        edges.add(edge("n_outline", "n_write"));
        edges.add(edge("n_write", "n_quality"));
        edges.add(edge("n_quality", "n_delivery"));
        edges.add(edge("n_delivery", "n_end"));
        return edges;
    }

    private Map<String, Object> node(String id, String type, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("label", label);
        return m;
    }

    private Map<String, Object> edge(String from, String to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        return m;
    }

    private List<Map<String, Object>> buildRuntimeValidation(JSONObject pluginMeta,
                                                             Map<String, Object> ir,
                                                             Map<String, Object> workflowDraft) {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(runtimeCheck("http-public-only", "check_required", "HTTP 节点仅支持公网域名/IP，需人工检查 URL"));
        checks.add(runtimeCheck("python-no-external-network", "check_required", "Python 节点不支持外网访问，需避免依赖外部网络"));
        checks.add(runtimeCheck("loop-max-99", "check_required", "循环节点建议上限 99 次，避免超限或死循环"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endOutputs = (List<Map<String, Object>>) workflowDraft.get("endOutputs");
        if (endOutputs == null || endOutputs.isEmpty()) {
            checks.add(runtimeCheck("end-output-required", "degraded", "结束节点缺少输出参数，机器人可能无法获得工作流结果"));
        } else {
            checks.add(runtimeCheck("end-output-required", "mapped", "结束节点已预置输出参数"));
        }

        JSONObject permissions = pluginMeta.getJSONObject("permissions");
        if (permissions != null && Boolean.TRUE.equals(permissions.getBoolean("bash"))) {
            checks.add(runtimeCheck("bash-runtime-unsupported", "unsupported", "Skill 依赖 bash 运行时，企微工作流仅能以占位节点降级"));
        }
        return checks;
    }

    private Map<String, Object> summarizeRuntimeValidation(List<Map<String, Object>> checks) {
        int mapped = 0;
        int degraded = 0;
        int unsupported = 0;
        int checkRequired = 0;
        for (Map<String, Object> row : safeList(checks)) {
            String status = String.valueOf(row.getOrDefault("status", "check_required"));
            switch (status) {
                case "mapped" -> mapped++;
                case "degraded" -> degraded++;
                case "unsupported" -> unsupported++;
                default -> checkRequired++;
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mapped", mapped);
        m.put("degraded", degraded);
        m.put("unsupported", unsupported);
        m.put("checkRequired", checkRequired);
        return m;
    }

    private Map<String, Object> runtimeCheck(String id, String status, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("status", status);
        m.put("description", description);
        return m;
    }

    private List<Map<String, Object>> buildValidationGranules(Map<String, Object> validationResult) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (validationResult == null || validationResult.isEmpty()) {
            return rows;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) validationResult.get("issues");
        for (Map<String, Object> issue : safeList(issues)) {
            addGranule(rows, "WORKFLOW_VALIDATE_ISSUE",
                String.valueOf(issue.getOrDefault("severity", "warn")),
                issue.get("code"), issue.get("message"), "validate.issues");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = (List<Map<String, Object>>) validationResult.get("checks");
        for (Map<String, Object> c : safeList(checks)) {
            addGranule(rows, "WORKFLOW_VALIDATE_CHECK",
                String.valueOf(c.getOrDefault("status", "unknown")),
                c.get("name"), c.get("note"), "validate.checks");
        }
        return rows;
    }

    private void addAllStrings(Set<String> dest, JSONArray arr) {
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            String val = arr.getString(i);
            if (val != null && !val.trim().isEmpty()) {
                dest.add(val.trim());
            }
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

    private List<Map<String, Object>> dedupeByKey(List<Map<String, Object>> rows, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> exists = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            Object val = row.get(key);
            String k = val == null ? null : String.valueOf(val).toLowerCase(Locale.ROOT);
            if (k == null || exists.contains(k)) {
                continue;
            }
            exists.add(k);
            out.add(row);
        }
        return out;
    }
}

