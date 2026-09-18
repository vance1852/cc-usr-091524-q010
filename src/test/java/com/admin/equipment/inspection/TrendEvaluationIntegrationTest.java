package com.admin.equipment.inspection;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.*;
import com.admin.equipment.service.inspection.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 趋势预警端到端集成测试（H2 内存库，MySQL 兼容模式）。
 * 覆盖：触发与冷却归并、样本不足解释、迟到重算、窗口幂等、规则版本、
 * 缺失值与同刻重复、分页、评估顺序无关性、并发幂等、处置生命周期。
 */
@SpringBootTest
class TrendEvaluationIntegrationTest {

    @Autowired private InspectionTaskService taskService;
    @Autowired private InspectionPlanService planService;
    @Autowired private TrendRuleService ruleService;
    @Autowired private TrendAlertService alertService;
    @Autowired private TrendEvaluationService evaluationService;
    @Autowired private TrendEvaluationWorker worker;

    @Autowired private TrendRuleRepository ruleRepo;
    @Autowired private TrendAlertRepository alertRepo;
    @Autowired private TrendAlertEvidenceRepository evidenceRepo;
    @Autowired private TrendAlertActionRepository actionRepo;
    @Autowired private TrendEvaluationRepository evaluationRepo;
    @Autowired private InspectionRecordRepository recordRepo;
    @Autowired private InspectionAbnormalityRepository abnormalityRepo;
    @Autowired private InspectionTaskPointRepository taskPointRepo;
    @Autowired private InspectionTaskRepository taskRepo;
    @Autowired private InspectionPlanPointRepository planPointRepo;
    @Autowired private InspectionPlanRepository planRepo;
    @Autowired private InspectionTemplateItemRepository itemRepo;
    @Autowired private InspectionTemplateRepository templateRepo;
    @Autowired private InspectionPointRepository pointRepo;
    @Autowired private EquipmentRepository equipmentRepo;
    @Autowired private WorkOrderRepository workOrderRepo;

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 8, 0);

    private Equipment equipment;
    private InspectionPoint point;
    private InspectionTemplateItem item;
    private InspectionPlan plan;

    @BeforeEach
    void setUp() {
        evaluationRepo.deleteAll();
        evidenceRepo.deleteAll();
        actionRepo.deleteAll();
        alertRepo.deleteAll();
        ruleRepo.deleteAll();
        recordRepo.deleteAll();
        abnormalityRepo.deleteAll();
        taskPointRepo.deleteAll();
        taskRepo.deleteAll();
        planPointRepo.deleteAll();
        planRepo.deleteAll();
        itemRepo.deleteAll();
        templateRepo.deleteAll();
        pointRepo.deleteAll();
        equipmentRepo.deleteAll();
        workOrderRepo.deleteAll();

        equipment = new Equipment();
        equipment.setCode("EQ-T1");
        equipment.setName("测试电机");
        equipment.setType("motor");
        equipment.setStatus("normal");
        equipment = equipmentRepo.save(equipment);

        point = new InspectionPoint();
        point.setCode("IP-T1");
        point.setName("测试巡检点");
        point.setEquipmentIds(String.valueOf(equipment.getId()));
        point.setEquipmentType("motor");
        point = pointRepo.save(point);

        InspectionTemplate template = new InspectionTemplate();
        template.setCode("TPL-T1");
        template.setName("测试电机模板");
        template.setEquipmentType("motor");
        template = templateRepo.save(template);

        InspectionTemplateItem ti = new InspectionTemplateItem();
        ti.setTemplateId(template.getId());
        ti.setName("定子温度");
        ti.setType("numeric");
        ti.setSortOrder(1);
        ti.setNormalMin(20.0);
        ti.setNormalMax(85.0);
        item = itemRepo.save(ti);

        plan = planService.create(new InspectionPlanService.PlanSpec(
                "PLAN-T1", "测试计划", template.getId(), "daily", 1, "day",
                "08:00", "10:00", 120, "", "", "", List.of(point.getId())));
    }

    // ---------- 工具 ----------

    private record Reading(Double value, LocalDateTime time) {}

    private TrendRule newRule(Double slope, Double amplitude, Double margin, Integer nearLimit,
                              int minSamples, int cooldownHours) {
        return ruleService.create(new TrendRuleService.RuleSpec(
                "测试规则", "motor", item.getId(), 168, minSamples,
                slope, amplitude, margin, nearLimit, "high", cooldownHours), "tester");
    }

    /** 走完整巡检执行链路写入一批读数（生成任务→开始→执行），返回保存的记录 */
    private List<InspectionRecord> writeBatch(List<Reading> readings) {
        InspectionTask task = taskService.generateTask(plan.getId(), null, "测试员", false, null);
        taskService.startTask(task.getId(), "测试员");
        InspectionTaskPoint tp = taskService.getTaskPoints(task.getId()).get(0);
        List<InspectionTaskService.PointItemSpec> items = new ArrayList<>();
        for (Reading r : readings) {
            String value = r.value() == null ? "" : String.valueOf(r.value());
            items.add(new InspectionTaskService.PointItemSpec(item.getId(), value, r.value(), "", r.time()));
        }
        return taskService.executePoint(task.getId(), tp.getId(), "测试员", items, null).records();
    }

    private void awaitEvaluations(long count) {
        await().atMost(15, SECONDS).until(() -> evaluationRepo.count() >= count);
    }

    // ---------- 用例 ----------

    @Test
    void risingTrendTriggersSingleAlertAndCooldownAppendsEvidence() {
        newRule(1.0, null, null, null, 3, 24);
        // 连续 3 天每天上升 2 度：斜率 2/天 ≥ 阈值 1
        writeBatch(List.of(
                new Reading(50.0, T0),
                new Reading(52.0, T0.plusDays(1)),
                new Reading(54.0, T0.plusDays(2))));
        awaitEvaluations(3);

        assertEquals(1, alertRepo.count(), "只应产生一个预警事件");
        TrendAlert alert = alertRepo.findAll().get(0);
        assertEquals("open", alert.getStatus());
        assertEquals("high", alert.getLevel());
        assertEquals(T0.plusDays(2), alert.getWindowEnd());
        assertEquals(1, evidenceRepo.count());
        TrendAlertEvidence ev = evidenceRepo.findAll().get(0);
        assertEquals(3, ev.getSampleCount());
        assertEquals(2.0, ev.getSlope(), 0.0001);
        assertEquals("slope", ev.getTriggeredMetrics());
        assertFalse(ev.getLateRecompute(), "最新窗口的评估不是迟到重算");

        // 冷却期内第 4 天继续上升：新证据追加到既有事件，不新开预警
        writeBatch(List.of(new Reading(56.0, T0.plusDays(3))));
        awaitEvaluations(4);
        assertEquals(1, alertRepo.count());
        assertEquals(2, evidenceRepo.count());
        alert = alertRepo.findAll().get(0);
        assertEquals(2, alert.getActiveEvidenceCount());
        assertNotNull(alert.getCooldownUntil());
        assertTrue(alert.getTriggerSummary().contains("变化斜率"));
    }

    @Test
    void insufficientSamplesAreExplained() {
        TrendRule rule = newRule(1.0, null, null, null, 5, 24);
        writeBatch(List.of(
                new Reading(50.0, T0),
                new Reading(52.0, T0.plusDays(1)),
                new Reading(54.0, T0.plusDays(2))));
        awaitEvaluations(3);

        assertEquals(0, alertRepo.count(), "样本不足不应产生预警");
        List<TrendEvaluation> evals = evaluationRepo.findAll();
        assertEquals(3, evals.size());
        for (TrendEvaluation e : evals) {
            assertEquals("insufficient_samples", e.getOutcome());
            assertTrue(e.getDetail().contains("少于最少样本"), "应解释样本不足: " + e.getDetail());
        }
        // 序列接口的规则状态也应给出样本不足解释
        Map<String, Object> series = alertService.getSeries(
                equipment.getId(), item.getId(), null, null, 0, 10, "asc");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) series.get("rules");
        assertEquals(1, rules.size());
        assertEquals(rule.getId(), rules.get(0).get("ruleId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) rules.get(0).get("latestEvaluation");
        assertEquals("insufficient_samples", latest.get("outcome"));
    }

    @Test
    void lateDataRecomputesAffectedWindows() {
        newRule(null, 10.0, null, null, 2, 24);
        // 先在途数据：波动很小，不触发
        writeBatch(List.of(
                new Reading(50.0, T0.plusDays(1)),
                new Reading(52.0, T0.plusDays(2)),
                new Reading(54.0, T0.plusDays(3))));
        awaitEvaluations(3);
        assertEquals(0, alertRepo.count());

        // 迟到数据：T0 时刻实际为 70 度，按采样时间重算受影响窗口
        writeBatch(List.of(new Reading(70.0, T0)));
        awaitEvaluations(3 + 4);

        assertEquals(1, alertRepo.count(), "迟到重算后应只产生一个事件");
        List<TrendAlertEvidence> evidences = evidenceRepo.findAll();
        assertEquals(3, evidences.size(), "T0 自身窗口样本不足，T1~T3 三个窗口被重算触发");
        for (TrendAlertEvidence ev : evidences) {
            assertTrue(ev.getLateRecompute(), "迟到重算的证据应打标");
            assertEquals(20.0, ev.getAmplitude(), 0.0001);
        }
        // 评估日志中应能找到迟到重算的解释：
        // 首批 3 条同批写入，其中 t1/t2 在更晚样本存在后评估（2 条），迟到批扇出 4 条，共 6 条
        List<TrendEvaluation> lateEvals = evaluationRepo.findAll().stream()
                .filter(e -> Boolean.TRUE.equals(e.getLateRecompute())).toList();
        assertEquals(6, lateEvals.size());
        assertTrue(lateEvals.stream().anyMatch(e -> "insufficient_samples".equals(e.getOutcome())));
    }

    @Test
    void sameWindowEvaluatedRepeatedlyYieldsSingleAlert() {
        TrendRule rule = newRule(1.0, null, null, null, 3, 24);
        writeBatch(List.of(
                new Reading(50.0, T0),
                new Reading(52.0, T0.plusDays(1)),
                new Reading(54.0, T0.plusDays(2))));
        awaitEvaluations(3);
        assertEquals(1, alertRepo.count());

        // 同一窗口重复评估（模拟异步重试/重复事件）：结论幂等
        worker.evaluateAnchor(rule.getId(), equipment.getId(), item.getId(), T0.plusDays(2), false);
        worker.evaluateAnchor(rule.getId(), equipment.getId(), item.getId(), T0.plusDays(2), false);
        assertEquals(1, alertRepo.count());
        assertEquals(1, evidenceRepo.count());
    }

    @Test
    void ruleVersioningAppliesOnlyToLaterEvaluations() {
        TrendRule rule = newRule(1.0, null, null, null, 3, 24);
        writeBatch(List.of(
                new Reading(50.0, T0),
                new Reading(52.0, T0.plusDays(1)),
                new Reading(54.0, T0.plusDays(2))));
        awaitEvaluations(3);
        assertEquals(1, alertRepo.count());
        assertEquals(1, alertRepo.findAll().get(0).getRuleVersion());

        // 修改规则参数：版本自增，阈值调高后新数据不再触发
        ruleService.update(rule.getId(), new TrendRuleService.RuleSpec(
                "测试规则", "motor", item.getId(), 168, 3,
                100.0, null, null, null, "high", 24));
        assertEquals(2, ruleRepo.findById(rule.getId()).orElseThrow().getVersion());

        writeBatch(List.of(new Reading(56.0, T0.plusDays(3))));
        awaitEvaluations(4);

        assertEquals(1, alertRepo.count(), "新版本未触发，不应新开预警");
        TrendEvaluation latest = evaluationRepo.findFirstByRuleIdAndEquipmentIdAndTemplateItemIdOrderByIdDesc(
                rule.getId(), equipment.getId(), item.getId()).orElseThrow();
        assertEquals(2, latest.getRuleVersion());
        assertEquals("not_triggered", latest.getOutcome());

        // 评估日志接口应标注旧版本评估已失效
        TrendAlertService.PageResult<Map<String, Object>> page = alertService.listEvaluations(
                rule.getId(), equipment.getId(), item.getId(), null, 0, 20);
        assertEquals(4, page.totalElements());
        Map<String, Object> latestRow = page.content().get(0);
        assertEquals(false, latestRow.get("ruleSuperseded"));
        assertTrue(page.content().stream().skip(1)
                .allMatch(r -> Boolean.TRUE.equals(r.get("ruleSuperseded"))));
    }

    @Test
    void dispositionLifecycleKeepsOperatorAndReason() {
        newRule(1.0, null, null, null, 3, 24);
        writeBatch(List.of(
                new Reading(50.0, T0),
                new Reading(52.0, T0.plusDays(1)),
                new Reading(54.0, T0.plusDays(2))));
        awaitEvaluations(3);
        TrendAlert alert = alertRepo.findAll().get(0);
        Long alertId = alert.getId();

        assertThrows(IllegalArgumentException.class,
                () -> alertService.acknowledge(alertId, "", "理由"));
        assertThrows(IllegalArgumentException.class,
                () -> alertService.acknowledge(alertId, "赵工程师", " "));

        alertService.acknowledge(alertId, "赵工程师", "现场确认温升趋势");
        alert = alertRepo.findById(alertId).orElseThrow();
        assertEquals("acknowledged", alert.getStatus());
        assertEquals("赵工程师", alert.getAcknowledgedBy());
        assertEquals("现场确认温升趋势", alert.getAcknowledgeReason());
        assertNotNull(alert.getAcknowledgedAt());

        alertService.ignore(alertId, "赵工程师", "等待备件到货", 48);
        alert = alertRepo.findById(alertId).orElseThrow();
        assertEquals("ignored", alert.getStatus());
        assertEquals("赵工程师", alert.getIgnoredBy());
        assertNotNull(alert.getIgnoreUntil());

        WorkOrder wo = alertService.convertToWorkOrder(alertId, "赵工程师", "安排停机检修",
                "repair", null, "张维保");
        assertEquals("repair", wo.getType());
        assertEquals("high", wo.getPriority(), "等级 high 应映射为工单优先级 high");
        assertEquals(equipment.getId(), wo.getEquipmentId());
        alert = alertRepo.findById(alertId).orElseThrow();
        assertEquals("wo_created", alert.getStatus());
        assertEquals(wo.getId(), alert.getWorkOrderId());
        assertEquals("赵工程师", alert.getWoCreatedBy());
        assertEquals("安排停机检修", alert.getWoReason());

        assertThrows(IllegalArgumentException.class,
                () -> alertService.acknowledge(alertId, "赵工程师", "重复确认"));

        List<TrendAlertAction> actions = actionRepo.findByAlertIdOrderByCreatedAtAscIdAsc(alertId);
        assertEquals(3, actions.size());
        assertEquals(List.of("acknowledge", "ignore", "work_order"),
                actions.stream().map(TrendAlertAction::getAction).toList());
        for (TrendAlertAction a : actions) {
            assertEquals("赵工程师", a.getOperator());
            assertFalse(a.getReason().isBlank(), "任何处置都必须保留理由");
        }
    }

    @Test
    void missingAndDuplicateReadingsAreHandledDeterministically() {
        newRule(null, 5.0, null, null, 2, 24);
        // t2 时刻两条同刻读数 56/58（合并为 57），t3 为缺失值
        InspectionTask task = taskService.generateTask(plan.getId(), null, "测试员", false, null);
        taskService.startTask(task.getId(), "测试员");
        InspectionTaskPoint tp = taskService.getTaskPoints(task.getId()).get(0);
        taskService.executePoint(task.getId(), tp.getId(), "测试员", List.of(
                new InspectionTaskService.PointItemSpec(item.getId(), "50.0", 50.0, "", T0),
                new InspectionTaskService.PointItemSpec(item.getId(), "56.0", 56.0, "", T0.plusDays(1)),
                new InspectionTaskService.PointItemSpec(item.getId(), "58.0", 58.0, "", T0.plusDays(1)),
                new InspectionTaskService.PointItemSpec(item.getId(), "", null, "", T0.plusDays(2))
        ), null);
        awaitEvaluations(3);

        assertEquals(1, alertRepo.count());
        List<TrendAlertEvidence> evidences = evidenceRepo.findAll().stream()
                .sorted((a, b) -> a.getWindowEnd().compareTo(b.getWindowEnd())).toList();
        assertEquals(2, evidences.size());
        TrendAlertEvidence evT2 = evidences.get(0);
        assertEquals(T0.plusDays(1), evT2.getWindowEnd());
        assertEquals(1, evT2.getDuplicateCount(), "同刻重复读数应合并计数");
        assertEquals(2, evT2.getSampleCount(), "同刻读数合并后有效样本为 2");
        assertEquals(7.0, evT2.getAmplitude(), 0.0001, "同刻读数按均值合并：(56+58)/2=57，幅度=57-50=7");
        TrendAlertEvidence evT3 = evidences.get(1);
        assertEquals(1, evT3.getMissingCount(), "缺失值应剔除并计数");
        assertEquals(7.0, evT3.getAmplitude(), 0.0001);
    }

    @Test
    void seriesPaginationAndSummaryAreStable() {
        List<Reading> readings = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            readings.add(new Reading(50.0 + i, T0.plusHours(i)));
        }
        writeBatch(readings);

        Map<String, Object> page0 = alertService.getSeries(equipment.getId(), item.getId(),
                null, null, 0, 10, "asc");
        @SuppressWarnings("unchecked")
        Map<String, Object> pageMeta = (Map<String, Object>) page0.get("page");
        assertEquals(25L, pageMeta.get("totalElements"));
        assertEquals(3, pageMeta.get("totalPages"));
        @SuppressWarnings("unchecked")
        List<InspectionRecord> records = (List<InspectionRecord>) page0.get("records");
        assertEquals(10, records.size());
        assertEquals(T0, records.get(0).getRecordedAt(), "升序分页首条应为最早读数");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) page0.get("summary");
        assertEquals(25L, summary.get("total"));
        assertEquals(25L, summary.get("valid"));
        assertEquals(0L, summary.get("missing"));
        assertEquals(50.0, summary.get("min"));
        assertEquals(74.0, summary.get("max"));

        Map<String, Object> desc = alertService.getSeries(equipment.getId(), item.getId(),
                null, null, 0, 10, "desc");
        @SuppressWarnings("unchecked")
        List<InspectionRecord> descRecords = (List<InspectionRecord>) desc.get("records");
        assertEquals(T0.plusHours(24), descRecords.get(0).getRecordedAt());
    }

    @Test
    void evaluationOrderDoesNotChangeConclusions() {
        TrendRule rule = newRule(null, 10.0, null, null, 2, 24);
        // 直接落库（不触发异步评估），随后按乱序手动评估
        saveRecordDirectly(50.0, T0);
        saveRecordDirectly(80.0, T0.plusDays(1));
        saveRecordDirectly(52.0, T0.plusDays(2));

        // 乱序：先评估最新的窗口，再回头评估历史窗口
        worker.evaluateAnchor(rule.getId(), equipment.getId(), item.getId(), T0.plusDays(2), false);
        worker.evaluateAnchor(rule.getId(), equipment.getId(), item.getId(), T0, false);
        worker.evaluateAnchor(rule.getId(), equipment.getId(), item.getId(), T0.plusDays(1), false);

        assertEquals(1, alertRepo.count(), "无论评估顺序如何，同一组合+窗口只应有一个事件");
        List<TrendAlertEvidence> evidences = evidenceRepo.findAll();
        assertEquals(2, evidences.size(), "T1、T2 两个窗口触发");
        double ampSum = evidences.stream().mapToDouble(TrendAlertEvidence::getAmplitude).sum();
        assertEquals(60.0, ampSum, 0.0001, "两窗口幅度均为 30，与评估顺序无关");
        List<TrendEvaluation> evals = evaluationRepo.findAll();
        assertEquals(3, evals.size());
        assertEquals(1, evals.stream().filter(e -> "insufficient_samples".equals(e.getOutcome())).count());
        assertEquals(2, evals.stream().filter(e -> "triggered".equals(e.getOutcome())).count());
    }

    @Test
    void concurrentEvaluationOfSameWindowIsIdempotent() {
        newRule(1.0, null, null, null, 3, 24);
        List<InspectionRecord> records = writeBatch(List.of(
                new Reading(50.0, T0),
                new Reading(52.0, T0.plusDays(1)),
                new Reading(54.0, T0.plusDays(2))));
        awaitEvaluations(3);
        assertEquals(1, alertRepo.count());

        // 同一批记录并发触发两次评估事件：序列锁 + 唯一约束保证结论不变
        List<Long> ids = records.stream().map(InspectionRecord::getId).toList();
        InspectionRecordsSavedEvent event = new InspectionRecordsSavedEvent(ids);
        Thread t1 = new Thread(() -> evaluationService.onRecordsSaved(event));
        Thread t2 = new Thread(() -> evaluationService.onRecordsSaved(event));
        t1.start();
        t2.start();
        awaitEvaluations(3 + 6);

        assertEquals(1, alertRepo.count(), "并发评估不应产生重复预警");
        assertEquals(1, evidenceRepo.count(), "并发评估不应产生重复证据");
    }

    @Test
    void ignoreExpiryReopensAlertOnNewEvidence() {
        TrendRule rule = newRule(1.0, null, null, null, 3, 24);
        writeBatch(List.of(
                new Reading(50.0, T0),
                new Reading(52.0, T0.plusDays(1)),
                new Reading(54.0, T0.plusDays(2))));
        awaitEvaluations(3);
        TrendAlert alert = alertRepo.findAll().get(0);
        Long alertId = alert.getId();

        alertService.ignore(alertId, "赵工程师", "暂不处理", 1);
        // 将忽略到期时间拨到过去，模拟忽略到期
        alert = alertRepo.findById(alertId).orElseThrow();
        alert.setIgnoreUntil(LocalDateTime.now().minusMinutes(1));
        alertRepo.save(alert);

        TrendAlertService.AlertDetail detail = alertService.getAlertDetail(alert.getId()).orElseThrow();
        assertEquals("open", detail.view().effectiveStatus(), "忽略到期后有效状态应恢复为待处理");
        assertTrue(detail.explanations().stream().anyMatch(e -> e.contains("到期")));

        // 新证据到达：忽略已到期，预警自动重开并留痕
        writeBatch(List.of(new Reading(56.0, T0.plusDays(3))));
        awaitEvaluations(4);
        alert = alertRepo.findById(alertId).orElseThrow();
        assertEquals("open", alert.getStatus());
        assertTrue(actionRepo.findByAlertIdOrderByCreatedAtAscIdAsc(alert.getId()).stream()
                .anyMatch(a -> "reopen".equals(a.getAction()) && "system".equals(a.getOperator())));
        assertEquals(2, evidenceRepo.count());
        assertEquals(rule.getId(), alert.getRuleId());
    }

    @Test
    void lateDataCanRetractPreviousTrigger() {
        // 连续接近边界规则：正常上限 85，余量 5，连续 3 次接近触发
        newRule(null, null, 5.0, 3, 3, 24);
        writeBatch(List.of(
                new Reading(81.0, T0),
                new Reading(82.0, T0.plusDays(1)),
                new Reading(83.0, T0.plusDays(2))));
        awaitEvaluations(3);
        assertEquals(1, alertRepo.count());
        TrendAlertEvidence ev = evidenceRepo.findAll().get(0);
        assertEquals(3, ev.getNearBoundCount());
        assertFalse(ev.getRetracted());

        // 迟到数据插入中间时刻，打破连续接近边界序列，重算后不再触发
        writeBatch(List.of(new Reading(50.0, T0.plusDays(1).plusHours(12))));
        awaitEvaluations(3 + 2);

        assertEquals(1, alertRepo.count(), "事件保留用于审计，不重复也不删除");
        ev = evidenceRepo.findAll().get(0);
        assertTrue(ev.getRetracted(), "迟到重算后不再触发，证据应撤回但保留");
        assertTrue(ev.getDetail().contains("撤回"));
        assertEquals(0, alertRepo.findAll().get(0).getActiveEvidenceCount());
        TrendAlertService.AlertDetail detail = alertService.getAlertDetail(ev.getAlertId()).orElseThrow();
        assertTrue(detail.explanations().stream().anyMatch(e -> e.contains("撤回")));
    }

    private void saveRecordDirectly(double value, LocalDateTime time) {
        InspectionRecord rec = new InspectionRecord();
        rec.setTaskId(-1L);
        rec.setTaskPointId(-1L);
        rec.setPointId(point.getId());
        rec.setTemplateItemId(item.getId());
        rec.setItemName(item.getName());
        rec.setItemType("numeric");
        rec.setCheckValue(String.valueOf(value));
        rec.setCheckNumeric(value);
        rec.setIsQualified(true);
        rec.setIsAbnormal(false);
        rec.setRecordedAt(time);
        rec.setRecordedBy("tester");
        recordRepo.save(rec);
    }
}
