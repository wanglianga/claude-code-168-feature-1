package com.ruralmarket.platform.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收费/缴费记录：摊位费、卫生费、电费、保证金、扣罚款。
 */
@Data
@Entity
@Table(name = "payments")
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "event_id", nullable = false)
    private MarketEvent event;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "result_id", nullable = false)
    private LotteryResult result;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "vendor_id", nullable = false)
    private User vendor;

    /** STALL_FEE 摊位费 / SANITATION 卫生费 / POWER 电费 / DEPOSIT 保证金 / PENALTY 扣罚款 /
     *  FEE_DIFF 雨天调位补收差额 / FEE_REFUND 雨天调位应退差额 */
    @Column(nullable = false, length = 20)
    private String itemType;

    @Column(nullable = false, length = 50)
    private String itemName;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** UNPAID 未缴 / PAID 已缴 / WAIVED 已减免 / DEDUCTED 已扣罚 / REFUNDED 已退还 */
    @Column(nullable = false, length = 20)
    private String status = "UNPAID";

    private LocalDateTime paidAt;

    @Column(length = 500)
    private String remark;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
