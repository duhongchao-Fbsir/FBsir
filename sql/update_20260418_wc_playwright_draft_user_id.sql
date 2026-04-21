-- wc_playwright_draft：显式 user_id 列（与 user_name 双写；历史 bigint 数据回填）
-- 依赖：MySQL 8.0+（与 AIGC 草稿窗口函数要求一致）
-- 若列已存在，跳过本脚本对应语句或整文件执行前手工检查 INFORMATION_SCHEMA

ALTER TABLE `wc_playwright_draft`
  ADD COLUMN `user_id` bigint(20) NULL DEFAULT NULL COMMENT '创建人用户ID（与 user_name 冗余对齐）' AFTER `user_name`;

UPDATE `wc_playwright_draft`
SET `user_id` = `user_name`
WHERE `user_id` IS NULL AND `user_name` IS NOT NULL;

CREATE INDEX `idx_user_id` ON `wc_playwright_draft` (`user_id`);
