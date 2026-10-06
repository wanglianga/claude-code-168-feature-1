package com.ruralmarket.platform.config;

import com.ruralmarket.platform.entity.*;
import com.ruralmarket.platform.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 首次启动初始化：账号、区域、摊位、一个报名中的赶集日 */
@Component
public class DataSeeder implements CommandLineRunner {
    private final UserRepository userRepository;
    private final StallZoneRepository zoneRepository;
    private final StallRepository stallRepository;
    private final MarketEventRepository eventRepository;
    private final PasswordEncoder passwordEncoder;

    public DataSeeder(UserRepository userRepository, StallZoneRepository zoneRepository,
                      StallRepository stallRepository, MarketEventRepository eventRepository,
                      PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.zoneRepository = zoneRepository;
        this.stallRepository = stallRepository;
        this.eventRepository = eventRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) return;

        // ===== 账号 =====
        createUser("admin", "admin123", "ADMIN", "乡镇管理员", "13800000001", 0, null);
        createUser("staff1", "staff123", "STAFF", "市场工作人员-刘", "13800000002", 0, null);
        createUser("vendor1", "vendor123", "VENDOR", "王老二(熟食)", "13900000001", 5, "常年在本集市售卖卤味，证照齐全");
        createUser("vendor2", "vendor123", "VENDOR", "李大嫂(糕点)", "13900000002", 4, "连续三年经营手工糕点");
        createUser("vendor3", "vendor123", "VENDOR", "张老三(活禽)", "13900000003", 2, "近两年开始售卖活禽");
        createUser("vendor4", "vendor123", "VENDOR", "赵四(果蔬)", "13900000004", 6, "老菜农，自产自销蔬菜水果");
        createUser("vendor5", "vendor123", "VENDOR", "钱五(百货)", "13900000005", 1, "新入驻日用百货摊主");
        createUser("vendor6", "vendor123", "VENDOR", "孙六(凉菜)", "13900000006", 3, "经营凉菜三年");

        // ===== 区域 =====
        StallZone zoneA = createZone("A", "A区-综合百货区", "DAILY_GOODS,CLOTHING,OTHER", false, false, "20", 0, "靠近集市东门");
        StallZone zoneB = createZone("B", "B区-熟食冷链区", "COOKED,PASTRY,COLD_DISH,MEAT", true, false, "30", 30, "配备冷链电源");
        StallZone zoneC = createZone("C", "C区-活禽隔离区", "LIVE_POULTRY", false, true, "25", 60, "独立隔离，远离食品区");
        StallZone zoneD = createZone("D", "D区-果蔬区", "VEG_FRUIT", false, false, "15", 0, "靠近集市西门");

        // ===== 摊位（A/B/C 区为硬化路面；D 区 D-01~D-04 为低洼泥地，暴雨易积水） =====
        double[] distA = {25, 22, 18, 15, 12, 9, 6, 2};
        for (int i = 1; i <= 8; i++) {
            createStall(zoneA, "A-" + pad(i), i <= 4, 5.0, i == 8, true, distA[i - 1], false, true);
        }
        double[] distB = {20, 17, 14, 11, 8, 2};
        for (int i = 1; i <= 6; i++) {
            createStall(zoneB, "B-" + pad(i), true, 4.0, i == 6, true, distB[i - 1], false, true);
        }
        double[] distC = {35, 32, 30, 28};
        for (int i = 1; i <= 4; i++) {
            createStall(zoneC, "C-" + pad(i), false, 6.0, false, true, distC[i - 1], false, true);
        }
        // D 区果蔬区：D-01~D-04 低洼未硬化（雨天积水重点调出对象），D-05~D-08 已硬化
        double[] distD = {3, 30, 28, 26, 24, 22, 20, 18};
        for (int i = 1; i <= 8; i++) {
            boolean low = i <= 4;
            createStall(zoneD, "D-" + pad(i), false, 5.0, i == 1, true, distD[i - 1], low, !low);
        }
        // 消防通道：不启用、不参与分配
        createStall(zoneA, "FIRE-01", false, null, true, false, 0.0, false, true);

        // ===== 赶集日 =====
        MarketEvent event = new MarketEvent();
        event.setName("十月十五大集");
        event.setMarketDate(LocalDate.of(2026, 10, 15));
        event.setStatus("OPEN");
        event.setRemark("报名后由市场管理审核，审核通过参与统一抽签");
        eventRepository.save(event);

        MarketEvent next = new MarketEvent();
        next.setName("十月廿九大集");
        next.setMarketDate(LocalDate.of(2026, 10, 29));
        next.setStatus("DRAFT");
        eventRepository.save(next);
    }

    private String pad(int i) {
        return String.format("%02d", i);
    }

    private void createUser(String username, String password, String role, String name,
                            String phone, int historyCount, String historyNotes) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(password));
        u.setRole(role);
        u.setName(name);
        u.setPhone(phone);
        u.setHistoryCount(historyCount);
        u.setHistoryNotes(historyNotes);
        userRepository.save(u);
    }

    private StallZone createZone(String code, String name, String categories, boolean cooked,
                                 boolean poultry, String fee, int offset, String remark) {
        StallZone z = new StallZone();
        z.setCode(code);
        z.setName(name);
        z.setAllowedCategories(categories);
        z.setAllowsCookedFood(cooked);
        z.setAllowsLivePoultry(poultry);
        z.setBaseFee(new BigDecimal(fee));
        z.setEntryOffsetMinutes(offset);
        z.setRemark(remark);
        return zoneRepository.save(z);
    }

    private void createStall(StallZone zone, String no, boolean power, Double maxLen,
                             boolean nearFire, boolean active, Double fireLaneDistance,
                             boolean lowLying, boolean hardened) {
        Stall s = new Stall();
        s.setZone(zone);
        s.setStallNo(no);
        s.setHasPower(power);
        s.setMaxVehicleLength(maxLen);
        s.setNearFireLane(nearFire);
        s.setActive(active);
        s.setFireLaneDistance(fireLaneDistance);
        s.setLowLying(lowLying);
        s.setHardened(hardened);
        stallRepository.save(s);
    }
}
