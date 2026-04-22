package com.wx.fbsir.business.websocket.service.skill;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Skill 迁移能力颗粒目录（内存版）。
 * <p>
 * 目标：先打通结构化沉淀与自动回写，后续可平滑替换为 DB 持久化实现。
 */
@Service
public class SkillGranuleCatalogService {

    private static final int MAX_RECENT = 60;

    private final AtomicLong seq = new AtomicLong(0L);
    private final Map<String, Map<String, Object>> skillSnapshots = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> recentSnapshots = Collections.synchronizedList(new ArrayList<>());

    public Map<String, Object> upsert(String skillId,
                                      Map<String, Object> skillMeta,
                                      List<Map<String, Object>> granules,
                                      String sourceFileName) {
        String normalizedSkillId = (skillId == null || skillId.isBlank()) ? "unknown-skill" : skillId.trim();
        long now = System.currentTimeMillis();
        long version = seq.incrementAndGet();

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("skillId", normalizedSkillId);
        snapshot.put("version", version);
        snapshot.put("updatedAt", now);
        snapshot.put("source", "skill-migration-preview");
        snapshot.put("sourceFile", sourceFileName == null ? "" : sourceFileName);
        snapshot.put("skillMeta", skillMeta == null ? Map.of() : skillMeta);
        List<Map<String, Object>> normalized = normalizeGranules(granules);
        snapshot.put("granules", normalized);
        snapshot.put("granuleCount", normalized.size());
        snapshot.put("stats", buildStats(normalized));

        skillSnapshots.put(normalizedSkillId, snapshot);
        pushRecent(snapshot);

        Map<String, Object> writeback = new LinkedHashMap<>();
        writeback.put("enabled", true);
        writeback.put("catalog", "skill-granule-memory-v1");
        writeback.put("skillId", normalizedSkillId);
        writeback.put("version", version);
        writeback.put("granuleCount", normalized.size());
        writeback.put("stats", buildStats(normalized));
        writeback.put("catalogSize", skillSnapshots.size());
        writeback.put("updatedAt", now);
        return writeback;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> appendGranules(String skillId,
                                              List<Map<String, Object>> granules,
                                              String source) {
        String normalizedSkillId = (skillId == null || skillId.isBlank()) ? "unknown-skill" : skillId.trim();
        Map<String, Object> current = skillSnapshots.get(normalizedSkillId);
        if (current == null) {
            return null;
        }
        List<Map<String, Object>> merged = new ArrayList<>();
        Object old = current.get("granules");
        if (old instanceof List<?>) {
            for (Object item : (List<?>) old) {
                if (item instanceof Map<?, ?> row) {
                    merged.add((Map<String, Object>) row);
                }
            }
        }
        if (granules != null && !granules.isEmpty()) {
            merged.addAll(granules);
        }
        merged = normalizeGranules(merged);

        long now = System.currentTimeMillis();
        long version = seq.incrementAndGet();
        current.put("granules", merged);
        current.put("granuleCount", merged.size());
        current.put("stats", buildStats(merged));
        current.put("version", version);
        current.put("updatedAt", now);
        current.put("lastWritebackSource", source == null ? "append" : source);
        pushRecent(current);

        Map<String, Object> writeback = new LinkedHashMap<>();
        writeback.put("enabled", true);
        writeback.put("catalog", "skill-granule-memory-v1");
        writeback.put("skillId", normalizedSkillId);
        writeback.put("version", version);
        writeback.put("granuleCount", merged.size());
        writeback.put("stats", buildStats(merged));
        writeback.put("catalogSize", skillSnapshots.size());
        writeback.put("updatedAt", now);
        writeback.put("source", source == null ? "append" : source);
        return writeback;
    }

    public List<Map<String, Object>> listRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(100, limit));
        List<Map<String, Object>> copy;
        synchronized (recentSnapshots) {
            int size = recentSnapshots.size();
            int from = Math.max(0, size - safeLimit);
            copy = new ArrayList<>(recentSnapshots.subList(from, size));
        }
        Collections.reverse(copy);
        return copy;
    }

    public Map<String, Object> getBySkillId(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return null;
        }
        return skillSnapshots.get(skillId.trim());
    }

    private void pushRecent(Map<String, Object> snapshot) {
        synchronized (recentSnapshots) {
            recentSnapshots.add(snapshot);
            if (recentSnapshots.size() > MAX_RECENT) {
                recentSnapshots.remove(0);
            }
        }
    }

    private List<Map<String, Object>> normalizeGranules(List<Map<String, Object>> granules) {
        if (granules == null || granules.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        LinkedHashSet<String> dedupe = new LinkedHashSet<>();
        for (Map<String, Object> row : granules) {
            if (row == null || row.isEmpty()) {
                continue;
            }
            String type = String.valueOf(row.getOrDefault("granuleType", ""));
            String key = String.valueOf(row.getOrDefault("key", ""));
            String source = String.valueOf(row.getOrDefault("sourcePath", ""));
            String dedupeKey = type + "||" + key + "||" + source;
            if (!dedupe.add(dedupeKey)) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> buildStats(List<Map<String, Object>> granules) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (Map<String, Object> row : granules) {
            String status = String.valueOf(row.getOrDefault("status", "unknown"));
            String type = String.valueOf(row.getOrDefault("granuleType", "unknown"));
            byStatus.put(status, byStatus.getOrDefault(status, 0) + 1);
            byType.put(type, byType.getOrDefault(type, 0) + 1);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("byStatus", byStatus);
        stats.put("byType", byType);
        return stats;
    }
}
