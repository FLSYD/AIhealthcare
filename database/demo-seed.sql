-- 全部为新建的合成演示数据。没有患者、预约、交易、微信身份或头像记录。
-- 管理员账号与密码由 init-database.py 交互创建，不在此保存固定口令或散列。
INSERT INTO sys_menu (parent_id, name, path, icon, sort_order, roles) VALUES
(0, '数据概览', '/dashboard', 'DataAnalysis', 10, 'ADMIN'),
(0, '科室管理', '/department', 'OfficeBuilding', 20, 'ADMIN'),
(0, '医生管理', '/doctor', 'Avatar', 30, 'ADMIN'),
(0, '排班管理', '/schedule', 'Calendar', 40, 'ADMIN'),
(0, '预约管理', '/appointment', 'Tickets', 50, 'ADMIN'),
(0, '药品管理', '/medicine', 'FirstAidKit', 60, 'ADMIN,PHARMACIST'),
(0, '用户管理', '/sysUser', 'User', 70, 'ADMIN'),
(0, '系统日志', '/sys-login-log', 'Document', 80, 'ADMIN'),
(0, '菜单管理', '/menu', 'Grid', 90, 'ADMIN'),
(0, '工作概览', '/doctor/overview', 'House', 10, 'DOCTOR'),
(0, '我的排班', '/doctor/schedules', 'Calendar', 20, 'DOCTOR'),
(0, '我的预约', '/doctor/appointments', 'Tickets', 30, 'DOCTOR'),
(0, '配药工作台', '/pharmacy', 'FirstAidKit', 10, 'PHARMACIST');

INSERT INTO department (name, type, description, location, sort_order, status) VALUES
('演示内科', 'CLINICAL', '合成学习数据，可在管理端修改', '演示门诊一层', 10, 1),
('演示配药科', 'PHARMACY', '合成配药科，可创建配药师档案', '演示药房', 20, 1);

INSERT INTO medicine (name, spec, unit, price, stock, usage_desc, status) VALUES
('演示药品A', '演示规格', '盒', 10.00, 100, '仅供软件流程演示，不代表真实用药建议', 1),
('演示药品B', '演示规格', '盒', 20.00, 50, '仅供软件流程演示，不代表真实用药建议', 1);
