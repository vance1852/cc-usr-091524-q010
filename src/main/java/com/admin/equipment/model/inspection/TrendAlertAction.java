package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 趋势预警处置记录：确认、忽略、转维修工单、忽略到期自动重开。
 * 任何处置都保留操作者与理由。
 */
@Entity
@Table(name = "trend_alert_actions", indexes = {
        @Index(name = "idx_action_alert", columnList = "alert_id")
})
public class TrendAlertAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    /** 动作：acknowledge 确认 / ignore 忽略 / convert_work_order 转工单 / auto_reopen 忽略到期重开 */
    @Column(length = 32)
    private String action = "";

    @Column(name = "operator", length = 64)
    private String operator = "";

    @Column(length = 512)
    private String reason = "";

    @Column(name = "from_status", length = 16)
    private String fromStatus = "";

    @Column(name = "to_status", length = 16)
    private String toStatus = "";

    @Column(name = "work_order_id")
    private Long workOrderId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long alertId) { this.alertId = alertId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String fromStatus) { this.fromStatus = fromStatus; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String toStatus) { this.toStatus = toStatus; }
    public Long getWorkOrderId() { return workOrderId; }
    public void setWorkOrderId(Long workOrderId) { this.workOrderId = workOrderId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
