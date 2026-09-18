package com.admin.equipment.service.inspection;

import com.admin.equipment.model.Equipment;
import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.*;
import com.admin.equipment.repo.EquipmentRepository;
import com.admin.equipment.repo.WorkOrderRepository;
import com.admin.equipment.repo.inspection.*;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 趋势预警的查询与处置：确认、忽略（带到期）、转维修工单。
 * 所有处置强制记录操作者与理由，并写入处置留痕。
 */
@Service
public class TrendAlertService {

    private static final Set<String> WO_TYPES = Set.of("inspection", "repair", "maintenance");
    private static final Set<String> WO_PRIORITIES = Set.of("low", "medium", "high", "urgent");

    private final TrendAlertRepository alertRepo;
    private final TrendAlertEvidenceRepository evidenceRepo;
    private final TrendAlertActionRepository actionRepo;
    private final TrendEvaluationRepository evaluationRepo;
    private final TrendRuleRepository ruleRepo;
    private final InspectionRecordRepository recordRepo;
    private final InspectionTemplateItemRepository itemRepo;
    private final EquipmentRepository equipmentRepo;
    private final WorkOrderRepository workOrderRepo;
    private final TrendEvaluationWorker worker;

    public TrendAlertService(TrendAlertRepository alertRepo,
                             TrendAlertEvidenceRepository evidenceRepo,
                             TrendAlertActionRepository actionRepo,
                             TrendEvaluationRepository evaluationRepo,
                             TrendRuleRepository ruleRepo,
                             InspectionRecordRepository recordRepo,
                             InspectionTemplateItemRepository itemRepo,
                             EquipmentRepository equipmentRepo,
                             WorkOrderRepository workOrderRepo,
                             TrendEvaluationWorker worker) {
        this.alertRepo = alertRepo;
        this.evidenceRepo = evidenceRepo;
        this.actionRepo = actionRepo;
        this.evaluationRepo = evaluationRepo;
        this.ruleRepo = ruleRepo;
        this.recordRepo = recordRepo;
        this.itemRepo = itemRepo;
        this.equipmentRepo = equipmentRepo;
        this.workOrderRepo = workOrderRepo;
        this.worker = worker;
    }

