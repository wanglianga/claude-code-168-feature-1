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

    /** 距消防通道距离（米），用于雨天调位时评估替代位置 */
    private Double fireLaneDistance;

    /** 是否低洼易积水（暴雨时需优先调出） */
    @Column(nullable = false)
    private Boolean lowLying = false;

    /** 是否硬化路面（雨天调位的可替代位置须为硬化路面） */
    @Column(nullable = false)
    private Boolean hardened = false;

    /** 是否启用（消防通道本身等不用于分配的位置设为 false） */
    @Column(nullable = false)
    private Boolean active = true;
}
