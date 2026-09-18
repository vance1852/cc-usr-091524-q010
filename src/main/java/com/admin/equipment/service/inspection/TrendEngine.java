package com.admin.equipment.service.inspection;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 趋势预警计算引擎：纯函数、无状态。
 * 相同的输入样本序列与规则参数必然得到相同的结论，与评估发生的时刻、并发度无关。
 * 窗口为"最近 N 个有效样本"的滚动样本窗口，样本须按 (采样时间, 记录ID) 升序传入。
 */
public final class TrendEngine {

    private TrendEngine() {}

    /** 趋势样本：一条数值型巡检记录的有效读数。 */
    public record Sample(long recordId, LocalDateTime at, double value) {}

    /** 单项被触发的趋势指标及判定依据（作为触发证据落库）。 */
    public record MetricHit(String metric, Double computedValue, Double thresholdValue, String detail) {}

    public record WindowResult(boolean evaluated, int sampleCount,
                               LocalDateTime windowStartAt, LocalDateTime windowEndAt,
                               Double slope, Double fluctuation, int nearBoundaryStreak,
                               List<MetricHit> metricHits, String detail) {
        public boolean triggered() { return evaluated && !metricHits.isEmpty(); }
        public List<String> triggeredMetrics() { return metricHits.stream().map(MetricHit::metric).toList(); }
    }

    /**
     * 评估一个滚动窗口。
     *
     * @param window               窗口内样本（按 (采样时间, ID) 升序）
     * @param minSamples           最少样本数，不足时不评估
     * @param slopeThreshold       变化斜率阈值（单位/小时；正数=上升，负数=下降；null=不启用）
     * @param fluctuationAmplitude 波动幅度阈值（max-min；null=不启用）
     * @param nearMargin           接近边界裕度（与正常上下限的距离；null=不启用）
     * @param nearBoundaryCount    连续接近边界次数阈值（null=不启用）
     * @param normalMin/normalMax  规则发布时快照的正常范围
     */
    public static WindowResult evaluateWindow(List<Sample> window, int minSamples,
                                              Double slopeThreshold,
                                              Double fluctuationAmplitude,
                                              Double nearMargin, Integer nearBoundaryCount,
                                              Double normalMin, Double normalMax) {
        int n = window.size();
        LocalDateTime startAt = n == 0 ? null : window.get(0).at();
        LocalDateTime endAt = n == 0 ? null : window.get(n - 1).at();
        if (n < minSamples) {
            return new WindowResult(false, n, startAt, endAt, null, null, 0, List.of(),
                    "样本不足：窗口内仅 " + n + " 个有效样本，少于最少样本数 " + minSamples);
        }

        // 变化斜率：最小二乘回归，x 为相对窗口起点的小时数。
        // 同刻重复读数使窗口时间跨度为 0 时斜率不可计算（slope 保持 null），结论仍然确定。
        Double slope = null;
        double[] xs = new double[n];
        double sumX = 0, sumY = 0;
        LocalDateTime t0 = window.get(0).at();
        for (int i = 0; i < n; i++) {
            xs[i] = Duration.between(t0, window.get(i).at()).toMillis() / 3_600_000.0;
            sumX += xs[i];
            sumY += window.get(i).value();
        }
        double meanX = sumX / n, meanY = sumY / n;
        double sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            sxy += (xs[i] - meanX) * (window.get(i).value() - meanY);
            sxx += (xs[i] - meanX) * (xs[i] - meanX);
        }
        if (sxx > 0) {
            slope = sxy / sxx;
        }

        // 波动幅度：窗口内最大值与最小值之差。
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Sample s : window) {
            min = Math.min(min, s.value());
            max = Math.max(max, s.value());
        }
        double fluctuation = max - min;

        // 连续接近边界：从窗口末尾向前计数（超出正常范围也算接近）。
        int streak = 0;
        if (nearMargin != null && (normalMin != null || normalMax != null)) {
            for (int i = n - 1; i >= 0; i--) {
                double v = window.get(i).value();
                boolean near = (normalMax != null && v >= normalMax - nearMargin)
                        || (normalMin != null && v <= normalMin + nearMargin);
                if (near) {
                    streak++;
                } else {
                    break;
                }
            }
        }

        List<MetricHit> hits = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (slopeThreshold != null && slopeThreshold != 0) {
            if (slope == null) {
                notes.add("窗口内采样时间跨度为 0（同刻重复读数），斜率不可计算");
            } else {
                boolean hit = slopeThreshold > 0 ? slope >= slopeThreshold : slope <= slopeThreshold;
                if (hit) {
                    hits.add(new MetricHit("slope", slope, slopeThreshold,
                            String.format(Locale.ROOT, "变化斜率 %.6f/小时 达到阈值 %.6f", slope, slopeThreshold)));
                }
            }
        }
        if (fluctuationAmplitude != null && fluctuation >= fluctuationAmplitude) {
            hits.add(new MetricHit("fluctuation", fluctuation, fluctuationAmplitude,
                    String.format(Locale.ROOT, "波动幅度 %.6f 达到阈值 %.6f", fluctuation, fluctuationAmplitude)));
        }
        if (nearBoundaryCount != null && nearMargin != null && streak >= nearBoundaryCount) {
            hits.add(new MetricHit("near_boundary", (double) streak, nearBoundaryCount.doubleValue(),
                    "连续 " + streak + " 个样本接近正常边界（裕度 "
                            + String.format(Locale.ROOT, "%.4f", nearMargin)
                            + "，达到阈值 " + nearBoundaryCount + " 次）"));
        }

        String detail;
        if (!hits.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (MetricHit h : hits) {
                parts.add(h.detail());
            }
            detail = String.join("；", parts);
        } else {
            String base = "窗口样本 " + n + " 个，未触发趋势条件（斜率 "
                    + (slope == null ? "不可计算" : String.format(Locale.ROOT, "%.4f/小时", slope))
                    + "，波动 " + String.format(Locale.ROOT, "%.4f", fluctuation)
                    + "，连续近边界 " + streak + " 次）";
            detail = notes.isEmpty() ? base : String.join("；", notes) + "；" + base;
        }
        return new WindowResult(true, n, startAt, endAt, slope, fluctuation, streak, hits, detail);
    }
}
