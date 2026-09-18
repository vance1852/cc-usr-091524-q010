package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.TrendRule;
import com.admin.equipment.service.inspection.TrendRuleService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/trend/rules")
public class TrendRuleController {

    private final TrendRuleService service;

    public TrendRuleController(TrendRuleService service) {
        this.service = service;
    }

    public record RuleRequest(String name, String equipmentType, Long templateItemId,
                              Integer windowSize, Integer minSamples,
                              Double slopeThreshold, Double fluctuationAmplitude,
                              Double nearMargin, Integer nearBoundaryCount,
                              String level, Integer cooldownHours, String createdBy) {}

    public record ReevaluateRequest(Long equipmentId) {}

    @GetMapping
    public List<TrendRule> list(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String equipmentType,
                                @RequestParam(required = false) Long templateItemId) {
        return service.list(status, equipmentType, templateItemId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return service.getById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("detail", "规则不存在")));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody RuleRequest req) {
        try {
            TrendRule r = service.create(toSpec(req), req.createdBy());
            return ResponseEntity.status(HttpStatus.CREATED).body(r);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 全量更新；已发布规则更新后版本递增，只作用于之后的评估 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody RuleRequest req) {
        try {
            return ResponseEntity.ok(service.update(id, toSpec(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.publish(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disable(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.disable(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/{id}/enable")
    public ResponseEntity<?> enable(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.enable(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    /** 手动重算：对指定设备按规则当前版本回放全部历史窗口（幂等，可用于补算历史数据） */
    @PostMapping("/{id}/reevaluate")
    public ResponseEntity<?> reevaluate(@PathVariable Long id, @RequestBody ReevaluateRequest req) {
        try {
            if (req.equipmentId() == null) {
                return ResponseEntity.unprocessableEntity().body(Map.of("detail", "设备ID必填"));
            }
            return ResponseEntity.ok(service.reevaluate(id, req.equipmentId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    private TrendRuleService.RuleSpec toSpec(RuleRequest req) {
        return new TrendRuleService.RuleSpec(req.name(), req.equipmentType(), req.templateItemId(),
                req.windowSize(), req.minSamples(), req.slopeThreshold(), req.fluctuationAmplitude(),
                req.nearMargin(), req.nearBoundaryCount(), req.level(), req.cooldownHours());
    }
}
