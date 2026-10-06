package com.ruralmarket.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruralmarket.platform.config.BizException;
import com.ruralmarket.platform.entity.*;
import com.ruralmarket.platform.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 抽签服务：确定性算法 + 规则/输入快照留痕。
 * 同一快照 + 同一种子 重放结果必然一致，任何人可复核，保证公开公平。
 */
@Service
public class LotteryService {
    private final ApplicationRepository applicationRepository;
    private final StallRepository stallRepository;
    private final LotteryRunRepository lotteryRunRepository;
    private final LotteryResultRepository lotteryResultRepository;
    private final MarketEventRepository eventRepository;
    private final PaymentRepository paymentRepository;
    private final AuditService auditService;
    private final ObjectMapper mapper = new ObjectMapper();

    public LotteryService(ApplicationRepository applicationRepository, StallRepository stallRepository,
                          LotteryRunRepository lotteryRunRepository, LotteryResultRepository lotteryResultRepository,
                          MarketEventRepository eventRepository, PaymentRepository paymentRepository,
                          AuditService auditService) {
        this.applicationRepository = applicationRepository;
        this.stallRepository = stallRepository;
        this.lotteryRunRepository = lotteryRunRepository;
        this.lotteryResultRepository = lotteryResultRepository;
        this.eventRepository = eventRepository;
        this.paymentRepository = paymentRepository;
        this.auditService = auditService;
    }

    /** 抽签规则（快照内容，规则调整需改版本号） */
    public Map<String, Object> ruleSnapshot() {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("version", "1.0");
        rules.put("priority", "老摊主(历史经营>=3次且信用>=60)优先，其余按随机种子排序");
        rules.put("cookedFood", "熟食仅分配至允许熟食且有电源的冷链区摊位");
        rules.put("livePoultry", "活禽仅分配至活禽隔离区摊位");
        rules.put("power", "有用电需求仅分配至有电源摊位");
        rules.put("vehicle", "车辆长度超过摊位上限时不予分配该摊位");
        rules.put("fireLane", "停用摊位(含消防通道)不参与分配");
        rules.put("veteranMinHistory", Constants.VETERAN_MIN_HISTORY);
        rules.put("veteranMinCredit", Constants.VETERAN_MIN_CREDIT);
        return rules;
    }

    /** 算法输入：报名快照 */
    public record AppInput(long id, String category, boolean needsPower, boolean sellsCookedFood,
                           boolean sellsLivePoultry, Double vehicleLength, boolean veteran, int historyCount) {}
    /** 算法输入：摊位快照 */
    public record StallInput(long id, String stallNo, String zoneCode, boolean hasPower, Double maxVehicleLength,
                             boolean allowsCookedFood, boolean allowsLivePoultry, String allowedCategories) {}

    /**
     * 确定性抽签算法：给定输入与种子，输出 报名ID→摊位ID 的分配。
     * 1) 全体报名用种子洗牌；2) 老摊主稳定置前（组内保持洗牌顺序）；
     * 3) 依次为其在兼容摊位中等概率抽取一个。
     */
    public Map<Long, Long> assign(List<AppInput> apps, List<StallInput> stalls, long seed) {
        List<AppInput> ordered = new ArrayList<>(apps);
        Collections.shuffle(ordered, new Random(seed));
        // 稳定排序：老摊主优先，同级内保持洗牌后的随机顺序
        ordered.sort(Comparator.comparingInt((AppInput a) -> a.veteran() ? 0 : 1)
                .thenComparingInt(a -> -a.historyCount()));

        List<StallInput> pool = new ArrayList<>(stalls);
        pool.sort(Comparator.comparing(StallInput::stallNo)); // 固定顺序保证可重放
        Random random = new Random(seed + 1);
        Map<Long, Long> result = new LinkedHashMap<>();
        Set<Long> used = new HashSet<>();

        for (AppInput app : ordered) {
            List<StallInput> compatible = new ArrayList<>();
            for (StallInput s : pool) {
                if (!used.contains(s.id()) && compatible(app, s)) {
                    compatible.add(s);
                }
            }
            if (compatible.isEmpty()) continue; // 无兼容摊位 → 未中签
            StallInput picked = compatible.get(random.nextInt(compatible.size()));
            result.put(app.id(), picked.id());
            used.add(picked.id());
        }
        return result;
    }

