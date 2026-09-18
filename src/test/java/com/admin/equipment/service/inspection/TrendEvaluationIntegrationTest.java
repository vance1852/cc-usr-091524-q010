package com.admin.equipment.service.inspection;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.inspection.InspectionRecord;
import com.admin.equipment.model.inspection.InspectionTaskPoint;
import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.model.inspection.TrendAlert;
import com.admin.equipment.model.inspection.TrendAlertEvidence;
import com.admin.equipment.model.inspection.TrendEvaluationLog;
import com.admin.equipment.model.inspection.TrendRule;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.inspection.InspectionRecordRepository;
import com.admin.equipment.repo.inspection.InspectionTaskPointRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import com.admin.equipment.repo.inspection.TrendAlertActionRepository;
import com.admin.equipment.repo.inspection.TrendAlertEvidenceRepository;
import com.admin.equipment.repo.inspection.TrendAlertRepository;
import com.admin.equipment.repo.inspection.TrendEvaluationLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 趋势预警端到端集成测试（H2 内存库）。 */
@SpringBootTest
class TrendEvaluationIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 1, 8, 0);

    @Autowired TrendEvaluationService evaluationService;
    @Autowired TrendRuleService ruleService;
    @Autowired TrendAlertService alertService;
    @Autowired EquipmentRepository equipmentRepo;
    @Autowired InspectionTemplateRepository templateRepo;
    @Autowired InspectionTemplateItemRepository itemRepo;
    @Autowired InspectionTaskPointRepository taskPointRepo;
    @Autowired InspectionRecordRepository recordRepo;
    @Autowired TrendAlertRepository alertRepo;
    @Autowired TrendAlertEvidenceRepository evidenceRepo;
    @Autowired TrendAlertActionRepository actionRepo;
    @Autowired TrendEvaluationLogRepository evalLogRepo;

    // ---------- 测试数据准备 ----------

    private record Fixture(Equipment eq, InspectionTemplateItem item, InspectionTaskPoint tp, TrendRule rule) {}

    private String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Fixture newFixture(Integer windowSize, Integer minSamples,
                               Double slope, Double fluct, Double nearMargin, Integer nearCount) {
        Equipment eq = new Equipment();
        eq.setCode("EQ-T-" + uid());
        eq.setName("测试电机");
        eq.setType("motor");
        eq.setStatus("normal");
        eq = equipmentRepo.save(eq);

        InspectionTemplate tpl = new InspectionTemplate();
        tpl.setCode("TPL-T-" + uid());
        tpl.setName("测试模板");
        tpl.setEquipmentType("motor");
        tpl = templateRepo.save(tpl);

        InspectionTemplateItem item = new InspectionTemplateItem();
        item.setTemplateId(tpl.getId());
        item.setName("定子温度(℃)");
        item.setType("numeric");
        item.setSortOrder(1);
        item.setNormalMin(20.0);
        item.setNormalMax(85.0);
        item = itemRepo.save(item);

        InspectionTaskPoint tp = new InspectionTaskPoint();
        tp.setTaskId(999999L);
        tp.setPointId(999999L);
        tp.setEquipmentIds(String.valueOf(eq.getId()));
        tp.setStatus("in_progress");
        tp = taskPointRepo.save(tp);

        TrendRule rule = ruleService.create(new TrendRuleService.RuleSpec(
                "趋势规则-" + uid(), "motor", item.getId(), windowSize, minSamples,
                slope, fluct, nearMargin, nearCount, "high", 24), "测试员");
        rule = ruleService.publish(rule.getId());
        return new Fixture(eq, item, tp, rule);
    }

    private InspectionRecord saveRecord(Fixture f, Double value, LocalDateTime at) {
        InspectionRecord r = new InspectionRecord();
        r.setTaskId(f.tp().getTaskId());
        r.setTaskPointId(f.tp().getId());
        r.setPointId(f.tp().getPointId());
        r.setTemplateItemId(f.item().getId());
        r.setItemName(f.item().getName());
        r.setItemType("numeric");
        r.setCheckValue(value == null ? "" : String.valueOf(value));
        r.setCheckNumeric(value);
        r.setIsQualified(true);
        r.setIsAbnormal(false);
        r.setRecordedAt(at);
        r.setRecordedBy("测试员");
        return recordRepo.save(r);
    }

    private List<TrendAlert> alertsOf(Fixture f) {
        return alertRepo.search(null, f.eq().getId(), null, null,
                org.springframework.data.domain.PageRequest.of(0, 100)).getContent();
    }

    private List<TrendEvaluationLog> logsOf(Fixture f) {
        return evalLogRepo.findAll().stream()
                .filter(l -> l.getEquipmentId().equals(f.eq().getId())
                        && l.getTemplateItemId().equals(f.item().getId()))
                .toList();
    }

    // ---------- 用例 ----------

    @Test
    void risingSlopeTriggersAlertWithEvidence() {
        Fixture f = newFixture(5, 3, 0.5, null, null, null);
        for (int i = 0; i < 5; i++) {
            saveRecord(f, 60.0 + i, T0.plusHours(i));
        }
        Map<String, Object> r = evaluationService.reevaluate(f.rule().getId(), f.eq().getId());
        assertEquals(5, r.get("evaluatedWindows"));
        assertEquals(3, r.get("triggered"));
        assertEquals(2, r.get("insufficientSamples"));

        List<TrendAlert> alerts = alertsOf(f);
        assertEquals(1, alerts.size());
        TrendAlert a = alerts.get(0);
        assertEquals("open", a.getStatus());
        assertEquals("high", a.getLevel());
        assertEquals(1, a.getRuleVersion());
        assertNotNull(a.getCooldownUntil());
        assertTrue(a.getDetail().contains("斜率"));

        List<TrendAlertEvidence> ev = evidenceRepo.findByAlertIdOrderByIdAsc(a.getId());
        assertEquals(3, ev.size());
        assertTrue(ev.stream().allMatch(e -> "slope".equals(e.getMetric())));
        assertTrue(ev.stream().map(TrendAlertEvidence::getWindowKey).distinct().count() == 3);
    }

    @Test
    void insufficientSamplesIsExplained() {
        Fixture f = newFixture(5, 5, 0.5, null, null, null);
        saveRecord(f, 60.0, T0);
        saveRecord(f, 61.0, T0.plusHours(1));
        evaluationService.reevaluate(f.rule().getId(), f.eq().getId());

        assertTrue(alertsOf(f).isEmpty());
        List<TrendEvaluationLog> logs = logsOf(f);
        assertEquals(2, logs.size());
        assertTrue(logs.stream().allMatch(l -> "insufficient_samples".equals(l.getResult())));
        assertTrue(logs.get(0).getDetail().contains("样本不足"));

        TrendAlertService.SeriesView view = alertService.getSeries(f.eq().getId(), f.item().getId(), null, null, 0, 10);
        assertEquals(2, view.usableSampleCount());
        assertEquals(1, view.rules().size());
        assertEquals("已发布，参与评估", view.rules().get(0).statusNote());
        assertNotNull(view.rules().get(0).sampleGap());
        assertEquals(5, view.rules().get(0).sampleGap().get("required"));
        assertEquals(2L, view.rules().get(0).sampleGap().get("actual"));
    }

    @Test
    void reevaluateIsIdempotentAndWindowsDeduplicate() {
        Fixture f = newFixture(5, 3, 0.5, null, null, null);
        for (int i = 0; i < 5; i++) {
            saveRecord(f, 60.0 + i, T0.plusHours(i));
        }
        evaluationService.reevaluate(f.rule().getId(), f.eq().getId());
        TrendAlert first = alertsOf(f).get(0);
        int evidenceBefore = evidenceRepo.findByAlertIdOrderByIdAsc(first.getId()).size();

        // 再次重算：同一设备、项目、规则版本与窗口仍只产生一个预警，证据不重复
        evaluationService.reevaluate(f.rule().getId(), f.eq().getId());
        List<TrendAlert> alerts = alertsOf(f);
        assertEquals(1, alerts.size());
        assertEquals(first.getId(), alerts.get(0).getId());
        assertEquals(evidenceBefore, evidenceRepo.findByAlertIdOrderByIdAsc(first.getId()).size());
    }

    @Test
    void lateDataRecomputesAffectedWindows() {
        Fixture f = newFixture(4, 3, null, 20.0, null, null);
        InspectionRecord[] recs = new InspectionRecord[5];
        for (int i = 0; i < 5; i++) {
            recs[i] = saveRecord(f, 50.0, T0.plusHours(i));
            evaluationService.doEvaluate(recs[i].getId(), f.eq().getId(), f.rule().getId());
        }
        assertTrue(alertsOf(f).isEmpty());

        // 迟到数据：采样时间落在 r2 与 r3 之间，按采样时间重算其后的所有窗口
        InspectionRecord late = saveRecord(f, 80.0, T0.plusMinutes(90));
        evaluationService.doEvaluate(late.getId(), f.eq().getId(), f.rule().getId());

        List<TrendAlert> alerts = alertsOf(f);
        assertEquals(1, alerts.size());
        TrendAlert a = alerts.get(0);
        assertEquals("rec-" + late.getId(), a.getWindowKey());
        List<TrendAlertEvidence> ev = evidenceRepo.findByAlertIdOrderByIdAsc(a.getId());
        assertEquals(4, ev.size());
        assertEquals(3, ev.stream().filter(e -> Boolean.TRUE.equals(e.getLateRecompute())).count());

        List<TrendEvaluationLog> logs = logsOf(f);
        assertEquals(3, logs.stream().filter(l -> Boolean.TRUE.equals(l.getLateRecompute())).count());
        assertTrue(logs.stream().anyMatch(l -> l.getRecordId().equals(late.getId())));
    }

    @Test
    void missingValuesAreExcludedDeterministically() {
        Fixture f = newFixture(5, 2, null, 100.0, null, null);
        saveRecord(f, 10.0, T0);
        saveRecord(f, null, T0.plusHours(1));   // 缺失值
        saveRecord(f, 20.0, T0.plusHours(2));

        Map<String, Object> r = evaluationService.reevaluate(f.rule().getId(), f.eq().getId());
        assertEquals(2, r.get("usableSamples"));
        assertEquals(2, r.get("evaluatedWindows"));

        TrendAlertService.SeriesView view = alertService.getSeries(f.eq().getId(), f.item().getId(), null, null, 0, 10);
        assertEquals(3, view.samples().size());
        assertEquals(2, view.usableSampleCount());
        long unusable = view.samples().stream().filter(s -> !s.usable()).count();
        assertEquals(1, unusable);
        assertTrue(view.samples().stream().anyMatch(s -> !s.usable() && s.note().contains("缺失数值")));
    }

    @Test
    void sameTimestampDuplicatesKeepDeterministicOrder() {
        Fixture f = newFixture(4, 2, 1.0, null, null, null);
        // 同刻重复读数：时间跨度为 0，斜率不可计算，结论确定且不触发
        saveRecord(f, 61.0, T0);
        saveRecord(f, 62.0, T0);
        saveRecord(f, 63.0, T0);
        Map<String, Object> r = evaluationService.reevaluate(f.rule().getId(), f.eq().getId());
        assertEquals(0, r.get("triggered"));
        assertTrue(alertsOf(f).isEmpty());
        assertTrue(logsOf(f).stream().anyMatch(l -> l.getDetail().contains("时间跨度为 0")));
    }

    @Test
    void ruleVersionBumpOnlyAffectsLaterEvaluations() {
        Fixture f = newFixture(5, 3, 0.5, null, null, null);
        for (int i = 0; i < 5; i++) {
            saveRecord(f, 60.0 + i, T0.plusHours(i));
        }
        evaluationService.reevaluate(f.rule().getId(), f.eq().getId());
        assertEquals(1, alertsOf(f).size());
        assertEquals(1, alertsOf(f).get(0).getRuleVersion());

        // 已发布规则更新 → 版本递增，只作用于之后的评估
        TrendRule v2 = ruleService.update(f.rule().getId(), new TrendRuleService.RuleSpec(
                f.rule().getName(), "motor", f.item().getId(), 5, 3,
                0.6, null, null, null, "high", 24));
        assertEquals(2, v2.getRuleVersion());

        evaluationService.reevaluate(f.rule().getId(), f.eq().getId());
        List<TrendAlert> alerts = alertsOf(f);
        assertEquals(2, alerts.size());
        assertEquals(List.of(1, 2), alerts.stream().map(TrendAlert::getRuleVersion).sorted().toList());
    }

    @Test
    void dispositionsKeepOperatorAndReason() {
        Fixture f = newFixture(5, 3, 0.5, null, null, null);
        for (int i = 0; i < 5; i++) {
            saveRecord(f, 60.0 + i, T0.plusHours(i));
        }
        evaluationService.reevaluate(f.rule().getId(), f.eq().getId());
        TrendAlert a = alertsOf(f).get(0);

        assertThrows(IllegalArgumentException.class,
                () -> alertService.acknowledge(a.getId(), "", "理由"));
        assertThrows(IllegalArgumentException.class,
                () -> alertService.acknowledge(a.getId(), "赵工程师", ""));

        alertService.acknowledge(a.getId(), "赵工程师", "已知悉，持续观察");
        alertService.ignore(a.getId(), "赵工程师", "计划内升温", 72);
        TrendAlertService.ConvertResult cr =
                alertService.convertToWorkOrder(a.getId(), "赵工程师", "转维修排查", "repair", "high", "张维保");

        TrendAlert after = alertRepo.findById(a.getId()).orElseThrow();
        assertEquals("converted", after.getStatus());
        assertEquals(cr.workOrder().getId(), after.getWorkOrderId());
        assertEquals("high", cr.workOrder().getPriority());
        assertTrue(cr.workOrder().getTitle().contains("趋势预警"));

        var actions = actionRepo.findByAlertIdOrderByIdAsc(a.getId());
        assertEquals(3, actions.size());
        assertEquals(List.of("acknowledge", "ignore", "convert_work_order"),
                actions.stream().map(x -> x.getAction()).toList());
        assertTrue(actions.stream().allMatch(x -> !x.getOperator().isBlank() && !x.getReason().isBlank()));

        assertThrows(IllegalArgumentException.class,
                () -> alertService.convertToWorkOrder(a.getId(), "赵工程师", "重复转换", null, null, null));
    }

    @Test
    void disabledRuleIsExplainedAsInactive() {
        Fixture f = newFixture(5, 3, 0.5, null, null, null);
        ruleService.disable(f.rule().getId());
        InspectionRecord rec = saveRecord(f, 60.0, T0);
        evaluationService.doEvaluate(rec.getId(), f.eq().getId(), f.rule().getId());

        List<TrendEvaluationLog> logs = logsOf(f);
        assertEquals(1, logs.size());
        assertEquals("rule_inactive", logs.get(0).getResult());

        TrendAlertService.SeriesView view = alertService.getSeries(f.eq().getId(), f.item().getId(), null, null, 0, 10);
        assertEquals("已停用，规则失效，不再参与评估", view.rules().get(0).statusNote());
    }

    @Test
    void nearBoundaryStreakTriggers() {
        Fixture f = newFixture(5, 3, null, null, 5.0, 3);
        // 上限 85，裕度 5 → 80 及以上算接近边界；连续 3 次触发
        saveRecord(f, 70.0, T0);
        saveRecord(f, 81.0, T0.plusHours(1));
        saveRecord(f, 83.0, T0.plusHours(2));
        saveRecord(f, 84.0, T0.plusHours(3));
        evaluationService.reevaluate(f.rule().getId(), f.eq().getId());

        List<TrendAlert> alerts = alertsOf(f);
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).getTriggeredMetrics().contains("near_boundary"));
        assertTrue(alerts.get(0).getDetail().contains("接近正常边界"));
    }

    @Test
    void asyncEvaluationRunsAfterRecordWrite() throws Exception {
        Fixture f = newFixture(5, 3, 0.5, null, null, null);
        InspectionRecord rec = saveRecord(f, 60.0, T0);
        evaluationService.scheduleEvaluationForRecords(List.of(rec));

        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (!logsOf(f).isEmpty()) {
                return;
            }
            Thread.sleep(100);
        }
        fail("异步评估未在预期时间内完成");
    }

    @Test
    void seriesPaginationIsStable() {
        Fixture f = newFixture(5, 3, 0.5, null, null, null);
        for (int i = 0; i < 30; i++) {
            saveRecord(f, 50.0 + i, T0.plusHours(i));
        }
        TrendAlertService.SeriesView p0 = alertService.getSeries(f.eq().getId(), f.item().getId(), null, null, 0, 10);
        TrendAlertService.SeriesView p1 = alertService.getSeries(f.eq().getId(), f.item().getId(), null, null, 1, 10);
        assertEquals(30, p0.page().totalElements());
        assertEquals(3, p0.page().totalPages());
        assertEquals(10, p0.samples().size());
        assertEquals(10, p1.samples().size());
        // 排序固定 (采样时间, ID)：两页无重叠且全局有序
        var ids0 = p0.samples().stream().map(TrendAlertService.SeriesSample::recordId).toList();
        var ids1 = p1.samples().stream().map(TrendAlertService.SeriesSample::recordId).toList();
        assertTrue(ids0.stream().noneMatch(ids1::contains));
        assertEquals(50.0, p0.samples().get(0).value(), 1e-9);
        assertEquals(60.0, p1.samples().get(0).value(), 1e-9);
    }
}
