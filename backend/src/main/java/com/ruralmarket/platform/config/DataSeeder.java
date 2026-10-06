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
        backfillStallDefaults();
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
        StallZone zoneA = createZone("A", "A区-综合百货区", "DAILY_GOODS,CLOTHING,OTHER", false, false, "20", 0, "靠近集市东门，硬化路面");
        StallZone zoneB = createZone("B", "B区-熟食冷链区", "COOKED,PASTRY,COLD_DISH,MEAT", true, false, "30", 30, "配备冷链电源，硬化路面");
        StallZone zoneC = createZone("C", "C区-活禽隔离区", "LIVE_POULTRY", false, true, "25", 60, "独立隔离，远离食品区");
        StallZone zoneD = createZone("D", "D区-果蔬区", "VEG_FRUIT", false, false, "15", 0, "靠近集市西门，低洼泥地，暴雨易积水");
        // 硬化路面应急疏导区：不参与常规抽签，仅供雨天等临时调位（收费略低，演示应退差额）
        StallZone zoneE = createZone("E", "E区-硬化路面应急疏导区", "VEG_FRUIT,DAILY_GOODS,OTHER", false, false, "12", 15, "硬化路面，暴雨等天气临时调位专用，预留不参与抽签");

        // ===== 摊位（含平面坐标，用于计算消防通道距离与邻摊影响） =====
        // A 区（硬化路面）
        for (int i = 1; i <= 8; i++) {
            Stall s = baseStall(zoneA, "A-" + pad(i), i <= 4, 5.0, i == 8, true);
            s.setHardened(true); s.setPosX(6.0 * i); s.setPosY(12.0);
            stallRepository.save(s);
        }
        // B 区（硬化冷链）
        for (int i = 1; i <= 6; i++) {
            Stall s = baseStall(zoneB, "B-" + pad(i), true, 4.0, i == 6, true);
            s.setHardened(true); s.setPosX(6.0 * i); s.setPosY(30.0);
            stallRepository.save(s);
        }
        // C 区（活禽隔离）
        for (int i = 1; i <= 4; i++) {
            Stall s = baseStall(zoneC, "C-" + pad(i), false, 6.0, false, true);
            s.setHardened(true); s.setPosX(60.0); s.setPosY(20.0 + 6.0 * i);
            stallRepository.save(s);
        }
        // D 区：低洼泥地（未硬化），暴雨清晨易积水——雨天调位的主要受影响摊位
        for (int i = 1; i <= 8; i++) {
            Stall s = baseStall(zoneD, "D-" + pad(i), false, 5.0, i == 1, true);
            s.setHardened(false); s.setPosX(4.0 + 7.0 * (i - 1)); s.setPosY(-12.0);
            stallRepository.save(s);
        }
        // E 区：硬化路面应急备用位（reservedEmergency=true，不参与抽签）；
        // E-01/E-02 紧邻 D 区果蔬摊（演示邻摊影响），E-03 紧邻消防通道（演示安全距离预警）
        createEmergencyStall(zoneE, "E-01", 7.5, -9.5, 5.0);
        createEmergencyStall(zoneE, "E-02", 14.5, -9.5, 5.0);
        createEmergencyStall(zoneE, "E-03", 21.5, -9.5, 5.0);
        Stall nearFire = baseStall(zoneE, "E-04", false, 5.0, true, true);
        nearFire.setHardened(true); nearFire.setReservedEmergency(true);
        nearFire.setPosX(2.0); nearFire.setPosY(2.0); nearFire.setFireLaneDistance(2.8);
        stallRepository.save(nearFire);
        // 消防通道：不启用、不参与分配，坐标原点供距离估算
        Stall fire = baseStall(zoneA, "FIRE-01", false, null, true, false);
        fire.setHardened(true); fire.setPosX(0.0); fire.setPosY(0.0);
        stallRepository.save(fire);

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

    /** 旧库升级回填：新增的 hardened / reservedEmergency 列为可空，历史摊位按硬化路面、非应急位补齐 */
    private void backfillStallDefaults() {
        java.util.List<Stall> all = stallRepository.findAll();
        java.util.List<Stall> changed = new java.util.ArrayList<>();
        for (Stall s : all) {
            if (s.getHardened() == null) { s.setHardened(true); changed.add(s); }
            if (s.getReservedEmergency() == null) {
                s.setReservedEmergency(false);
                if (!changed.contains(s)) changed.add(s);
            }
        }
        if (!changed.isEmpty()) stallRepository.saveAll(changed);
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

    private Stall baseStall(StallZone zone, String no, boolean power, Double maxLen,
                            boolean nearFire, boolean active) {
        Stall s = new Stall();
        s.setZone(zone);
        s.setStallNo(no);
        s.setHasPower(power);
        s.setMaxVehicleLength(maxLen);
        s.setNearFireLane(nearFire);
        s.setActive(active);
        return s;
    }

    /** 硬化路面应急备用位：启用但不参与常规抽签，平面坐标用于距离与邻摊影响计算 */
    private void createEmergencyStall(StallZone zone, String no, double x, double y, Double maxLen) {
        Stall s = baseStall(zone, no, false, maxLen, false, true);
        s.setHardened(true);
        s.setReservedEmergency(true);
        s.setPosX(x);
        s.setPosY(y);
        stallRepository.save(s);
    }
}