    private boolean compatible(AppInput app, StallInput s) {
        if (app.sellsCookedFood() && !(s.allowsCookedFood() && s.hasPower())) return false;
        if (app.sellsLivePoultry() && !s.allowsLivePoultry()) return false;
        if (app.needsPower() && !s.hasPower()) return false;
        if (app.vehicleLength() != null && s.maxVehicleLength() != null
                && app.vehicleLength() > s.maxVehicleLength()) return false;
        String allowed = s.allowedCategories();
        if (allowed != null && !allowed.isBlank() && !allowed.equals("*")) {
            Set<String> set = new HashSet<>(Arrays.asList(allowed.split(",")));
            if (!set.contains(app.category())) return false;
        }
        return true;
    }

    /** 执行一次抽签并持久化（含快照、结果、收费单） */
    @Transactional
    public LotteryRun runLottery(MarketEvent event, long seed, User operator) {
        if (!"CLOSED".equals(event.getStatus())) {
            throw new BizException("请先截止报名再组织抽签（当前状态：" + event.getStatus() + "）");
        }
        List<Application> apps = applicationRepository.findByEventIdAndStatus(event.getId(), "APPROVED");
        if (apps.isEmpty()) throw new BizException("没有已通过审核的报名，无法抽签");
        // 应急备用位（硬化路面预留）仅用于临时/雨天调位，不参与常规抽签
        List<Stall> stalls = stallRepository.findByActiveTrueOrderByStallNo().stream()
                .filter(s -> !Boolean.TRUE.equals(s.getReservedEmergency()))
                .toList();
        if (stalls.isEmpty()) throw new BizException("没有可用摊位，请先配置摊位");

        List<AppInput> appInputs = apps.stream().map(a -> new AppInput(
                a.getId(), a.getCategory(), a.getNeedsPower(), a.getSellsCookedFood(), a.getSellsLivePoultry(),
                a.getVehicleLength(), a.getVeteran(), a.getVendor().getHistoryCount())).toList();
        List<StallInput> stallInputs = stalls.stream().map(s -> new StallInput(
                s.getId(), s.getStallNo(), s.getZone().getCode(), s.getHasPower(), s.getMaxVehicleLength(),
                s.getZone().getAllowsCookedFood(), s.getZone().getAllowsLivePoultry(),
                s.getZone().getAllowedCategories())).toList();

        Map<Long, Long> assignment = assign(appInputs, stallInputs, seed);

        LotteryRun run = new LotteryRun();
        run.setEvent(event);
        run.setSeed(seed);
        run.setOperator(operator);
        try {
            run.setRuleSnapshot(mapper.writeValueAsString(ruleSnapshot()));
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("applications", appInputs);
            input.put("stalls", stallInputs);
            run.setInputSnapshot(mapper.writeValueAsString(input));
        } catch (Exception e) {
            throw new BizException("快照序列化失败: " + e.getMessage());
        }
        run.setRemark("应抽 " + apps.size() + " 家，中签 " + assignment.size() + " 家");
        run = lotteryRunRepository.save(run);

        Map<Long, Stall> stallMap = new HashMap<>();
        stalls.forEach(s -> stallMap.put(s.getId(), s));
        for (Application app : apps) {
            Long stallId = assignment.get(app.getId());
            if (stallId == null) {
                app.setStatus("LOST");
                applicationRepository.save(app);
                continue;
            }
            Stall stall = stallMap.get(stallId);
            app.setStatus("WON");
            applicationRepository.save(app);

            LotteryResult r = new LotteryResult();
            r.setRun(run);
            r.setApplication(app);
            r.setStall(stall);
            r.setStallNo(stall.getStallNo());
            r.setEntryTime(calcEntryTime(stall.getZone().getEntryOffsetMinutes()));
            List<Map<String, Object>> feeItems = buildFeeItems(stall, app);
            r.setFeeItems(toJson(feeItems));
            r.setDeposit(calcDeposit(app));
            r.setProhibitedItems(toJson(buildProhibited(app, stall)));
            r.setQrToken(UUID.randomUUID().toString().replace("-", ""));
            lotteryResultRepository.save(r);

            // 生成缴费单
            for (Map<String, Object> item : feeItems) {
                createPayment(event, r, app.getVendor(), (String) item.get("type"),
                        (String) item.get("item"), new BigDecimal(item.get("amount").toString()));
            }
            createPayment(event, r, app.getVendor(), "DEPOSIT", "经营保证金", r.getDeposit());
        }

        event.setStatus("LOTTERY_DONE");
        eventRepository.save(event);
        auditService.log(operator.getId(), operator.getName(), "LOTTERY_RUN",
                "集市[" + event.getName() + "] 抽签完成，种子=" + seed + "，" + run.getRemark());
        return run;
    }

