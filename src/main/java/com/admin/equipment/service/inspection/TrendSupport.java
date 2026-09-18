package com.admin.equipment.service.inspection;

import com.admin.equipment.model.inspection.TrendAlert;
import com.admin.equipment.model.inspection.TrendRule;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 趋势预警共享的常量与小工具。 */
final class TrendSupport {

    private TrendSupport() {}

    static final Set<String> LEVELS = Set.of("low", "medium", "high", "urgent");

    static String validLevel(String s) {
        return s != null && LEVELS.contains(s) ? s : "medium";
    }

    static int levelRank(String level) {
        return switch (level == null ? "medium" : level) {
            case "low" -> 1;
            case "high" -> 3;
            case "urgent" -> 4;
            default -> 2;
        };
    }

    static String maxLevel(String a, String b) {
        return levelRank(a) >= levelRank(b) ? validLevel(a) : validLevel(b);
    }

    /** 规则的设备类型匹配：规则类型为空表示适用于全部设备类型。 */
    static boolean typeMatches(String ruleType, String equipmentType) {
        return ruleType == null || ruleType.isBlank()
                || ruleType.equals(equipmentType == null ? "" : equipmentType);
    }

    static List<Long> parseIds(String str) {
        List<Long> result = new ArrayList<>();
        if (str == null || str.isBlank()) return result;
        for (String p : str.split(",")) {
            try {
                long id = Long.parseLong(p.trim());
                if (id > 0) result.add(id);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    /** 同一设备、项目、规则版本与窗口只产生一个预警的去重键。 */
    static String dedupeKey(Long equipmentId, Long templateItemId, Long ruleId, Integer ruleVersion, String windowKey) {
        return equipmentId + ":" + templateItemId + ":" + ruleId + ":v" + ruleVersion + ":" + windowKey;
    }

    /** 事件的有效合并期限：冷却期与忽略期取较晚者，期限内新证据并入既有事件。 */
    static boolean withinHorizon(TrendAlert alert, LocalDateTime now) {
        LocalDateTime horizon = alert.getCooldownUntil();
        if (alert.getIgnoreUntil() != null && (horizon == null || alert.getIgnoreUntil().isAfter(horizon))) {
            horizon = alert.getIgnoreUntil();
        }
        return horizon != null && !now.isAfter(horizon);
    }

    /** 规则参数快照（预警上留存，保证规则变更后历史预警仍可解释）。 */
    static Map<String, Object> paramsMap(TrendRule rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("windowSize", rule.getWindowSize());
        m.put("minSamples", rule.getMinSamples());
        m.put("slopeThreshold", rule.getSlopeThreshold());
        m.put("fluctuationAmplitude", rule.getFluctuationAmplitude());
        m.put("nearMargin", rule.getNearMargin());
        m.put("nearBoundaryCount", rule.getNearBoundaryCount());
        m.put("normalMin", rule.getNormalMin());
        m.put("normalMax", rule.getNormalMax());
        m.put("level", rule.getLevel());
        m.put("cooldownHours", rule.getCooldownHours());
        return m;
    }
}
