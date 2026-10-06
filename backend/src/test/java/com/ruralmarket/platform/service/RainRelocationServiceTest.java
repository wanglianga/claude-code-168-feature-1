package com.ruralmarket.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruralmarket.platform.entity.*;
import com.ruralmarket.platform.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 雨天调位候选位计算：硬化路面过滤、品类区域兼容、占用过滤、
 * 消防通道安全距离预警、5 米邻摊影响、收费差额。
 */
class RainRelocationServiceTest {

    private Stall zoneStall(long id, String no, StallZone zone, boolean hardened,
                            boolean active, boolean emergency, Double x, Double y, Double fireDist) {
        Stall s = new Stall();
        ReflectionTestUtils.setField(s, "id", id);
        s.setStallNo(no);
        s.setZone(zone);
        s.setHardened(hardened);
        s.setActive(active);
        s.setReservedEmergency(emergency);
        s.setHasPower(false);
        s.setMaxVehicleLength(5.0);
        s.setPosX(x);
        s.setPosY(y);
        s.setFireLaneDistance(fireDist);
        return s;
    }

    @Test
    void candidates_computedCorrectly() {
        // 区域：D 果蔬低洼（费15），E 硬化应急疏导区（费12），A 百货区（费20）
        StallZone d = new StallZone(); d.setName("D区果蔬"); d.setAllowedCategories("VEG_FRUIT"); d.setBaseFee(new BigDecimal("15"));
        StallZone e = new StallZone(); e.setName("E区硬化应急"); e.setAllowedCategories("VEG_FRUIT"); e.setBaseFee(new BigDecimal("12")); e.setEntryOffsetMinutes(15);
        StallZone a = new StallZone(); a.setName("A区百货"); a.setAllowedCategories("DAILY_GOODS"); a.setBaseFee(new BigDecimal("20"));

        Stall oldStall = zoneStall(23, "D-05", d, false, true, false, 32.0, -12.0, null);
        Stall neighborStall = zoneStall(21, "D-03", d, false, true, false, 18.0, -12.0, null);
        Stall e01 = zoneStall(27, "E-01", e, true, true, true, 7.5, -9.5, null);
        Stall e02 = zoneStall(28, "E-02", e, true, true, true, 14.5, -9.5, null);
        Stall e04 = zoneStall(30, "E-04", e, true, true, true, 2.0, 2.0, 2.8);
        Stall a01 = zoneStall(9, "A-01", a, true, true, false, 12.0, 12.0, null);
        Stall fire = zoneStall(31, "FIRE-01", a, true, false, false, 0.0, 0.0, 0.0);

        User vendor = new User(); vendor.setName("赵四"); ReflectionTestUtils.setField(vendor, "id", 4L);
        User neighborVendor = new User(); neighborVendor.setName("钱五");

        MarketEvent event = new MarketEvent(); ReflectionTestUtils.setField(event, "id", 1L);
        Application app = new Application();
        app.setEvent(event); app.setVendor(vendor); app.setCategory("VEG_FRUIT");
        app.setNeedsPower(false); app.setVehicleLength(4.0);

        LotteryResult self = new LotteryResult();
        ReflectionTestUtils.setField(self, "id", 1L);
        self.setApplication(app); self.setStall(oldStall); self.setStallNo("D-05"); self.setStatus("ASSIGNED");

        Application neighborApp = new Application();
        neighborApp.setEvent(event); neighborApp.setVendor(neighborVendor); neighborApp.setCategory("DAILY_GOODS");
        LotteryResult neighbor = new LotteryResult();
        ReflectionTestUtils.setField(neighbor, "id", 2L);
        neighbor.setApplication(neighborApp); neighbor.setStall(neighborStall); neighbor.setStallNo("D-03"); neighbor.setStatus("RELOCATED");

        LotteryResultRepository resultRepo = mock(LotteryResultRepository.class);
        when(resultRepo.findById(1L)).thenReturn(java.util.Optional.of(self));
        when(resultRepo.findByApplicationEventId(any())).thenReturn(List.of(self, neighbor));
        StallRepository stallRepo = mock(StallRepository.class);
        when(stallRepo.findByActiveTrueOrderByStallNo())
                .thenReturn(List.of(oldStall, neighborStall, e01, e02, e04, a01));
        when(stallRepo.findAll()).thenReturn(List.of(fire, oldStall, neighborStall, e01, e02, e04, a01));
        RainRelocationRepository rainRepo = mock(RainRelocationRepository.class);
        when(rainRepo.findByEventIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

        RainRelocationService svc = new RainRelocationService(
                rainRepo, resultRepo, stallRepo, mock(PaymentRepository.class),
                mock(AdjustmentRepository.class), mock(AuditService.class), new ObjectMapper());

        List<Map<String, Object>> list = svc.candidates(1L, null);

        // 只剩三个硬化、兼容、未占用的 E 区应急位；低洼 D-05、占用 D-03、品类不符 A-01 全部过滤
        var nos = list.stream().map(m -> ((Stall) m.get("stall")).getStallNo()).toList();
        assertEquals(List.of("E-01", "E-02", "E-04"), nos);

        Map<String, Map<String, Object>> byNo = new java.util.HashMap<>();
        list.forEach(m -> byNo.put(((Stall) m.get("stall")).getStallNo(), m));

        // E-04 登记消防距离 2.8 米 → 安全预警
        assertEquals(Boolean.TRUE, byNo.get("E-04").get("fireLaneWarning"));
        // E-01 到消防通道(0,0) 距离 sqrt(7.5²+9.5²)≈12.1 → 无预警
        assertEquals(Boolean.FALSE, byNo.get("E-01").get("fireLaneWarning"));
        assertEquals(12.1, ((Number) byNo.get("E-01").get("fireLaneDistance")).doubleValue(), 0.05);

        // E-02 与 D-03(18,-12) 相距 4.3 米，落在 5 米邻摊半径内
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> impact = (List<Map<String, Object>>) byNo.get("E-02").get("neighbors");
        assertEquals(1, impact.size());
        assertEquals("D-03", impact.get(0).get("stallNo"));
        assertEquals(4.3, ((Number) impact.get(0).get("distanceMeters")).doubleValue(), 0.05);
        // E-01 距 D-03 超过 5 米，无邻摊
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> e01Impact = (List<Map<String, Object>>) byNo.get("E-01").get("neighbors");
        assertTrue(e01Impact.isEmpty());

        // 收费差额：原 D 区 ¥15 → 新 E 区 ¥12，应退 ¥3
        assertEquals(0, new BigDecimal("-3").compareTo((BigDecimal) byNo.get("E-02").get("feeDelta")));
    }
}
