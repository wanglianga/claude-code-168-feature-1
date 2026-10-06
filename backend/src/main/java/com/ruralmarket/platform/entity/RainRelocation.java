package com.ruralmarket.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 雨天临时调位工单：暴雨积水时把低洼摊位调至硬化路面的全流程记录。
 * 流程：管理员发起(PENDING) → 摊主确认(CONFIRMED，重新生成二维码并核算收费差额)
 *       → 摊主拒绝(REJECTED) → 管理员标记放弃经营(ABANDONED)或保留原位风险自担(KEEP_ORIGINAL)。
 * 调位完成后新旧摊位照片、摊主确认与收费差额对财务公开。
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

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "old_stall_id", nullable = false)
    private Stall oldStall;

    @Column(nullable = false, length = 20)
    private String oldStallNo;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "new_stall_id", nullable = false)
    private Stall newStall;

    @Column(nullable = false, length = 20)
    private String newStallNo;

    /** 积水情况与调位原因（向摊主公开） */
    @Column(nullable = false, length = 1000)
    private String reason;

    /** 旧摊位（积水）照片，逗号分隔 */
    @Column(length = 1000)
    private String oldStallPhoto;

    /** 新摊位（硬化路面）照片，逗号分隔 */
    @Column(length = 1000)
    private String newStallPhoto;

    /** 新摊位距消防通道距离（米），发起时快照 */
    private Double fireLaneDistance;

    /** 邻摊影响说明，发起时快照 */
    @Column(length = 1000)
    private String neighborImpact;

    /** 原区域摊位费 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal oldFee = BigDecimal.ZERO;

    /** 新区域摊位费 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal newFee = BigDecimal.ZERO;

    /** 收费差额（新-旧；正=摊主补缴，负=应退摊主） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal feeDiff = BigDecimal.ZERO;

    /** 摊主确认后重新生成的入场核验二维码令牌 */
    @Column(length = 64)
    private String newQrToken;

    /** PENDING 待摊主确认 / CONFIRMED 已确认调位 / REJECTED 摊主已拒绝 /
     *  ABANDONED 放弃经营 / KEEP_ORIGINAL 保留原位风险自担 */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    /** 摊主确认/拒绝时的备注 */
    @Column(length = 500)
    private String vendorNote;

    private LocalDateTime confirmedAt;

    /** 管理员对拒绝单的处理说明（放弃经营/保留原位） */
    @Column(length = 500)
    private String resolveNote;

    private LocalDateTime resolvedAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "operator_id", nullable = false)
    private User operator;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
