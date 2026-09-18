# 工业设备巡检与维保工单管理平台（纯后端）

工业设备台账、巡检与维保工单管理的纯后端 API 服务。

## 技术栈

- Java 17 + Spring Boot 3 + Spring Web
- Spring Data JPA + MySQL 8（字符集 utf8mb4）
- JWT 鉴权（jjwt，自定义过滤器）、PBKDF2 密码哈希（JDK 自带）

## 启动（Docker）

```bash
docker compose up --build
```

MySQL 就绪后，应用通过 JPA 自动建表（ddl-auto=update）并在启动时灌入种子数据，服务监听 `http://127.0.0.1:7654`。

## 内置账号

唯一管理员（本平台只有 admin 一个角色）：

- 用户名：`admin`
- 密码：`admin123`

## 已实现的基础功能

- 登录签发 JWT、获取当前用户（`/api/auth/login`、`/api/auth/me`）
- 设备台账增删改查（`/api/equipments`，编号唯一校验）
- 维保工单查询、创建、状态流转（`/api/work-orders`，完成时记录关闭时间）
- 巡检点、巡检模板与周期计划维护（`/api/inspection/points`、`/api/inspection/templates`、`/api/inspection/plans`）
- 巡检任务生成与执行、异常转工单、复检闭环和路线比较（`/api/inspection/tasks`）
- 巡检完成率、设备历史与执行轨迹查询（`/api/inspection/stats`）
- 数值型巡检项趋势预警：规则发布与版本管理、异步评估、迟到重算、预警处置（`/api/inspection/trend`）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 趋势预警（/api/inspection/trend）

针对"单次读数合格但连续缓慢劣化"的场景，在数值型 `InspectionRecord` 之上提供趋势预警。

**规则（/rules）**：按设备类型 + 数值型模板项目发布，可配置滚动窗口（小时）、最少样本、
变化斜率（单位/天）、波动幅度（窗口内 max-min）、连续接近边界次数（边界余量 + 次数）、
预警等级（low/medium/high/urgent）与冷却期（小时）。`PUT /rules/{id}` 修改参数会使
`version` 自增，新版本只作用于之后的评估；`POST /rules/{id}/status` 可停用/启用。

**评估**：巡检记录写入并提交后异步评估匹配规则（`POST /api/inspection/tasks/execute`
的 `items[].recordedAt` 可传采样时间，用于补录迟到数据）。迟到数据会按采样时间扇出
重算所有受影响窗口；同一设备、项目、规则版本与窗口只产生一个预警（数据库唯一约束），
冷却期内的新触发以证据形式追加到既有事件。同刻重复读数按均值合并、缺失值剔除并计数，
评估结论只取决于已提交数据，与评估顺序、并发无关。

**预警（/alerts）**：分页查询与详情（含触发证据、处置留痕与解释）。处置接口：
`POST /alerts/{id}/acknowledge`（确认）、`POST /alerts/{id}/ignore`（忽略，带到期
小时数，到期后新证据自动重开）、`POST /alerts/{id}/work-order`（转维修工单）。
所有处置强制记录操作者与理由。

**查询**：`GET /series?equipmentId=&templateItemId=` 返回分页时间序列、汇总与各规则
最近评估结论；`GET /evaluations` 返回评估日志，解释样本不足、迟到重算与规则版本失效。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
