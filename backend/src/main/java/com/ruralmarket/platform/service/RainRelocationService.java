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
import java.util.stream.Collectors;

/**
 * 雨天临时调位：暴雨积水时将低洼摊位调至硬化路面的全流程。
 * 发起（展示受影响摊位、可替代位置、消防通道距离、邻摊影响）
 * → 摊主确认（重新生成入场二维码 + 收费差额单）
 * → 财务核对（新旧摊位照片、摊主确认、收费差额）；
 * 摊主拒绝的，管理员可标记「放弃经营」或「保留原位风险自担」。
 * 全过程生成 Adjustment 并写审计日志，向摊主公开。
 */
@Service
public class RainRelocationService {
    private final RainRelocationRepository relocationRepository;
    private final LotteryResultRepository resultRepository;
    private final StallRepository stallRepository;
    private final PaymentRepository paymentRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final AuditService auditService;
    private final ObjectMapper mapper = new ObjectMapper();

    public RainRelocationService(RainRelocationRepository relocationRepository,
                                 LotteryResultRepository resultRepository, StallRepository stallRepository,
                                 PaymentRepository paymentRepository, AdjustmentRepository adjustmentRepository,
                                 AuditService auditService) {
        this.relocationRepository = relocationRepository;
        this.resultRepository = resultRepository;
        this.stallRepository = stallRepository;
        this.paymentRepository = paymentRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.auditService = auditService;
    }

    // ================= 受影响摊位与可替代位置 =================

    /**
     * 受影响摊位：本集市已分配且摊位为低洼易积水的分配单；
     * 每个分配单给出可替代位置（硬化路面、启用、空闲、品类/用电/车长兼容），
     * 并附消防通道距离与邻摊影响说明。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> affected(Long eventId) {
        List<LotteryResult> results = resultRepository.findByApplicationEventId(eventId).stream()
                .filter(r -> !"CANCELLED".equals(r.getStatus()))
                .toList();
        Set<Long> occupied = results.stream().map(r -> r.getStall().getId()).collect(Collectors.toSet());
        // 已被待确认/已确认调位单锁定的新摊位不再作为替代位置
        Set<Long> lockedByTicket = relocationRepository.findByEventIdOrderByCreatedAtDesc(eventId).stream()
                .filter(t -> "PENDING".equals(t.getStatus()) || "CONFIRMED".equals(t.getStatus()))
                .map(t -> t.getNewStall().getId()).collect(Collectors.toSet());
        List<Stall> allStalls = stallRepository.findAll();

        List<Map<String, Object>> out = new ArrayList<>();
        for (LotteryResult r : results) {
            if (!Boolean.TRUE.equals(r.getStall().getLowLying())) continue;
            // 已有待确认调位单的不再重复发起
            boolean hasPending = relocationRepository.findByResultIdOrderByCreatedAtDesc(r.getId()).stream()
                    .anyMatch(t -> "PENDING".equals(t.getStatus()));
            Application app = r.getApplication();
            List<Map<String, Object>> alternatives = new ArrayList<>();
            for (Stall s : allStalls) {
                if (!Boolean.TRUE.equals(s.getActive()) || !Boolean.TRUE.equals(s.getHardened())) continue;
                if (occupied.contains(s.getId()) || lockedByTicket.contains(s.getId())) continue;
                if (!compatible(app, s)) continue;
                Map<String, Object> alt = new LinkedHashMap<>();
                alt.put("stall", s);
                alt.put("neighborImpact", neighborImpact(s, eventId, results, app));
                alternatives.add(alt);
            }
            alternatives.sort(Comparator.comparing(m -> ((Stall) m.get("stall")).getStallNo()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("result", r);
            row.put("hasPendingTicket", hasPending);
            row.put("alternatives", alternatives);
            out.add(row);
        }
        return out;
    }

    /** 邻摊影响：同区域左右相邻摊位的占用与经营品类，叠加敏感品类冲突与消防提示 */
    private String neighborImpact(Stall target, Long eventId, List<LotteryResult> eventResults, Application movingApp) {
        List<String> parts = new ArrayList<>();
        Map<Long, LotteryResult> byStall = eventResults.stream()
                .collect(Collectors.toMap(r -> r.getStall().getId(), r -> r, (a, b) -> a));
        for (Stall neighbor : neighborsOf(target)) {
            LotteryResult nr = byStall.get(neighbor.getId());
            if (nr != null) {
                String cat = Constants.categoryName(nr.getApplication().getCategory());
                parts.add("邻摊 " + neighbor.getStallNo() + "：" + nr.getApplication().getVendor().getName()
                        + "（" + cat + "）");
                if (conflict(nr.getApplication(), movingApp)) {
                    parts.add("⚠ 与邻摊「" + cat + "」品类相邻敏感，注意隔离");
                }
            } else {
                parts.add("邻摊 " + neighbor.getStallNo() + "：空闲");
            }
        }
        if (parts.isEmpty()) parts.add("同排无相邻摊位");
        if (Boolean.TRUE.equals(target.getNearFireLane())) {
            parts.add("紧邻消防通道（" + distText(target) + "），禁止堆物外延");
        } else {
            parts.add("距消防通道 " + distText(target));
        }
        return String.join("；", parts);
    }

