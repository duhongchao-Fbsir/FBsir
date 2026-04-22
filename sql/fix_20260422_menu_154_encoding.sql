-- 修复菜单 154 中文乱码（须以 utf8mb4 客户端执行）
SET NAMES utf8mb4;
UPDATE sys_menu
SET menu_name = '企微工作流',
    remark = '企业微信智能机器人工作流研究与测试（Engine QYWEIXIN_WORKFLOW_*）'
WHERE menu_id = '154';
