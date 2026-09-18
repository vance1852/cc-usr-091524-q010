package com.admin.equipment.web.inspection;

import com.admin.equipment.model.WorkOrder;
import com.admin.equipment.model.inspection.TrendAlert;
import com.admin.equipment.model.inspection.TrendRule;
import com.admin.equipment.service.inspection.TrendAlertService;
import com.admin.equipment.service.inspection.TrendRuleService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 趋势预警：规则发布与版本管理、预警查询与处置、数值序列与评估日志查询。
 */
@RestController
@RequestMapping("/api/inspection/trend")
public class InspectionTrendController {

    private final TrendRuleService ruleService;
    private final TrendAlertService alertService;

    public InspectionTrendController(TrendRuleService ruleService, TrendAlertService alertService) {
        this.ruleService = ruleService;
        this.alertService = alertService;
    }

    public record RuleRequest(String name, String equipmentType, Long templateItemId,
                              Integer windowHours, Integer minSamples,
                              Double slopeThreshold, Double amplitudeThreshold,
                              Double nearBoundMargin, Integer nearBoundLimit,
                              String level, Integer cooldownHours, String createdBy) {}

    public record RuleStatusRequest(Boolean enabled) {}

    public record AckRequest(String operator, String reason) {}

    public record IgnoreRequest(String operator, String reason, Integer ignoreHours) {}

    public record WorkOrderRequest(String operator, String reason, String type,
                                   String priority, String assignee) {}

    // ---------- 规则 ----------

    @GetMapping("/rules")
    public ResponseEntity<?> listRules(@RequestParam(required = false) String equipmentType,
                                       @RequestParam(required = false) Boolean enabled) {
        return ResponseEntity.ok(ruleService.list(equipmentType, enabled));
    }

    @GetMapping("/rules/{id}")
    public ResponseEntity<?> getRule(@PathVariable Long id) {
        return ruleService.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "规则不存在")));
    }

    @PostMapping("/rules")
    public ResponseEntity<?> createRule(@RequestBody RuleRequest req) {
        try {
            TrendRule rule = ruleService.create(toSpec(req), req.createdBy());
            return ResponseEntity.status(HttpStatus.CREATED).body(rule);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PutMapping("/rules/{id}")
    public ResponseEntity<?> updateRule(@PathVariable Long id, @RequestBody RuleRequest req) {
        try {
            TrendRule rule = ruleService.update(id, toSpec(req));
            return ResponseEntity.ok(rule);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/rules/{id}/status")
    public ResponseEntity<?> setRuleStatus(@PathVariable Long id, @RequestBody RuleStatusRequest req) {
        try {
            if (req.enabled() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "enabled 必填"));
            }
            return ResponseEntity.ok(ruleService.setEnabled(id, req.enabled()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    // ---------- 预警 ----------

    @GetMapping("/alerts")
    public ResponseEntity<?> listAlerts(@RequestParam(required = false) String status,
                                        @RequestParam(required = false) String level,
                                        @RequestParam(required = false) Long equipmentId,
                                        @RequestParam(required = false) Long ruleId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(alertService.listAlerts(status, level, equipmentId, ruleId, page, size));
    }

    @GetMapping("/alerts/{id}")
    public ResponseEntity<?> getAlert(@PathVariable Long id) {
        return alertService.getAlertDetail(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "预警不存在")));
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ResponseEntity<?> acknowledge(@PathVariable Long id, @RequestBody AckRequest req) {
        try {
            TrendAlert alert = alertService.acknowledge(id, req.operator(), req.reason());
            return ResponseEntity.ok(alert);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/alerts/{id}/ignore")
    public ResponseEntity<?> ignore(@PathVariable Long id, @RequestBody IgnoreRequest req) {
        try {
            TrendAlert alert = alertService.ignore(id, req.operator(), req.reason(), req.ignoreHours());
            return ResponseEntity.ok(alert);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/alerts/{id}/work-order")
    public ResponseEntity<?> convertToWorkOrder(@PathVariable Long id, @RequestBody WorkOrderRequest req) {
        try {
            WorkOrder wo = alertService.convertToWorkOrder(id, req.operator(), req.reason(),
                    req.type(), req.priority(), req.assignee());
            return ResponseEntity.status(HttpStatus.CREATED).body(wo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    // ---------- 序列与评估日志 ----------

    @GetMapping("/series")
    public ResponseEntity<?> series(@RequestParam Long equipmentId,
                                    @RequestParam Long templateItemId,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size,
                                    @RequestParam(defaultValue = "asc") String order) {
        try {
            return ResponseEntity.ok(alertService.getSeries(equipmentId, templateItemId,
                    start, end, page, size, order));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @GetMapping("/evaluations")
    public ResponseEntity<?> evaluations(@RequestParam(required = false) Long ruleId,
                                         @RequestParam(required = false) Long equipmentId,
                                         @RequestParam(required = false) Long templateItemId,
                                         @RequestParam(required = false) String outcome,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(alertService.listEvaluations(ruleId, equipmentId,
                templateItemId, outcome, page, size));
    }

    private static TrendRuleService.RuleSpec toSpec(RuleRequest req) {
        return new TrendRuleService.RuleSpec(req.name(), req.equipmentType(), req.templateItemId(),
                req.windowHours(), req.minSamples(), req.slopeThreshold(), req.amplitudeThreshold(),
                req.nearBoundMargin(), req.nearBoundLimit(), req.level(), req.cooldownHours());
    }
}