    private String distText(Stall s) {
        return s.getFireLaneDistance() == null ? "未知" : (s.getFireLaneDistance() + "米");
    }

    /** 同区域、同前缀、编号 ±1 的摊位 */
    private List<Stall> neighborsOf(Stall target) {
        String no = target.getStallNo();
        int dash = no.lastIndexOf('-');
        if (dash < 0) return List.of();
        String prefix = no.substring(0, dash + 1);
        int num;
        try {
            num = Integer.parseInt(no.substring(dash + 1));
        } catch (NumberFormatException e) {
            return List.of();
        }
        Long zoneId = target.getZone().getId();
        Set<String> wanted = Set.of(prefix + String.format("%02d", num - 1), prefix + String.format("%02d", num + 1));
        return stallRepository.findAll().stream()
                .filter(s -> s.getZone().getId().equals(zoneId) && wanted.contains(s.getStallNo()))
                .sorted(Comparator.comparing(Stall::getStallNo))
                .toList();
    }

    /** 食品类与活禽相邻为敏感组合 */
    private boolean conflict(Application a, Application b) {
        Set<String> food = Set.of("COOKED", "PASTRY", "COLD_DISH", "MEAT", "VEG_FRUIT");
        boolean aPoultry = Boolean.TRUE.equals(a.getSellsLivePoultry());
        boolean bPoultry = Boolean.TRUE.equals(b.getSellsLivePoultry());
        return (aPoultry && food.contains(b.getCategory())) || (bPoultry && food.contains(a.getCategory()));
    }

    /** 与抽签一致的摊位兼容性校验 */
    private boolean compatible(Application app, Stall s) {
        if (Boolean.TRUE.equals(app.getSellsCookedFood())
                && !(Boolean.TRUE.equals(s.getZone().getAllowsCookedFood()) && Boolean.TRUE.equals(s.getHasPower()))) return false;
        if (Boolean.TRUE.equals(app.getSellsLivePoultry()) && !Boolean.TRUE.equals(s.getZone().getAllowsLivePoultry())) return false;
        if (Boolean.TRUE.equals(app.getNeedsPower()) && !Boolean.TRUE.equals(s.getHasPower())) return false;
        if (app.getVehicleLength() != null && s.getMaxVehicleLength() != null
                && app.getVehicleLength() > s.getMaxVehicleLength()) return false;
        String allowed = s.getZone().getAllowedCategories();
        if (allowed != null && !allowed.isBlank() && !allowed.equals("*")) {
            if (!Arrays.asList(allowed.split(",")).contains(app.getCategory())) return false;
        }
        return true;
    }

    // ================= 发起调位 =================

