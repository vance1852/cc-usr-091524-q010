package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 预警处置留痕：确认、忽略、转工单、忽略到期自动重开，均记录操作者与理由。 */
@Entity
@Table(name = "inspection_trend_alert_actions",
        indexes = @Index(name = "idx_trend_action_alert", columnList = "alert_id"))
public class TrendAlertAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    /** 动作：acknowledge 确认 / ignore 忽略 / work_order 转工单 / reopen 忽略到期重开 */
    @Column(name = "action", length = 16)
    private String action;

    @Column(name = "operator", length = 64)
    private String operator = "";

    @Column(name = "reason", length = 512)
    private String reason = "";

    @Column(name = "detail", length = 512)
    private String detail = "";

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
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
