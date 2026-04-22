package com.wx.fbsir.business.websocket.controller;

import com.wx.fbsir.business.websocket.service.skill.SkillMigrationPreviewService;
import com.wx.fbsir.common.core.controller.BaseController;
import com.wx.fbsir.common.core.domain.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Map;

/**
 * Skill -> 企微工作流迁移接口（L1预览）。
 */
@RestController
@RequestMapping({"/business/host/qyweixin-workflow/skill", "/ws/admin/skill"})
public class QyWeixinWorkflowSkillController extends BaseController {

    private final SkillMigrationPreviewService previewService;

    public QyWeixinWorkflowSkillController(SkillMigrationPreviewService previewService) {
        this.previewService = previewService;
    }

    @PreAuthorize("@ss.hasPermi('business:host:qyweixinWorkflow:view')")
    @PostMapping("/preview")
    public AjaxResult preview(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return AjaxResult.error("请上传 skill ZIP 文件");
        }
        String name = file.getOriginalFilename();
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".zip")) {
            return AjaxResult.error("仅支持 .zip 文件");
        }
        try {
            Map<String, Object> result = previewService.preview(file);
            return AjaxResult.success("迁移预览生成成功", result);
        } catch (Exception ex) {
            return AjaxResult.error("迁移预览失败: " + ex.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:host:qyweixinWorkflow:view')")
    @GetMapping("/granules/recent")
    public AjaxResult recentGranules(@RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        int safeLimit = limit == null ? 20 : limit;
        return AjaxResult.success("查询成功", previewService.listRecentGranules(safeLimit));
    }

    @PreAuthorize("@ss.hasPermi('business:host:qyweixinWorkflow:view')")
    @GetMapping("/granules/skill/{skillId}")
    public AjaxResult granulesBySkill(@PathVariable("skillId") String skillId) {
        Map<String, Object> snapshot = previewService.getSkillGranules(skillId);
        if (snapshot == null) {
            return AjaxResult.error("未找到该 skill 的沉淀记录: " + skillId);
        }
        return AjaxResult.success("查询成功", snapshot);
    }

    @PreAuthorize("@ss.hasPermi('business:host:qyweixinWorkflow:view')")
    @PostMapping("/granules/skill/{skillId}/append")
    public AjaxResult appendGranulesBySkill(@PathVariable("skillId") String skillId,
                                            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> validationResult = body == null ? null : castObjectMap(body.get("validationResult"));
        if (validationResult == null || validationResult.isEmpty()) {
            return AjaxResult.error("缺少 validationResult，无法回写");
        }
        Map<String, Object> writeback = previewService.appendValidationWriteback(skillId, validationResult);
        if (writeback == null) {
            return AjaxResult.error("回写失败：未找到 skillId=" + skillId + " 的沉淀快照");
        }
        return AjaxResult.success("回写成功", writeback);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castObjectMap(Object val) {
        if (val instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }
}

