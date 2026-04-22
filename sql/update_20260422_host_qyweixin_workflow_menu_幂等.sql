-- 主机管理下增加：企微智能体工作流（研究与测试页）
-- 执行：mysql -h127.0.0.1 -P3306 -uroot -p wxfbsir < sql/update_20260422_host_qyweixin_workflow_menu_幂等.sql

INSERT INTO `sys_menu` (
  `menu_id`, `menu_name`, `parent_id`, `order_num`, `path`, `component`, `query`, `route_name`,
  `is_frame`, `is_cache`, `menu_type`, `visible`, `status`, `perms`, `icon`, `create_by`, `create_time`,
  `update_by`, `update_time`, `remark`
) VALUES (
  '154', '企微工作流', '7', '7', 'qyweixin-workflow', 'business/host/qyweixinWorkflow/index', '', '',
  1, 0, 'C', '0', '0', 'business:host:qyweixinWorkflow:view', 'guide', 'admin', sysdate(), '', NULL,
  '企业微信智能机器人/工作流实验页（Engine QYWEIXIN_*）；侧栏简称「企微工作流」'
) ON DUPLICATE KEY UPDATE
  `menu_name` = VALUES(`menu_name`),
  `parent_id` = VALUES(`parent_id`),
  `order_num` = VALUES(`order_num`),
  `path` = VALUES(`path`),
  `component` = VALUES(`component`),
  `perms` = VALUES(`perms`),
  `icon` = VALUES(`icon`),
  `remark` = VALUES(`remark`);

-- 普通角色(role_id=2)可见；已存在则忽略
INSERT IGNORE INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES ('2', '154');
