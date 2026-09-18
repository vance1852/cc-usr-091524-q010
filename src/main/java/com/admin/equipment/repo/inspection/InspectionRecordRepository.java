package com.admin.equipment.repo.inspection;

import com.admin.equipment.model.inspection.InspectionRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface InspectionRecordRepository extends JpaRepository<InspectionRecord, Long> {
    List<InspectionRecord> findByTaskPointIdOrderByIdAsc(Long taskPointId);
    List<InspectionRecord> findByTaskIdOrderByIdAsc(Long taskId);
    List<InspectionRecord> findByPointIdOrderByRecordedAtDesc(Long pointId);
    List<InspectionRecord> findByTemplateItemIdOrderByRecordedAtDesc(Long templateItemId);
    long countByTaskPointIdAndIsQualifiedFalse(Long taskPointId);
    long countByTaskPointIdAndIsAbnormalTrue(Long taskPointId);

    /**
     * 趋势评估用序列：某设备在某模板项目下的全部有效样本。
     * 有效 = 有数值且采样时间非空；按 (采样时间, ID) 升序，保证同刻重复读数顺序确定。
     * 设备归属按任务巡检点快照的 equipmentIds 精确匹配（逗号包裹防误配）。
     */
    @Query("SELECT r FROM InspectionRecord r, InspectionTaskPoint tp "
            + "WHERE r.taskPointId = tp.id AND r.templateItemId = :itemId "
            + "AND r.checkNumeric IS NOT NULL AND r.recordedAt IS NOT NULL "
            + "AND CONCAT(',', tp.equipmentIds, ',') LIKE CONCAT('%,', :equipmentId, ',%') "
            + "ORDER BY r.recordedAt ASC, r.id ASC")
    List<InspectionRecord> findTrendSeries(@Param("equipmentId") String equipmentId,
                                           @Param("itemId") Long itemId);

    @Query("SELECT COUNT(r) FROM InspectionRecord r, InspectionTaskPoint tp "
            + "WHERE r.taskPointId = tp.id AND r.templateItemId = :itemId "
            + "AND r.checkNumeric IS NOT NULL AND r.recordedAt IS NOT NULL "
            + "AND CONCAT(',', tp.equipmentIds, ',') LIKE CONCAT('%,', :equipmentId, ',%')")
    long countTrendSeries(@Param("equipmentId") String equipmentId,
                          @Param("itemId") Long itemId);

    /**
     * 序列查询接口用分页：包含缺失值记录（usable 标记由调用方判断），
     * 排序固定为 (采样时间, ID)，保证分页结果可复现。
     */
    @Query(value = "SELECT r FROM InspectionRecord r, InspectionTaskPoint tp "
            + "WHERE r.taskPointId = tp.id AND r.templateItemId = :itemId "
            + "AND CONCAT(',', tp.equipmentIds, ',') LIKE CONCAT('%,', :equipmentId, ',%') "
            + "AND (:fromAt IS NULL OR r.recordedAt >= :fromAt) "
            + "AND (:toAt IS NULL OR r.recordedAt <= :toAt) "
            + "ORDER BY r.recordedAt ASC, r.id ASC",
            countQuery = "SELECT COUNT(r) FROM InspectionRecord r, InspectionTaskPoint tp "
            + "WHERE r.taskPointId = tp.id AND r.templateItemId = :itemId "
            + "AND CONCAT(',', tp.equipmentIds, ',') LIKE CONCAT('%,', :equipmentId, ',%') "
            + "AND (:fromAt IS NULL OR r.recordedAt >= :fromAt) "
            + "AND (:toAt IS NULL OR r.recordedAt <= :toAt)")
    Page<InspectionRecord> pageTrendSeries(@Param("equipmentId") String equipmentId,
                                           @Param("itemId") Long itemId,
                                           @Param("fromAt") LocalDateTime fromAt,
                                           @Param("toAt") LocalDateTime toAt,
                                           Pageable pageable);
}