    @Transactional
    public RainRelocation create(Long resultId, Long newStallId, String reason,
                                 String oldStallPhoto, String newStallPhoto, User operator) {
        LotteryResult r = resultRepository.findById(resultId)
                .orElseThrow(() -> new BizException("分配单不存在"));
        if ("CANCELLED".equals(r.getStatus())) throw new BizException("该分配单已取消");
        boolean hasPending = relocationRepository.findByResultIdOrderByCreatedAtDesc(resultId).stream()
                .anyMatch(t -> "PENDING".equals(t.getStatus()));
        if (hasPending) throw new BizException("该摊位已有待摊主确认的调位单");

        Stall target = stallRepository.findById(newStallId)
                .orElseThrow(() -> new BizException("目标摊位不存在"));
        MarketEvent event = r.getApplication().getEvent();
        validateTargetAvailable(event.getId(), r, target);

        RainRelocation t = new RainRelocation();
        t.setEvent(event);
        t.setResult(r);
        t.setVendor(r.getApplication().getVendor());
        t.setOldStall(r.getStall());
        t.setOldStallNo(r.getStallNo());
        t.setNewStall(target);
        t.setNewStallNo(target.getStallNo());
        t.setReason(reason);
        t.setOldStallPhoto(oldStallPhoto);
        t.setNewStallPhoto(newStallPhoto);
        t.setFireLaneDistance(target.getFireLaneDistance());
        List<LotteryResult> eventResults = resultRepository.findByApplicationEventId(event.getId()).stream()
                .filter(x -> !"CANCELLED".equals(x.getStatus())).toList();
        t.setNeighborImpact(neighborImpact(target, event.getId(), eventResults, r.getApplication()));
        BigDecimal oldFee = r.getStall().getZone().getBaseFee();
        BigDecimal newFee = target.getZone().getBaseFee();
        t.setOldFee(oldFee);
        t.setNewFee(newFee);
        t.setFeeDiff(newFee.subtract(oldFee));
        t.setOperator(operator);
        RainRelocation saved = relocationRepository.save(t);
        auditService.log(operator.getId(), operator.getName(), "RAIN_RELOCATE_CREATE",
                "发起雨天调位#" + saved.getId() + "：摊主[" + t.getVendor().getName() + "] "
                        + t.getOldStallNo() + " → " + t.getNewStallNo() + "，差额 ¥" + t.getFeeDiff()
                        + "，原因：" + reason);
        return saved;
    }

    private void validateTargetAvailable(Long eventId, LotteryResult r, Stall target) {
        if (!Boolean.TRUE.equals(target.getActive())) throw new BizException("目标摊位未启用，不能调入");
        if (!Boolean.TRUE.equals(target.getHardened())) throw new BizException("目标摊位非硬化路面，不符合雨天调位要求");
        if (!compatible(r.getApplication(), target)) {
            throw new BizException("目标摊位 " + target.getStallNo() + " 与该摊主品类/用电/车辆限制不兼容");
        }
        boolean occupied = resultRepository.findByApplicationEventId(eventId).stream()
                .anyMatch(x -> !x.getId().equals(r.getId()) && x.getStall().getId().equals(target.getId())
                        && !"CANCELLED".equals(x.getStatus()));
        if (occupied) throw new BizException("目标摊位 " + target.getStallNo() + " 已被占用");
        boolean locked = relocationRepository.findByEventIdOrderByCreatedAtDesc(eventId).stream()
                .anyMatch(t -> !t.getResult().getId().equals(r.getId())
                        && ("PENDING".equals(t.getStatus()) || "CONFIRMED".equals(t.getStatus()))
                        && t.getNewStall().getId().equals(target.getId()));
        if (locked) throw new BizException("目标摊位 " + target.getStallNo() + " 已被其他调位单锁定");
    }

    // ================= 摊主确认 / 拒绝 =================

