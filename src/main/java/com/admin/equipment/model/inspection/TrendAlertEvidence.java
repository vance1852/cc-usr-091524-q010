package com.admin.equipment.model.inspection;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 趋势预警触发证据：每个触发窗口、每个指标一条，只增不改。
 * 迟到重算产生的证据带 lateRecompute 标记；结论一致的重算不重复追加（幂等）。
 */
@Entity
@Table(name = "trend_alert_evidence", indexes = {
        @Index(name = "idx_evidence_alert", columnList = "alert_id")
})
public class TrendAlertEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    /** 产生该证据的评估日志ID */
    @Column(name = "evaluation_id")
    private Long evaluationId;

    @Column(name = "window_key", length = 64)
    private String windowKey = "";

    /** 指标：slope / fluctuation / near_boundary */
    @Column(length = 32)
    private String metric = "";

    @Column(name = "computed_value")
    private Double computedValue;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(name = "sample_count")
    private Integer sampleCount;

    @Column(name = "window_start_at")
    private LocalDateTime windowStartAt;

    @Column(name = "window_end_at")
    private LocalDateTime windowEndAt;

    @Column(length = 512)
    private String detail = "";

    @Column(name = "late_recompute")
    private Boolean lateRecompute = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAlertId() { return alertId; }
    public void setAlertId(Long alertId) { this.alertId = alertId; }
    public Long getEvaluationId() { return evaluationId; }
    public void setEvaluationId(Long evaluationId) { this.evaluationId = evaluationId; }
    public String getWindowKey() { return windowKey; }
    public void setWindowKey(String windowKey) { this.windowKey = windowKey; }
    public String getMetric() { return metric; }
    public void setMetric(String metric) { this.metric = metric; }
    public Double getComputedValue() { return computedValue; }
    public void setComputedValue(Double computedValue) { this.computedValue = computedValue; }
    public Double getThresholdValue() { return thresholdValue; }
    public void setThresholdValue(Double thresholdValue) { this.thresholdValue = thresholdValue; }
    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }
    public LocalDateTime getWindowStartAt() { return windowStartAt; }
    public void setWindowStartAt(LocalDateTime windowStartAt) { this.windowStartAt = windowStartAt; }
    public LocalDateTime getWindowEndAt() { return windowEndAt; }
    public void setWindowEndAt(LocalDateTime windowEndAt) { this.windowEndAt = windowEndAt; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Boolean getLateRecompute() { return lateRecompute; }
    public void setLateRecompute(Boolean lateRecompute) { this.lateRecompute = lateRecompute; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
