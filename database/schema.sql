-- MySQL 8 表结构：只包含 DDL，不包含原库记录、口令、交易或头像数据。
-- 由 scripts/init-database.py 在全新目标库中导入；不要对已有业务库手工重复执行。

CREATE TABLE `appointment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '预约用户',
  `schedule_id` bigint NOT NULL COMMENT '关联排班',
  `patient_name` varchar(50) NOT NULL COMMENT '就诊人姓名',
  `patient_phone` varchar(20) NOT NULL COMMENT '就诊人手机',
  `id_card` varchar(30) DEFAULT NULL COMMENT '身份证号',
  `amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '挂号费用',
  `pay_method` varchar(20) DEFAULT NULL COMMENT 'balance/wechat/alipay',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `status` varchar(20) NOT NULL DEFAULT 'unpaid' COMMENT 'unpaid/pending/checked_in/in_consultation/pending_rx_pay/completed/cancelled',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_appt_user` (`user_id`),
  KEY `idx_appt_schedule` (`schedule_id`),
  KEY `idx_appt_status_time` (`status`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约记录';

CREATE TABLE `appointment_review` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `appointment_id` bigint NOT NULL COMMENT '预约',
  `user_id` bigint NOT NULL COMMENT '评价用户',
  `doctor_id` bigint NOT NULL COMMENT '医生',
  `rating` tinyint NOT NULL COMMENT '评分1-5',
  `tags` varchar(200) DEFAULT NULL COMMENT '快捷标签，逗号分隔',
  `comment` varchar(500) DEFAULT NULL COMMENT '文字评价',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_appointment` (`appointment_id`),
  KEY `idx_doctor` (`doctor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='就诊评价';

CREATE TABLE `balance_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户',
  `type` varchar(20) NOT NULL COMMENT 'recharge/order_pay/order_refund',
  `amount` decimal(10,2) NOT NULL COMMENT '变动金额，充值为正，支付为负',
  `balance_after` decimal(10,2) NOT NULL COMMENT '变动后余额',
  `order_id` bigint DEFAULT NULL COMMENT '关联预约 ID',
  `pay_method` varchar(20) DEFAULT NULL COMMENT 'wechat/alipay/balance',
  `remark` varchar(100) DEFAULT NULL COMMENT '备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_balance_user_time` (`user_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='余额流水';

CREATE TABLE `consultation_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `appointment_id` bigint NOT NULL COMMENT '预约',
  `doctor_id` bigint NOT NULL COMMENT '接诊医生',
  `chief_complaint` varchar(500) DEFAULT NULL COMMENT '主诉',
  `present_illness` varchar(1000) DEFAULT NULL COMMENT '现病史',
  `diagnosis` varchar(500) DEFAULT NULL COMMENT '诊断',
  `treatment_plan` varchar(1000) DEFAULT NULL COMMENT '治疗方案',
  `doctor_advice` varchar(500) DEFAULT NULL COMMENT '医嘱',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_consultation_appt` (`appointment_id`),
  KEY `idx_consultation_doctor` (`doctor_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='问诊病历';

CREATE TABLE `department` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '科室名称',
  `type` varchar(20) NOT NULL DEFAULT 'CLINICAL' COMMENT '科室类型：CLINICAL=临床科室，PHARMACY=配药科',
  `description` varchar(500) DEFAULT NULL COMMENT '科室简介',
  `location` varchar(200) DEFAULT NULL COMMENT '所在楼层/位置',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0停用 1启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_dept_status_sort` (`status`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='科室';

CREATE TABLE `doctor` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `department_id` bigint NOT NULL COMMENT '所属科室',
  `name` varchar(50) NOT NULL COMMENT '姓名',
  `role_type` varchar(20) NOT NULL DEFAULT 'DOCTOR' COMMENT '人员类型：DOCTOR=医生，PHARMACIST=配药师',
  `title` varchar(50) DEFAULT NULL COMMENT '职称',
  `gender` tinyint NOT NULL DEFAULT '1' COMMENT '1男 2女',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像路径',
  `intro` varchar(1000) DEFAULT NULL COMMENT '简介',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0停用 1启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_doctor_dept` (`department_id`),
  KEY `idx_doctor_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医生';

CREATE TABLE `medicine` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '药品名称',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格',
  `unit` varchar(20) NOT NULL DEFAULT '盒' COMMENT '单位',
  `price` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '单价',
  `stock` int NOT NULL DEFAULT '0' COMMENT '库存',
  `usage_desc` varchar(200) DEFAULT NULL COMMENT '用法说明',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1启用 0停用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_medicine_name` (`name`),
  KEY `idx_medicine_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品库';

CREATE TABLE `prescription` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `appointment_id` bigint NOT NULL COMMENT '预约',
  `user_id` bigint NOT NULL COMMENT '患者用户',
  `doctor_id` bigint NOT NULL COMMENT '开方医生',
  `total_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '处方总金额',
  `status` varchar(20) NOT NULL DEFAULT 'unpaid' COMMENT 'unpaid/paid/dispensed/cancelled',
  `pay_method` varchar(20) DEFAULT NULL COMMENT 'balance/wechat/alipay',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prescription_appt` (`appointment_id`),
  KEY `idx_prescription_user` (`user_id`),
  KEY `idx_prescription_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='处方单';

CREATE TABLE `prescription_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `prescription_id` bigint NOT NULL COMMENT '处方',
  `medicine_id` bigint NOT NULL COMMENT '药品',
  `medicine_name` varchar(100) NOT NULL COMMENT '药品名称快照',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格快照',
  `dosage` varchar(50) DEFAULT NULL COMMENT '单次剂量',
  `frequency` varchar(50) DEFAULT NULL COMMENT '频次',
  `days` int DEFAULT NULL COMMENT '天数',
  `quantity` int NOT NULL DEFAULT '1' COMMENT '数量',
  `unit_price` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '单价快照',
  `amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '小计',
  PRIMARY KEY (`id`),
  KEY `idx_rx_item_prescription` (`prescription_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='处方明细';

CREATE TABLE `schedule` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `doctor_id` bigint NOT NULL COMMENT '医生',
  `schedule_date` date NOT NULL COMMENT '出诊日期',
  `time_slot` varchar(20) NOT NULL COMMENT '时段',
  `total_quota` int NOT NULL COMMENT '总号源',
  `booked_count` int NOT NULL DEFAULT '0' COMMENT '已预约数',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0停诊 1正常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_schedule_doctor_date_slot` (`doctor_id`,`schedule_date`,`time_slot`),
  KEY `idx_schedule_date` (`schedule_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='排班号源';

CREATE TABLE `sys_login_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) NOT NULL COMMENT '登录账号',
  `ip` varchar(50) DEFAULT NULL COMMENT 'IP',
  `status` tinyint NOT NULL COMMENT '0失败 1成功',
  `message` varchar(200) DEFAULT NULL COMMENT '说明',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录日志';

CREATE TABLE `sys_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `parent_id` bigint NOT NULL DEFAULT '0' COMMENT '父菜单，0为顶级',
  `name` varchar(50) NOT NULL COMMENT '菜单名称',
  `path` varchar(100) NOT NULL COMMENT '前端路由',
  `icon` varchar(50) DEFAULT NULL COMMENT '图标名（Element Plus Icons）',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `roles` varchar(100) NOT NULL DEFAULT 'ADMIN' COMMENT '固定 ADMIN（仅后台菜单）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单表';

CREATE TABLE `sys_operation_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `username` varchar(50) DEFAULT NULL COMMENT '操作人',
  `module` varchar(50) DEFAULT NULL COMMENT '模块',
  `action` varchar(50) DEFAULT NULL COMMENT '操作',
  `method` varchar(10) DEFAULT NULL COMMENT '请求方式',
  `url` varchar(200) DEFAULT NULL COMMENT '请求地址',
  `ip` varchar(50) DEFAULT NULL COMMENT 'IP',
  `cost_ms` int DEFAULT NULL COMMENT '耗时毫秒',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0失败 1成功',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作日志';

CREATE TABLE `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `code` varchar(30) NOT NULL COMMENT '角色编码',
  `name` varchar(50) NOT NULL COMMENT '角色名称',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '0禁用 1启用',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';

CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(50) NOT NULL COMMENT '用户名',
  `password` varchar(100) NOT NULL COMMENT '密码（BCrypt）',
  `nickname` varchar(50) DEFAULT NULL COMMENT '昵称',
  `email` varchar(100) DEFAULT NULL COMMENT '邮箱',
  `phone` varchar(20) DEFAULT NULL COMMENT '手机号',
  `avatar` varchar(255) DEFAULT NULL COMMENT '头像访问路径，如 /uploads/avatar/xxx.jpg',
  `openid` varchar(64) DEFAULT NULL COMMENT '微信小程序 openid',
  `balance` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '账户余额',
  `doctor_id` bigint DEFAULT NULL COMMENT '医生角色绑定的 doctor.id',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态：0禁用 1启用',
  `role` varchar(30) NOT NULL DEFAULT 'USER' COMMENT '角色编码，对应 sys_role.code',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_openid` (`openid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';

CREATE TABLE `user_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '接收用户',
  `type` varchar(50) NOT NULL COMMENT '通知类型',
  `title` varchar(100) NOT NULL COMMENT '标题',
  `content` varchar(500) NOT NULL COMMENT '内容',
  `biz_type` varchar(30) DEFAULT NULL COMMENT 'appointment/prescription',
  `biz_id` bigint DEFAULT NULL COMMENT '业务主键',
  `read_flag` tinyint NOT NULL DEFAULT '0' COMMENT '0未读 1已读',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_notify_user` (`user_id`,`read_flag`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内消息通知';

CREATE TABLE `wechat_native_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `business_type` varchar(24) NOT NULL,
  `business_id` bigint DEFAULT NULL,
  `user_id` bigint NOT NULL,
  `amount_fen` bigint NOT NULL,
  `merchant_order_no` varchar(32) NOT NULL,
  `transaction_id` varchar(64) DEFAULT NULL,
  `request_hash` char(64) DEFAULT NULL,
  `active_key` varchar(100) DEFAULT NULL,
  `state` varchar(16) NOT NULL,
  `code_url` varchar(1024) DEFAULT NULL,
  `prepay_id` varchar(128) DEFAULT NULL,
  `error_code` varchar(64) DEFAULT NULL,
  `error_message` varchar(256) DEFAULT NULL,
  `expires_at` datetime NOT NULL,
  `last_query_at` datetime DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_native_merchant_order` (`merchant_order_no`),
  UNIQUE KEY `uk_native_transaction` (`transaction_id`),
  UNIQUE KEY `uk_native_active` (`active_key`),
  UNIQUE KEY `uk_native_recharge_request` (`user_id`,`request_hash`),
  KEY `idx_native_state_query` (`state`,`last_query_at`),
  KEY `idx_native_business` (`business_type`,`business_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