    /** 摊主确认：执行调位，重新生成入场二维码，核算收费差额 */
    @Transactional
    public RainRelocation confirm(Long id, User vendor, String note) {
        RainRelocation t = relocationRepository.findById(id)
                .orElseThrow(() -> new BizException("调位单不存在"));
        if (!t.getVendor().getId().equals(vendor.getId())) throw new BizException("无权操作他人调位单");
        if (!"PENDING".equals(t.getStatus())) throw new BizException("该调位单当前状态不可确认（" + t.getStatus() + "）");
        LotteryResult r = t.getResult();
        // 确认前再次校验目标摊位仍可用（可能被后续调位/调整占用）
        validateTargetAvailable(t.getEvent().getId(), r, t.getNewStall());

        // 1) 更新分配单：新摊位 + 新入场时间 + 重新生成二维码
        Stall target = t.getNewStall();
        r.setStall(target);
        r.setStallNo(target.getStallNo());
        r.setEntryTime(calcEntryTime(target.getZone().getEntryOffsetMinutes()));
        r.setStatus("RELOCATED");
        String newQr = UUID.randomUUID().toString().replace("-", "");
        r.setQrToken(newQr);
        r.setFeeItems(updateFeeItems(r.getFeeItems(), target));
        resultRepository.save(r);
        t.setNewQrToken(newQr);

        // 2) 收费差额单：正=摊主补缴，负=应退摊主
        if (t.getFeeDiff().compareTo(BigDecimal.ZERO) != 0) {
            Payment p = new Payment();
            p.setEvent(t.getEvent());
            p.setResult(r);
            p.setVendor(t.getVendor());
            p.setItemType("RELOCATE_DIFF");
            p.setItemName("雨天调位差额(" + t.getOldStallNo() + "→" + t.getNewStallNo() + ")");
            p.setAmount(t.getFeeDiff());
            p.setRemark(t.getFeeDiff().signum() > 0 ? "新摊位费高于原摊位，差额待补缴"
                    : "新摊位费低于原摊位，差额待退还摊主");
            paymentRepository.save(p);
        }

        // 3) 留痕：Adjustment + 审计
        Adjustment adj = baseAdjustment(t, vendor);
        adj.setBeforeValue("摊位 " + t.getOldStallNo());
        adj.setAfterValue("摊位 " + t.getNewStallNo() + "（入场 " + r.getEntryTime()
                + "，差额 ¥" + t.getFeeDiff() + "，已换发新入场码）");
        adjustmentRepository.save(adj);

        t.setStatus("CONFIRMED");
        t.setVendorNote(note);
        t.setConfirmedAt(LocalDateTime.now());
        RainRelocation saved = relocationRepository.save(t);
        auditService.log(vendor.getId(), vendor.getName(), "RAIN_RELOCATE_CONFIRM",
                "摊主确认调位#" + id + "：" + t.getOldStallNo() + " → " + t.getNewStallNo()
                        + "，差额 ¥" + t.getFeeDiff() + "，已重新生成入场码");
        return saved;
    }

    /** 摊主拒绝调位 */
    @Transactional
    public RainRelocation reject(Long id, User vendor, String note) {
        RainRelocation t = relocationRepository.findById(id)
                .orElseThrow(() -> new BizException("调位单不存在"));
        if (!t.getVendor().getId().equals(vendor.getId())) throw new BizException("无权操作他人调位单");
        if (!"PENDING".equals(t.getStatus())) throw new BizException("该调位单当前状态不可拒绝（" + t.getStatus() + "）");
        if (note == null || note.isBlank()) throw new BizException("拒绝调位请填写说明");
        t.setStatus("REJECTED");
        t.setVendorNote(note);
        RainRelocation saved = relocationRepository.save(t);
        auditService.log(vendor.getId(), vendor.getName(), "RAIN_RELOCATE_REJECT",
                "摊主拒绝调位#" + id + "（" + t.getOldStallNo() + " → " + t.getNewStallNo() + "），说明：" + note);
        return saved;
    }

    // ================= 管理员处理拒绝单 =================

    /**
     * 摊主拒绝后的处理：ABANDONED 放弃经营（取消分配、未缴账单作废）/ KEEP_ORIGINAL 保留原位风险自担。
     */
    @Transactional
    public RainRelocation resolve(Long id, String action, String note, User operator) {
        RainRelocation t = relocationRepository.findById(id)
                .orElseThrow(() -> new BizException("调位单不存在"));
        if (!"REJECTED".equals(t.getStatus())) throw new BizException("仅摊主已拒绝的调位单可处理（当前：" + t.getStatus() + "）");
        if (note == null || note.isBlank()) throw new BizException("请填写处理说明");
        LotteryResult r = t.getResult();
        Adjustment adj = baseAdjustment(t, operator);
        adj.setBeforeValue("摊位 " + t.getOldStallNo() + "（摊主拒绝调至 " + t.getNewStallNo() + "）");

        if ("ABANDONED".equals(action)) {
            r.setStatus("CANCELLED");
            resultRepository.save(r);
            // 未缴账单作废
            for (Payment p : paymentRepository.findByResultId(r.getId())) {
                if ("UNPAID".equals(p.getStatus())) {
                    p.setStatus("WAIVED");
                    p.setRemark("雨天放弃经营，账单作废：" + note);
                    paymentRepository.save(p);
                }
            }
            adj.setAfterValue("放弃经营，摊位 " + t.getOldStallNo() + " 释放，未缴账单作废");
            t.setStatus("ABANDONED");
        } else if ("KEEP_ORIGINAL".equals(action)) {
            adj.setAfterValue("保留原位 " + t.getOldStallNo() + "，积水风险自担：" + note);
            t.setStatus("KEEP_ORIGINAL");
        } else {
            throw new BizException("非法处理方式: " + action);
        }
        adjustmentRepository.save(adj);
        t.setResolveNote(note);
        t.setResolvedAt(LocalDateTime.now());
        RainRelocation saved = relocationRepository.save(t);
        auditService.log(operator.getId(), operator.getName(), "RAIN_RELOCATE_RESOLVE",
                "调位单#" + id + " 处理为 " + ("ABANDONED".equals(action) ? "放弃经营" : "保留原位风险自担")
                        + "：摊主[" + t.getVendor().getName() + "] " + t.getOldStallNo() + "，说明：" + note);
        return saved;
    }