    /** 复核：按快照与种子重放，校验与库中结果一致 */
    @Transactional(readOnly = true)
    public Map<String, Object> verify(LotteryRun run) {
        try {
            Map<?, ?> snapshot = mapper.readValue(run.getInputSnapshot(), Map.class);
            List<AppInput> apps = new ArrayList<>();
            for (Map<?, ?> a : (List<Map<?, ?>>) snapshot.get("applications")) {
                apps.add(new AppInput(
                        ((Number) a.get("id")).longValue(), (String) a.get("category"),
                        Boolean.TRUE.equals(a.get("needsPower")), Boolean.TRUE.equals(a.get("sellsCookedFood")),
                        Boolean.TRUE.equals(a.get("sellsLivePoultry")),
                        a.get("vehicleLength") == null ? null : ((Number) a.get("vehicleLength")).doubleValue(),
                        Boolean.TRUE.equals(a.get("veteran")),
                        a.get("historyCount") == null ? 0 : ((Number) a.get("historyCount")).intValue()));
            }
            List<StallInput> stalls = new ArrayList<>();
            for (Map<?, ?> s : (List<Map<?, ?>>) snapshot.get("stalls")) {
                stalls.add(new StallInput(
                        ((Number) s.get("id")).longValue(), (String) s.get("stallNo"), (String) s.get("zoneCode"),
                        Boolean.TRUE.equals(s.get("hasPower")),
                        s.get("maxVehicleLength") == null ? null : ((Number) s.get("maxVehicleLength")).doubleValue(),
                        Boolean.TRUE.equals(s.get("allowsCookedFood")), Boolean.TRUE.equals(s.get("allowsLivePoultry")),
                        (String) s.get("allowedCategories")));
            }
            Map<Long, Long> replayed = assign(apps, stalls, run.getSeed());

            Map<Long, Long> actual = new LinkedHashMap<>();
            lotteryResultRepository.findByRunId(run.getId())
                    .forEach(r -> actual.put(r.getApplication().getId(), r.getStall().getId()));

            boolean match = replayed.equals(actual);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("match", match);
            out.put("replayedCount", replayed.size());
            out.put("actualCount", actual.size());
            out.put("seed", run.getSeed());
            out.put("message", match ? "复核通过：按相同种子与快照重放，分配结果完全一致"
                    : "复核不一致：结果与快照不符，请排查数据是否被篡改");
            return out;
        } catch (Exception e) {
            throw new BizException("复核失败: " + e.getMessage());
        }
    }

    private String calcEntryTime(int offsetMinutes) {
        int total = 6 * 60 + offsetMinutes;
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private List<Map<String, Object>> buildFeeItems(Stall stall, Application app) {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("type", "STALL_FEE", "item", "摊位费(" + stall.getZone().getName() + ")",
                "amount", stall.getZone().getBaseFee()));
        items.add(Map.of("type", "SANITATION", "item", "卫生费", "amount", new BigDecimal("5")));
        if (Boolean.TRUE.equals(app.getNeedsPower())) {
            items.add(Map.of("type", "POWER", "item", "电费", "amount", new BigDecimal("10")));
        }
        return items;
    }

    private BigDecimal calcDeposit(Application app) {
        if (Boolean.TRUE.equals(app.getSellsLivePoultry())) return new BigDecimal("300");
        if (Boolean.TRUE.equals(app.getSellsCookedFood())) return new BigDecimal("200");
        return new BigDecimal("100");
    }

    private List<String> buildProhibited(Application app, Stall stall) {
        List<String> list = new ArrayList<>();
        list.add("禁止缺斤少两，必须明码标价");
        list.add("禁止占用消防通道与公共过道");
        if (Boolean.TRUE.equals(app.getSellsCookedFood())) {
            list.add("熟食必须留样48小时并登记批次与保存位置");
            list.add("禁止销售来源不明、变质食品");
        }
        if (Boolean.TRUE.equals(app.getSellsLivePoultry())) {
            list.add("活禽禁止出隔离区交易，必须每日消毒");
        }
        if (Boolean.TRUE.equals(stall.getNearFireLane())) {
            list.add("本摊位紧邻消防通道，禁止堆物外延");
        }
        if (Boolean.TRUE.equals(app.getNeedsPower())) {
            list.add("禁止私拉乱接电线，离场断电");
        }
        return list;
    }

    private Payment createPayment(MarketEvent event, LotteryResult r, User vendor,
                                  String type, String name, BigDecimal amount) {
        Payment p = new Payment();
        p.setEvent(event);
        p.setResult(r);
        p.setVendor(vendor);
        p.setItemType(type);
        p.setItemName(name);
        p.setAmount(amount);
        return paymentRepository.save(p);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException("JSON序列化失败");
        }
    }
}
