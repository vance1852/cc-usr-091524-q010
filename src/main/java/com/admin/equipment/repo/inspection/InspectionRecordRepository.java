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

    /** 滚动窗口样本：(start, end]，按采样时间+ID确定性排序 */
    @Query("select r from InspectionRecord r where r.templateItemId = :itemId and r.pointId in :pointIds "
            + "and r.recordedAt > :start and r.recordedAt <= :end order by r.recordedAt asc, r.id asc")
    List<InspectionRecord> findWindowSamples(@Param("itemId") Long itemId,
                                             @Param("pointIds") List<Long> pointIds,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);

    /** 闭区间样本，用于迟到数据定位受影响窗口的锚点 */
    @Query("select r from InspectionRecord r where r.templateItemId = :itemId and r.pointId in :pointIds "
            + "and r.recordedAt between :start and :end order by r.recordedAt asc, r.id asc")
    List<InspectionRecord> findRangeSamples(@Param("itemId") Long itemId,
                                            @Param("pointIds") List<Long> pointIds,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    InspectionRecord findFirstByTemplateItemIdAndPointIdInOrderByRecordedAtDescIdDesc(Long itemId, List<Long> pointIds);

    Page<InspectionRecord> findByTemplateItemIdAndPointIdInAndRecordedAtBetween(
            Long itemId, List<Long> pointIds, LocalDateTime start, LocalDateTime end, Pageable pageable);

    /** 序列汇总：总记录数、非空数值数、最小/最大值、最早/最晚采样时间 */
    @Query("select count(r), count(r.checkNumeric), min(r.checkNumeric), max(r.checkNumeric), "
            + "min(r.recordedAt), max(r.recordedAt) from InspectionRecord r "
            + "where r.templateItemId = :itemId and r.pointId in :pointIds and r.recordedAt between :start and :end")
    Object[] summarizeSeries(@Param("itemId") Long itemId,
                             @Param("pointIds") List<Long> pointIds,
                             @Param("start") LocalDateTime start,
                             @Param("end") LocalDateTime end);
}
