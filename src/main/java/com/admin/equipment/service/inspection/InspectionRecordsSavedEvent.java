package com.admin.equipment.service.inspection;

import java.util.List;

/** 巡检记录写入并提交后发布，携带本次保存的记录ID，用于触发趋势规则异步评估。 */
public record InspectionRecordsSavedEvent(List<Long> recordIds) {}
