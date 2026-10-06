package com.ruralmarket.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 雨天临时调位单：赶集日清晨暴雨导致低洼摊位积水时，管理员把摊位调整到硬化路面的全过程单据。
 *
 * 流程：管理员扫描受影响摊位 → 选定硬化路面候选位（记录消防通道距离、邻摊影响、收费差额）
 *      → 摊主确认 → 重新生成入场二维码并生成差额账单 → 管理员上传新摊位照片完成归档
 *      → 财务对账（新旧摊位照片 + 摊主确认 + 收费差额）。
 * 摊主拒绝时可选择「放弃经营」或「保留原位、风险自担」。
 */
@Data
@Entity
@Table(name = "rain_relocations")
public class RainRelocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "event_id", nullable = false)
    private MarketEvent event;

    /** 受影响的摊位分配单 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "result_id", nullable = false)
    private LotteryResult result;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vendor_id", nullable = false)
    private User vendor;

    /** 原（积水）摊位 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "old_stall_id", nullable = false)
    private Stall oldStall;

    /** 拟调整到的硬化路面摊位 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "new_stall_id", nullable = false)
    private Stall newStall;

    @Column(nullable = false, length = 20)
    private String oldStallNo;

    @Column(nullable = false, length = 20)
    private String newStallNo;

    /**
     * PROPOSED 已发起待摊主确认 / CONFIRMED 摊主已同意(已调位) / COMPLETED 已上传新摊照片归档完成 /
     * ABANDONED 摊主拒绝-放弃经营 / RISK_SELF 摊主拒绝-保留原位风险自担
     */
    @Column(nullable = false, length = 20)
    private String status = "PROPOSED";

    /** 调位原因（必填，向摊主公开，如：清晨暴雨 D 区积水约 15cm） */
    @Column(nullable = false, length = 1000)
    private String reason;

    /** 现场积水量深（厘米，选填） */
    private Integer floodDepthCm;

    /** 原摊位积水照片，逗号分隔 URL */
    @Column(length = 2000)
    private String oldStallPhotos;

    /** 新摊位（硬化路面）照片，逗号分隔 URL，调位完成后由管理员补传 */
    @Column(length = 2000)
    private String newStallPhotos;

    /** 新摊位距消防通道距离（米，发起时快照） */
    @Column(nullable = false)
    private Double fireLaneDistance;

    /** 邻摊影响快照 JSON：[{stallNo, vendorName, category, distanceMeters}] */
    @Column(length = 2000)
    private String neighborImpact;

    /** 原摊位收费合计（摊位费+电费，元） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal oldFee = BigDecimal.ZERO;

    /** 新摊位收费合计（摊位费+电费，元） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal newFee = BigDecimal.ZERO;

    /** 收费差额（新-原；正数补收、负数应退） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal feeDelta = BigDecimal.ZERO;

    /** 生成的差额账单ID（无差额时为空） */
    private Long deltaPaymentId;

    /** 同步生成的调整追溯记录ID */
    private Long adjustmentId;

    /** 摊主答复时间 */
    private LocalDateTime vendorRespondedAt;

    /** 摊主答复备注（同意/拒绝时的说明） */
    @Column(length = 1000)
    private String vendorNote;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "operator_id", nullable = false)
    private User operator;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime completedAt;
}
