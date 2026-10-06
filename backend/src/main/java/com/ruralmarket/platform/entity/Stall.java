package com.ruralmarket.platform.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "stalls")
public class Stall {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "zone_id", nullable = false)
    private StallZone zone;

    /** 摊位号，如 A-01 */
    @Column(nullable = false, unique = true, length = 20)
    private String stallNo;

    /** 是否有电源 */
    @Column(nullable = false)
    private Boolean hasPower = false;

    /** 可停放车辆最大长度（米），null 表示不限 */
    private Double maxVehicleLength;

    /** 是否紧邻消防通道（此类摊位禁止堆物占道，巡查重点） */
    @Column(nullable = false)
    private Boolean nearFireLane = false;

    /** 是否硬化路面（硬化路面暴雨不积水；雨天临时调位的目标摊位必须为硬化路面）。
     *  列可空以便旧库平滑升级，启动时由 DataSeeder 回填为 true（既有摊位默认为硬化路面） */
    private Boolean hardened = true;

    /** 距消防通道的水平距离（米），调位时用于消防安全评估展示；为空时以 nearFireLane 估算 */
    private Double fireLaneDistance;

    /** 市场平面坐标 X（米），用于计算摊位间距、评估邻摊影响 */
    private Double posX;

    /** 市场平面坐标 Y（米） */
    private Double posY;

    /** 是否应急备用位（硬化路面预留，不参与常规抽签分配，仅用于临时/雨天调位）。
     *  列可空以便旧库平滑升级，启动时回填为 false */
    private Boolean reservedEmergency = false;

    /** 是否启用（消防通道本身等不用于分配的位置设为 false） */
    @Column(nullable = false)
    private Boolean active = true;
}
