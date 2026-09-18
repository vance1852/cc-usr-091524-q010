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
- 数值型巡检记录的趋势预警：规则发布、异步评估、迟到重算、预警处置（`/api/inspection/trend`）
- 仪表盘统计（`/api/dashboard/stats`）
- 健康检查（`/api/health`）

除 `login` 与 `health` 外，接口均需 `Authorization: Bearer <token>`。

## 趋势预警（/api/inspection/trend）

针对数值型巡检项的缓慢劣化（如电机温度连续数日逼近上限但单次仍合格），在单次判定 `judgeItem` 之外提供滚动窗口趋势预警。

### 规则（/api/inspection/trend/rules）

- `POST /api/inspection/trend/rules` 创建规则（草稿）：指定设备类型（空=全部类型）与数值型模板项目，可配置：
  - `windowSize` 滚动窗口（最近 N 个有效样本，默认 10）
  - `minSamples` 最少样本数（默认 min(5, 窗口)），不足则不评估
  - `slopeThreshold` 变化斜率阈值（单位/小时，正=上升、负=下降，最小二乘回归）
  - `fluctuationAmplitude` 波动幅度阈值（窗口内 max-min）
  - `nearMargin` + `nearBoundaryCount` 连续接近正常边界次数（边界取发布时模板正常范围快照）
  - `level` 预警等级（low/medium/high/urgent）、`cooldownHours` 冷却期（默认 24 小时）
- `POST /{id}/publish` 发布；`POST /{id}/disable` / `enable` 停用/启用
- `PUT /{id}` 全量更新；**已发布规则更新后版本递增，新版本只作用于之后的评估**，既有预警与证据保持原版本可解释
- `POST /{id}/reevaluate` 对指定设备按当前版本回放全部历史窗口（幂等，用于补算/验证）

### 评估与去重

- 巡检记录写入事务提交后异步评估命中的已发布规则；同一 (设备, 项目) 的评估固定串行，并发评估不改变结论
- 序列按 (采样时间, 记录ID) 排序；缺失数值的记录确定性剔除；同刻重复读数按 ID 定序（时间跨度为 0 时斜率记为不可计算）
- 迟到数据按采样时间插入序列后，重算其后的所有受影响窗口（评估日志与证据带 `lateRecompute` 标记）
- 同一 `设备:项目:规则:版本:窗口` 只产生一个预警（数据库唯一键）；冷却期（或忽略期）内其它窗口触发的新证据追加到既有事件；结论一致的重算不重复追加证据

### 预警（/api/inspection/trend/alerts）

- `GET /alerts?status=&equipmentId=&ruleId=&level=&page=&size=` 分页查询
- `GET /alerts/{id}` 详情：触发证据、处置记录、评估留痕、窗口时间序列、规则现状（停用/版本更替解释）
- `POST /alerts/{id}/acknowledge` 确认、`POST /alerts/{id}/ignore` 忽略（`ignoreHours` 到期后出现新证据自动重开）、`POST /alerts/{id}/convert` 转维修工单；**任何处置都要求并保留操作者与理由**
- `GET /series?equipmentId=&templateItemId=&from=&to=&page=&size=` 时间序列 + 规则解释：分页样本（缺失值标记 `usable=false`）、各规则状态说明（草稿/已发布/已停用失效）、样本不足差距、最近迟到重算记录

### 测试

```bash
mvn test
```

含趋势引擎单元测试与基于 H2 内存库的端到端集成测试（上升斜率触发、样本不足解释、迟到重算、冷却合并、幂等重算、缺失值/同刻读数、版本更替、处置留痕、异步评估、分页稳定性）。

## 编码说明

数据库使用 utf8mb4，JDBC 连接显式指定 characterEncoding=utf8；Spring Boot 的 JSON 响应默认 UTF-8，中文不乱码。