    public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}

    /** 预警视图：附带有效状态（忽略到期视为待处理）与规则版本注解 */
    public record AlertView(TrendAlert alert, String effectiveStatus,
                            Integer ruleCurrentVersion, Boolean ruleEnabled, boolean ruleSuperseded) {}

    public PageResult<AlertView> listAlerts(String status, String level, Long equipmentId, Long ruleId,
                                            int page, int size) {
        Specification<TrendAlert> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null && !status.isBlank()) ps.add(cb.equal(root.get("status"), status));
            if (level != null && !level.isBlank()) ps.add(cb.equal(root.get("level"), level));
            if (equipmentId != null) ps.add(cb.equal(root.get("equipmentId"), equipmentId));
            if (ruleId != null) ps.add(cb.equal(root.get("ruleId"), ruleId));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        PageRequest pr = PageRequest.of(Math.max(0, page), clampSize(size),
                Sort.by(Sort.Direction.DESC, "lastEvidenceAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<TrendAlert> result = alertRepo.findAll(spec, pr);
        Map<Long, TrendRule> ruleCache = new HashMap<>();
        List<AlertView> views = result.getContent().stream()
                .map(a -> toView(a, ruleCache)).toList();
        return new PageResult<>(views, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    public record AlertDetail(AlertView view, List<TrendAlertEvidence> evidences,
                              List<TrendAlertAction> actions, List<String> explanations) {}

    public Optional<AlertDetail> getAlertDetail(Long id) {
        Optional<TrendAlert> opt = alertRepo.findById(id);
        if (opt.isEmpty()) return Optional.empty();
        TrendAlert alert = opt.get();
        TrendRule rule = alert.getRuleId() != null ? ruleRepo.findById(alert.getRuleId()).orElse(null) : null;
        List<TrendAlertEvidence> evidences = evidenceRepo.findByAlertIdOrderByWindowEndAsc(alert.getId());
        List<TrendAlertAction> actions = actionRepo.findByAlertIdOrderByCreatedAtAscIdAsc(alert.getId());
        List<String> explanations = new ArrayList<>();

        if (rule == null) {
            explanations.add("规则已删除，本预警为规则版本 " + alert.getRuleVersion()
                    + " 产生的历史结论，不再更新");
        } else {
            if (rule.getVersion() > alert.getRuleVersion()) {
                explanations.add("规则已更新至版本 " + rule.getVersion() + "，本预警基于版本 "
                        + alert.getRuleVersion() + "，新版本只作用于之后的评估");
            }
            if (!Boolean.TRUE.equals(rule.getEnabled())) {
                explanations.add("规则已停用，后续新数据不再评估，本预警保持当前状态");
            }
        }
        if ("ignored".equals(alert.getStatus()) && alert.getIgnoreUntil() != null
                && LocalDateTime.now().isAfter(alert.getIgnoreUntil())) {
            explanations.add("忽略已于 " + fmtTime(alert.getIgnoreUntil()) + " 到期，有效状态恢复为待处理");
        }
        boolean hasLate = evidences.stream().anyMatch(e -> Boolean.TRUE.equals(e.getLateRecompute()));
        if (hasLate) {
            explanations.add("包含迟到数据按采样时间重算后产生的证据");
        }
        long retracted = evidences.stream().filter(e -> Boolean.TRUE.equals(e.getRetracted())).count();
        if (retracted > 0) {
            explanations.add(retracted + " 条证据经迟到数据重算后不再满足触发条件，已撤回但保留审计记录");
        }
        return Optional.of(new AlertDetail(toView(alert, rule), evidences, actions, explanations));
    }

    @Transactional
    public TrendAlert acknowledge(Long alertId, String operator, String reason) {
        requireOperatorAndReason(operator, reason);
        TrendAlert alert = alertRepo.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("预警不存在"));
        if ("wo_created".equals(alert.getStatus())) {
            throw new IllegalArgumentException("预警已转工单，不能再确认");
        }
        alert.setStatus("acknowledged");
        alert.setAcknowledgedBy(operator);
        alert.setAcknowledgedAt(LocalDateTime.now());
        alert.setAcknowledgeReason(reason);
        logAction(alert.getId(), "acknowledge", operator, reason, null);
        return alertRepo.save(alert);
    }

    @Transactional
    public TrendAlert ignore(Long alertId, String operator, String reason, Integer ignoreHours) {
        requireOperatorAndReason(operator, reason);
        int hours = ignoreHours == null ? 24 : ignoreHours;
        if (hours <= 0) throw new IllegalArgumentException("忽略时长必须大于 0 小时");
        TrendAlert alert = alertRepo.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("预警不存在"));
        if ("wo_created".equals(alert.getStatus())) {
            throw new IllegalArgumentException("预警已转工单，不能再忽略");
        }
        LocalDateTime until = LocalDateTime.now().plusHours(hours);
        alert.setStatus("ignored");
        alert.setIgnoredBy(operator);
        alert.setIgnoredAt(LocalDateTime.now());
        alert.setIgnoreReason(reason);
        alert.setIgnoreUntil(until);
        logAction(alert.getId(), "ignore", operator, reason, "忽略至 " + fmtTime(until));
        return alertRepo.save(alert);
    }

    @Transactional
    public WorkOrder convertToWorkOrder(Long alertId, String operator, String reason,
                                        String type, String priority, String assignee) {
        requireOperatorAndReason(operator, reason);
        TrendAlert alert = alertRepo.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("预警不存在"));
        if (alert.getWorkOrderId() != null) {
            throw new IllegalArgumentException("预警已转工单，工单ID：" + alert.getWorkOrderId());
        }
        Equipment equipment = equipmentRepo.findById(alert.getEquipmentId())
                .orElseThrow(() -> new IllegalArgumentException("预警关联的设备不存在"));

        WorkOrder wo = new WorkOrder();
        wo.setEquipmentId(equipment.getId());
        wo.setTitle("[趋势预警] " + equipment.getName() + " " + alert.getItemName() + " " + alert.getRuleName());
        wo.setType(type != null && WO_TYPES.contains(type) ? type : "repair");
        String pri = (priority != null && WO_PRIORITIES.contains(priority)) ? priority
                : switch (alert.getLevel() == null ? "" : alert.getLevel()) {
            case "urgent" -> "urgent";
            case "high" -> "high";
            case "low" -> "low";
            default -> "medium";
        };
        wo.setPriority(pri);
        wo.setDescription("来源：趋势预警\n预警ID：" + alert.getId()
                + "\n规则：" + alert.getRuleName() + "（版本 " + alert.getRuleVersion() + "）"
                + "\n设备：" + equipment.getName() + "（" + equipment.getCode() + "）"
                + "\n项目：" + alert.getItemName()
                + "\n" + alert.getTriggerSummary()
                + "\n处置理由：" + reason);
        wo.setAssignee(assignee == null ? "" : assignee);
        wo.setStatus("open");
        WorkOrder saved = workOrderRepo.save(wo);

        alert.setWorkOrderId(saved.getId());
        alert.setWoCreatedBy(operator);
        alert.setWoCreatedAt(LocalDateTime.now());
        alert.setWoReason(reason);
        alert.setStatus("wo_created");
        alertRepo.save(alert);
        logAction(alert.getId(), "work_order", operator, reason, "工单ID：" + saved.getId());
        return saved;
    }

    /** 设备-项目数值序列：分页返回，并附各匹配规则的最近评估结论与解释 */
    public Map<String, Object> getSeries(Long equipmentId, Long templateItemId,
                                         LocalDateTime start, LocalDateTime end,
                                         int page, int size, String order) {
        Equipment equipment = equipmentRepo.findById(equipmentId)
                .orElseThrow(() -> new IllegalArgumentException("设备不存在"));
        InspectionTemplateItem item = itemRepo.findById(templateItemId)
                .orElseThrow(() -> new IllegalArgumentException("模板项目不存在"));
        LocalDateTime rangeStart = start == null ? LocalDateTime.of(1970, 1, 1, 0, 0) : start;
        LocalDateTime rangeEnd = end == null ? LocalDateTime.now().plusDays(1) : end;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("equipment", Map.of("id", equipment.getId(), "code", equipment.getCode(),
                "name", equipment.getName(), "type", equipment.getType() == null ? "" : equipment.getType()));
        result.put("item", Map.of("id", item.getId(), "name", item.getName(), "type", item.getType(),
                "normalMin", item.getNormalMin() == null ? "" : item.getNormalMin(),
                "normalMax", item.getNormalMax() == null ? "" : item.getNormalMax()));

        List<Long> pointIds = worker.pointIdsOf(equipmentId);
        if (pointIds.isEmpty()) {
            result.put("records", List.of());
            result.put("page", Map.of("page", 0, "size", clampSize(size), "totalElements", 0, "totalPages", 0));
            result.put("summary", Map.of("total", 0, "valid", 0, "missing", 0));
            result.put("rules", List.of());
            result.put("explanations", List.of("设备未关联任何巡检点，无数值序列"));
            return result;
        }

        Sort sort = "desc".equalsIgnoreCase(order)
                ? Sort.by(Sort.Direction.DESC, "recordedAt").and(Sort.by(Sort.Direction.DESC, "id"))
                : Sort.by(Sort.Direction.ASC, "recordedAt").and(Sort.by(Sort.Direction.ASC, "id"));
        PageRequest pr = PageRequest.of(Math.max(0, page), clampSize(size), sort);
        Page<InspectionRecord> records = recordRepo.findByTemplateItemIdAndPointIdInAndRecordedAtBetween(
                templateItemId, pointIds, rangeStart, rangeEnd, pr);
        result.put("records", records.getContent());
        result.put("page", Map.of("page", records.getNumber(), "size", records.getSize(),
                "totalElements", records.getTotalElements(), "totalPages", records.getTotalPages()));

        Object[] summary = recordRepo.summarizeSeries(templateItemId, pointIds, rangeStart, rangeEnd);
        Object[] row = summary;
        if (summary.length == 1 && summary[0] instanceof Object[]) row = (Object[]) summary[0];
        long total = row != null && row[0] != null ? ((Number) row[0]).longValue() : 0;
        long valid = row != null && row[1] != null ? ((Number) row[1]).longValue() : 0;
        Map<String, Object> summaryMap = new LinkedHashMap<>();
        summaryMap.put("total", total);
        summaryMap.put("valid", valid);
        summaryMap.put("missing", total - valid);
        summaryMap.put("min", row != null ? row[2] : null);
        summaryMap.put("max", row != null ? row[3] : null);
        summaryMap.put("firstAt", row != null ? row[4] : null);
        summaryMap.put("lastAt", row != null ? row[5] : null);
        result.put("summary", summaryMap);

        List<Map<String, Object>> ruleStatuses = new ArrayList<>();
        for (TrendRule rule : ruleRepo.findByTemplateItemId(templateItemId)) {
            if (!TrendRuleService.typeMatches(rule.getEquipmentType(), equipment.getType())) continue;
            Map<String, Object> rs = new LinkedHashMap<>();
            rs.put("ruleId", rule.getId());
            rs.put("ruleName", rule.getName());
            rs.put("version", rule.getVersion());
            rs.put("enabled", rule.getEnabled());
            List<String> explanations = new ArrayList<>();
            Optional<TrendEvaluation> latestOpt = evaluationRepo
                    .findFirstByRuleIdAndEquipmentIdAndTemplateItemIdOrderByIdDesc(
                            rule.getId(), equipmentId, templateItemId);
            if (latestOpt.isEmpty()) {
                rs.put("latestEvaluation", null);
                explanations.add(Boolean.TRUE.equals(rule.getEnabled())
                        ? "尚无评估记录，等待新数据写入后触发评估"
                        : "规则已停用，新数据不再评估");
            } else {
                TrendEvaluation latest = latestOpt.get();
                Map<String, Object> evalMap = new LinkedHashMap<>();
                evalMap.put("outcome", latest.getOutcome());
                evalMap.put("detail", latest.getDetail());
                evalMap.put("lateRecompute", latest.getLateRecompute());
                evalMap.put("ruleVersion", latest.getRuleVersion());
                evalMap.put("windowEnd", latest.getWindowEnd());
                evalMap.put("createdAt", latest.getCreatedAt());
                rs.put("latestEvaluation", evalMap);
                if (Boolean.TRUE.equals(latest.getLateRecompute())) {
                    explanations.add("最近一次评估由迟到数据触发，受影响窗口已按采样时间重算");
                }
                if (latest.getRuleVersion() < rule.getVersion()) {
                    explanations.add("规则已更新至版本 " + rule.getVersion() + "，该评估基于版本 "
                            + latest.getRuleVersion() + "，新版本只作用于之后的评估");
                }
                if (!Boolean.TRUE.equals(rule.getEnabled())) {
                    explanations.add("规则已停用，新数据不再评估");
                }
            }
            rs.put("explanations", explanations);
            ruleStatuses.add(rs);
        }
        result.put("rules", ruleStatuses);
        return result;
    }

    /** 评估日志分页查询：解释样本不足、迟到重算与规则失效 */
    public PageResult<Map<String, Object>> listEvaluations(Long ruleId, Long equipmentId, Long templateItemId,
                                                           String outcome, int page, int size) {
        Specification<TrendEvaluation> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (ruleId != null) ps.add(cb.equal(root.get("ruleId"), ruleId));
            if (equipmentId != null) ps.add(cb.equal(root.get("equipmentId"), equipmentId));
            if (templateItemId != null) ps.add(cb.equal(root.get("templateItemId"), templateItemId));
            if (outcome != null && !outcome.isBlank()) ps.add(cb.equal(root.get("outcome"), outcome));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        PageRequest pr = PageRequest.of(Math.max(0, page), clampSize(size),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<TrendEvaluation> result = evaluationRepo.findAll(spec, pr);
        Map<Long, TrendRule> ruleCache = new HashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TrendEvaluation eval : result.getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", eval.getId());
            row.put("ruleId", eval.getRuleId());
            row.put("ruleVersion", eval.getRuleVersion());
            row.put("equipmentId", eval.getEquipmentId());
            row.put("templateItemId", eval.getTemplateItemId());
            row.put("windowStart", eval.getWindowStart());
            row.put("windowEnd", eval.getWindowEnd());
            row.put("outcome", eval.getOutcome());
            row.put("sampleCount", eval.getSampleCount());
            row.put("missingCount", eval.getMissingCount());
            row.put("duplicateCount", eval.getDuplicateCount());
            row.put("slope", eval.getSlope());
            row.put("amplitude", eval.getAmplitude());
            row.put("nearBoundCount", eval.getNearBoundCount());
            row.put("triggeredMetrics", eval.getTriggeredMetrics());
            row.put("detail", eval.getDetail());
            row.put("lateRecompute", eval.getLateRecompute());
            row.put("alertId", eval.getAlertId());
            row.put("createdAt", eval.getCreatedAt());
            TrendRule rule = ruleCache.computeIfAbsent(eval.getRuleId(),
                    id -> ruleRepo.findById(id).orElse(null));
            if (rule == null) {
                row.put("ruleCurrentVersion", null);
                row.put("ruleSuperseded", true);
                row.put("ruleExplanation", "规则已删除，本评估为历史版本 " + eval.getRuleVersion() + " 的结论");
            } else {
                row.put("ruleCurrentVersion", rule.getVersion());
                row.put("ruleSuperseded", rule.getVersion() > eval.getRuleVersion());
                row.put("ruleExplanation", rule.getVersion() > eval.getRuleVersion()
                        ? "规则已更新至版本 " + rule.getVersion() + "，本评估基于版本 " + eval.getRuleVersion()
                        : (Boolean.TRUE.equals(rule.getEnabled()) ? "" : "规则已停用，本评估为停用前的结论"));
            }
            rows.add(row);
        }
        return new PageResult<>(rows, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    private AlertView toView(TrendAlert alert, Map<Long, TrendRule> ruleCache) {
        TrendRule rule = ruleCache.computeIfAbsent(alert.getRuleId(),
                id -> ruleRepo.findById(id).orElse(null));
        return toView(alert, rule);
    }

    private AlertView toView(TrendAlert alert, TrendRule rule) {
        String effective = alert.getStatus();
        if ("ignored".equals(alert.getStatus()) && alert.getIgnoreUntil() != null
                && LocalDateTime.now().isAfter(alert.getIgnoreUntil())) {
            effective = "open";
        }
        boolean superseded = rule == null || rule.getVersion() > alert.getRuleVersion();
        return new AlertView(alert, effective,
                rule != null ? rule.getVersion() : null,
                rule != null ? rule.getEnabled() : null,
                superseded);
    }

    private void logAction(Long alertId, String action, String operator, String reason, String detail) {
        TrendAlertAction log = new TrendAlertAction();
        log.setAlertId(alertId);
        log.setAction(action);
        log.setOperator(operator);
        log.setReason(reason);
        log.setDetail(detail == null ? "" : detail);
        actionRepo.save(log);
    }

    private static void requireOperatorAndReason(String operator, String reason) {
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("操作者必填");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("处置理由必填");
    }

    private static int clampSize(int size) {
        if (size <= 0) return 20;
        return Math.min(size, 200);
    }

    private static String fmtTime(LocalDateTime t) {
        return t == null ? "" : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }
}