    // ================= 财务核对 =================

    /** 财务视图：已确认调位单的新旧照片、摊主确认与收费差额（含差额账单状态） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> finance(Long eventId) {
        List<RainRelocation> tickets = relocationRepository.findByEventIdOrderByCreatedAtDesc(eventId).stream()
                .filter(t -> "CONFIRMED".equals(t.getStatus())).toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (RainRelocation t : tickets) {
            Payment diff = paymentRepository.findByResultId(t.getResult().getId()).stream()
                    .filter(p -> "RELOCATE_DIFF".equals(p.getItemType()))
                    .reduce((a, b) -> b).orElse(null); // 取最新一张差额单
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ticket", t);
            row.put("diffPayment", diff);
            out.add(row);
        }
        return out;
    }

    /** 财务标记差额已退还（仅负差额，即应退摊主） */
    @Transactional
    public RainRelocation settle(Long id, User operator) {
        RainRelocation t = relocationRepository.findById(id)
                .orElseThrow(() -> new BizException("调位单不存在"));
        if (!"CONFIRMED".equals(t.getStatus())) throw new BizException("仅已确认调位单可结算");
        Payment diff = paymentRepository.findByResultId(t.getResult().getId()).stream()
                .filter(p -> "RELOCATE_DIFF".equals(p.getItemType()))
                .reduce((a, b) -> b).orElse(null);
        if (diff == null) throw new BizException("该调位无差额，无需结算");
        if (diff.getAmount().signum() >= 0) throw new BizException("正差额应由摊主在线补缴，无需财务退还");
        if (!"UNPAID".equals(diff.getStatus())) throw new BizException("差额单当前状态不可结算（" + diff.getStatus() + "）");
        diff.setStatus("REFUNDED");
        diff.setPaidAt(LocalDateTime.now());
        diff.setRemark((diff.getRemark() == null ? "" : diff.getRemark() + "；") + "财务已退还摊主");
        paymentRepository.save(diff);
        auditService.log(operator.getId(), operator.getName(), "RAIN_RELOCATE_SETTLE",
                "调位单#" + id + " 差额 ¥" + diff.getAmount() + " 已退还摊主[" + t.getVendor().getName() + "]");
        return t;
    }

    private Adjustment baseAdjustment(RainRelocation t, User operator) {
        Adjustment adj = new Adjustment();
        adj.setEvent(t.getEvent());
        adj.setResult(t.getResult());
        adj.setVendor(t.getVendor());
        adj.setType("RAIN_RELOCATE");
        adj.setReason(t.getReason());
        adj.setSource("RAIN");
        adj.setSourceRefId(t.getId());
        adj.setOperator(operator);
        return adj;
    }

    /** 更新收费项目快照中的摊位费（区域变更） */
    private String updateFeeItems(String feeItemsJson, Stall newStall) {
        try {
            List<Map<String, Object>> items = mapper.readValue(feeItemsJson, List.class);
            for (Map<String, Object> item : items) {
                if ("STALL_FEE".equals(item.get("type"))) {
                    item.put("item", "摊位费(" + newStall.getZone().getName() + ")");
                    item.put("amount", newStall.getZone().getBaseFee());
                }
            }
            return mapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new BizException("收费项目更新失败");
        }
    }

    private String calcEntryTime(int offsetMinutes) {
        int total = 6 * 60 + offsetMinutes;
        return String.format("%02d:%02d", total / 60, total % 60);
    }
}
