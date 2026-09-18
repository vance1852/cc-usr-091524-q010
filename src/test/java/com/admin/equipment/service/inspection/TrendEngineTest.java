package com.admin.equipment.service.inspection;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 趋势计算引擎纯函数测试：相同输入必然得到相同结论。 */
class TrendEngineTest {

    private static TrendEngine.Sample s(long id, String time, double v) {
        return new TrendEngine.Sample(id, LocalDateTime.parse(time), v);
    }

    @Test
    void risingSlopeTriggers() {
        List<TrendEngine.Sample> w = List.of(
                s(1, "2026-09-01T08:00", 60), s(2, "2026-09-01T09:00", 61), s(3, "2026-09-01T10:00", 62));
        TrendEngine.WindowResult r = TrendEngine.evaluateWindow(w, 3, 0.5, null, null, null, null, null);
        assertTrue(r.evaluated());
        assertTrue(r.triggered());
        assertEquals(1.0, r.slope(), 1e-9);
        assertEquals(List.of("slope"), r.triggeredMetrics());
    }

    @Test
    void fallingSlopeNeedsNegativeThreshold() {
        List<TrendEngine.Sample> w = List.of(
                s(1, "2026-09-01T08:00", 62), s(2, "2026-09-01T09:00", 61), s(3, "2026-09-01T10:00", 60));
        TrendEngine.WindowResult fall = TrendEngine.evaluateWindow(w, 3, -0.5, null, null, null, null, null);
        assertTrue(fall.triggered());
        TrendEngine.WindowResult rise = TrendEngine.evaluateWindow(w, 3, 0.5, null, null, null, null, null);
        assertFalse(rise.triggered());
    }

    @Test
    void insufficientSamplesSkipsEvaluation() {
        List<TrendEngine.Sample> w = List.of(s(1, "2026-09-01T08:00", 60), s(2, "2026-09-01T09:00", 61));
        TrendEngine.WindowResult r = TrendEngine.evaluateWindow(w, 3, 0.5, null, null, null, null, null);
        assertFalse(r.evaluated());
        assertFalse(r.triggered());
        assertTrue(r.detail().contains("样本不足"));
    }

    @Test
    void fluctuationTriggers() {
        List<TrendEngine.Sample> w = List.of(
                s(1, "2026-09-01T08:00", 50), s(2, "2026-09-01T09:00", 80), s(3, "2026-09-01T10:00", 50));
        TrendEngine.WindowResult r = TrendEngine.evaluateWindow(w, 3, null, 20.0, null, null, null, null);
        assertTrue(r.triggered());
        assertEquals(List.of("fluctuation"), r.triggeredMetrics());
        assertEquals(30.0, r.fluctuation(), 1e-9);
    }

    @Test
    void nearBoundaryStreakTriggers() {
        List<TrendEngine.Sample> w = List.of(
                s(1, "2026-09-01T08:00", 70), s(2, "2026-09-01T09:00", 82),
                s(3, "2026-09-01T10:00", 84), s(4, "2026-09-01T11:00", 86));
        TrendEngine.WindowResult r = TrendEngine.evaluateWindow(w, 3, null, null, 5.0, 3, 20.0, 85.0);
        assertTrue(r.triggered());
        assertEquals(3, r.nearBoundaryStreak());
        assertEquals(List.of("near_boundary"), r.triggeredMetrics());
    }

    @Test
    void sameTimestampSlopeNotComputable() {
        List<TrendEngine.Sample> w = List.of(
                s(1, "2026-09-01T08:00", 60), s(2, "2026-09-01T08:00", 61), s(3, "2026-09-01T08:00", 62));
        TrendEngine.WindowResult r = TrendEngine.evaluateWindow(w, 3, 0.5, null, null, null, null, null);
        assertTrue(r.evaluated());
        assertNull(r.slope());
        assertFalse(r.triggered());
        assertTrue(r.detail().contains("时间跨度为 0"));
    }

    @Test
    void missingBoundsDisableNearBoundary() {
        List<TrendEngine.Sample> w = List.of(
                s(1, "2026-09-01T08:00", 999), s(2, "2026-09-01T09:00", 999), s(3, "2026-09-01T10:00", 999));
        TrendEngine.WindowResult r = TrendEngine.evaluateWindow(w, 3, null, null, 5.0, 2, null, null);
        assertFalse(r.triggered());
        assertEquals(0, r.nearBoundaryStreak());
    }
}
