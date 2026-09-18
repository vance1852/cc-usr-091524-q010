package com.admin.equipment.web.inspection;

import com.admin.equipment.model.inspection.TrendAlert;
import com.admin.equipment.service.inspection.TrendAlertService;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/inspection/trend")
public class TrendAlertController {

    private static final int MAX_PAGE_SIZE = 500;

    private final TrendAlertService service;

    public TrendAlertController(TrendAlertService service) {
        this.service = service;
    }

    public record DisposeRequest(String operator, String reason) {}
    public record IgnoreRequest(String operator, String reason, Integer ignoreHours) {}
    public record ConvertRequest(String operator, String reason, String workOrderType,
                                 String priority, String assignee) {}

    @GetMapping("/alerts")
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) Long equipmentId,
                                  @RequestParam(required = false) Long ruleId,
                                  @RequestParam(required = false) String level,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        ResponseEntity<?> bad = validatePage(page, size);
        if (bad != null) return bad;
        Page<TrendAlert> p = service.search(status, equipmentId, ruleId, level, page, size);
        return ResponseEntity.ok(Map.of(
                "content", p.getContent(),
                "page", p.getNumber(),
                "size", p.getSize(),
                "totalElements", p.getTotalElements(),
                "totalPages", p.getTotalPages()));
    }

    /** 预警详情：触发证据、处置记录、评估留痕、窗口时间序列与规则现状解释 */
    @GetMapping("/alerts/{id}")
    public ResponseEntity<?> detail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(service.getDetail(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", e.getMessage()));
        }
    }

    /**
     * 时间序列 + 规则解释：分页返回样本（缺失值标记 usable=false），
     * 并解释样本不足、迟到重算与规则失效。
     */
    @GetMapping("/series")
    public ResponseEntity<?> series(@RequestParam Long equipmentId,
                                    @RequestParam Long templateItemId,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        ResponseEntity<?> bad = validatePage(page, size);
        if (bad != null) return bad;
        try {
            return ResponseEntity.ok(service.getSeries(equipmentId, templateItemId, from, to, page, size));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        }
    }

    @PostMapping("/alerts/{id}/acknowledge")
    public ResponseEntity<?> acknowledge(@PathVariable Long id, @RequestBody DisposeRequest req) {
        try {
            return ResponseEntity.ok(service.acknowledge(id, req.operator(), req.reason()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("detail", "预警已被并发修改，请刷新后重试"));
        }
    }

    /** 忽略：到期后若出现新的触发证据，事件自动重开 */
    @PostMapping("/alerts/{id}/ignore")
    public ResponseEntity<?> ignore(@PathVariable Long id, @RequestBody IgnoreRequest req) {
        try {
            return ResponseEntity.ok(service.ignore(id, req.operator(), req.reason(), req.ignoreHours()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("detail", "预警已被并发修改，请刷新后重试"));
        }
    }

    @PostMapping("/alerts/{id}/convert")
    public ResponseEntity<?> convert(@PathVariable Long id, @RequestBody ConvertRequest req) {
        try {
            TrendAlertService.ConvertResult r = service.convertToWorkOrder(
                    id, req.operator(), req.reason(), req.workOrderType(), req.priority(), req.assignee());
            return ResponseEntity.ok(Map.of("alert", r.alert(), "workOrder", r.workOrder()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("detail", e.getMessage()));
        } catch (ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("detail", "预警已被并发修改，请刷新后重试"));
        }
    }

    private ResponseEntity<?> validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            return ResponseEntity.unprocessableEntity()
                    .body(Map.of("detail", "分页参数不合法：page >= 0，1 <= size <= " + MAX_PAGE_SIZE));
        }
        return null;
    }
}
