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
 * 雨天临时调位服务。
 *
 * 赶集日清晨暴雨、低洼摊位积水时：
 * 1. 管理员扫描受影响摊位（低洼/未硬化路面），平台给出硬化路面候选位；
 * 2. 每个候选位展示：距消防通道距离、邻摊影响（周边摊主/品类/间距）、收费差额；
 * 3. 发起调位单（原因必填、积水照片）→ 摊主确认；
 * 4. 摊主同意：调换摊位、重新生成入场二维码、按新摊位费生成补收/应退差额账单，并写调整追溯；
 * 5. 管理员补传新摊位照片后归档，财务凭「新旧照片 + 摊主确认 + 收费差额」对账；
 * 6. 摊主拒绝：标记「放弃经营（取消分配）」或「保留原位、风险自担」。
 */
@Service
public class RainRelocationService {
    /** 邻摊影响判定半径（米） */
    public static final double NEIGHBOR_RADIUS_METERS = 5.0;
    /** 距消防通道安全提示阈值（米） */
    public static final double FIRE_LANE_SAFE_METERS = 3.0;
    private static final BigDecimal SANITATION_FEE = new BigDecimal("5");
    private static final BigDecimal POWER_FEE = new BigDecimal("10");

    private final RainRelocationRepository rainRepository;
    private final LotteryResultRepository resultRepository;
    private final StallRepository stallRepository;
    private final PaymentRepository paymentRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public RainRelocationService(RainRelocationRepository rainRepository,
                                 LotteryResultRepository resultRepository, StallRepository stallRepository,
                                 PaymentRepository paymentRepository, AdjustmentRepository adjustmentRepository,
                                 AuditService auditService, ObjectMapper mapper) {
        this.rainRepository = rainRepository;
        this.resultRepository = resultRepository;
        this.stallRepository = stallRepository;
        this.paymentRepository = paymentRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    // ================= 受影响摊位扫描 =================

    /** 受影响摊位：低洼（非硬化路面）摊位，以及已有雨天调位单的摊位（含最新单据状态） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> scanAffected(Long eventId) {
        List<LotteryResult> results = resultRepository.findByApplicationEventId(eventId).stream()
                .filter(r -> !"CANCELLED".equals(r.getStatus()))
                .toList();
        List<Map<String, Object>> out = new ArrayList<>();
        for (LotteryResult r : results) {
            Stall s = r.getStall();
            boolean lowLying = !Boolean.TRUE.equals(s.getHardened());
            RainRelocation latest = rainRepository.findFirstByResultIdOrderByIdDesc(r.getId()).orElse(null);
            String rainStatus = latest == null ? null : latest.getStatus();
            if (!lowLying && latest == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resultId", r.getId());
            m.put("vendor", r.getApplication().getVendor());
            m.put("category", r.getApplication().getCategory());
            m.put("stall", s);
            m.put("lowLying", lowLying);
            m.put("needsPower", r.getApplication().getNeedsPower());
            m.put("rainStatus", rainStatus);
            m.put("rainId", latest == null ? null : latest.getId());
            m.put("active", rainStatus == null || Set.of("ABANDONED", "RISK_SELF").contains(rainStatus));
            out.add(m);
        }
        return out;
    }

    // ================= 候选硬化路面 =================

    /** 为指定分配单计算可替代的硬化路面摊位（含消防距离、邻摊影响、收费差额） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidates(Long resultId, Long excludeRainId) {
        LotteryResult r = resultRepository.findById(resultId)
                .orElseThrow(() -> new BizException("分配单不存在"));
        Application app = r.getApplication();
        Long eventId = app.getEvent().getId();

        Set<Long> occupiedStallIds = new HashSet<>();
        for (LotteryResult x : resultRepository.findByApplicationEventId(eventId)) {
            if (!x.getId().equals(r.getId()) && !"CANCELLED".equals(x.getStatus())) {
                occupiedStallIds.add(x.getStall().getId());
            }
        }
        // 其他待摊主确认的雨天调位单已预定目标位
        for (RainRelocation rr : rainRepository.findByEventIdOrderByCreatedAtDesc(eventId)) {
            if (Set.of("PROPOSED", "CONFIRMED").contains(rr.getStatus())
                    && !rr.getResult().getId().equals(resultId)
                    && (excludeRainId == null || !rr.getId().equals(excludeRainId))) {
                occupiedStallIds.add(rr.getNewStall().getId());
            }
        }

        List<LotteryResult> neighbors = resultRepository.findByApplicationEventId(eventId).stream()
                .filter(x -> !x.getId().equals(r.getId()) && !"CANCELLED".equals(x.getStatus()))
                .toList();

        List<Map<String, Object>> list = new ArrayList<>();
        for (Stall s : stallRepository.findByActiveTrueOrderByStallNo()) {
            if (occupiedStallIds.contains(s.getId())) continue;
            if (!Boolean.TRUE.equals(s.getHardened())) continue;
            if (!compatible(app, s)) continue;

            List<Map<String, Object>> impact = neighborImpact(s, neighbors);
            BigDecimal oldFee = stallFee(r.getStall(), app);
            BigDecimal newFee = stallFee(s, app);
            double distance = fireLaneDistance(s);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stall", s);
            m.put("reservedEmergency", Boolean.TRUE.equals(s.getReservedEmergency()));
            m.put("fireLaneDistance", Math.round(distance * 10.0) / 10.0);
            m.put("fireLaneWarning", distance < FIRE_LANE_SAFE_METERS);
            m.put("neighbors", impact);
            m.put("neighborCount", impact.size());
            m.put("oldFee", oldFee);
            m.put("newFee", newFee);
            m.put("feeDelta", newFee.subtract(oldFee));
            list.add(m);
        }
        list.sort(Comparator.comparing(m -> ((Stall) ((Map<?, ?>) m).get("stall")).getStallNo()));
        return list;
    }

    /** 邻摊影响：候选位半径内已分配摊位（摊主、品类、间距） */
    private List<Map<String, Object>> neighborImpact(Stall candidate, List<LotteryResult> neighbors) {
        List<Map<String, Object>> impact = new ArrayList<>();
        if (candidate.getPosX() == null || candidate.getPosY() == null) return impact;
        for (LotteryResult x : neighbors) {
            Stall os = x.getStall();
            if (os.getPosX() == null || os.getPosY() == null) continue;
            double d = Math.hypot(candidate.getPosX() - os.getPosX(), candidate.getPosY() - os.getPosY());
            if (d <= NEIGHBOR_RADIUS_METERS) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("stallNo", os.getStallNo());
                n.put("vendorName", x.getApplication().getVendor().getName());
                n.put("category", Constants.categoryName(x.getApplication().getCategory()));
                n.put("distanceMeters", Math.round(d * 10.0) / 10.0);
                impact.add(n);
            }
        }
        impact.sort(Comparator.comparing(n -> ((Number) n.get("distanceMeters")).doubleValue()));
        return impact;
    }

    /** 候选位与报名约束是否兼容（同抽签规则：熟食冷链+电源/活禽隔离/用电/车长/品类区域） */
    private boolean compatible(Application app, Stall s) {
        StallZone z = s.getZone();
        if (Boolean.TRUE.equals(app.getSellsCookedFood())
                && (!Boolean.TRUE.equals(z.getAllowsCookedFood()) || !Boolean.TRUE.equals(s.getHasPower()))) {
            return false;
        }
        if (Boolean.TRUE.equals(app.getSellsLivePoultry()) && !Boolean.TRUE.equals(z.getAllowsLivePoultry())) {
            return false;
        }
        if (Boolean.TRUE.equals(app.getNeedsPower()) && !Boolean.TRUE.equals(s.getHasPower())) return false;
        if (app.getVehicleLength() != null && s.getMaxVehicleLength() != null
                && app.getVehicleLength() > s.getMaxVehicleLength()) return false;
        String allowed = z.getAllowedCategories();
        if (allowed != null && !allowed.isBlank() && !allowed.equals("*")) {
            if (!new HashSet<>(Arrays.asList(allowed.split(","))).contains(app.getCategory())) return false;
        }
        return true;
    }

    /** 摊位费合计 = 区域基础摊位费 +（用电摊）电费；卫生费各摊位一致不影响差额 */
    private BigDecimal stallFee(Stall s, Application app) {
        BigDecimal fee = s.getZone().getBaseFee();
        if (Boolean.TRUE.equals(app.getNeedsPower())) fee = fee.add(POWER_FEE);
        return fee;
    }

    /** 距消防通道距离：优先取摊位登记值，否则用平面坐标估算到消防通道（停用位）的最近距离 */
    private double fireLaneDistance(Stall s) {
        if (s.getFireLaneDistance() != null) return s.getFireLaneDistance();
        if (s.getPosX() != null && s.getPosY() != null) {
            double best = Double.MAX_VALUE;
            for (Stall f : stallRepository.findAll()) {
                if (Boolean.FALSE.equals(f.getActive()) && f.getPosX() != null && f.getPosY() != null) {
                    best = Math.min(best, Math.hypot(s.getPosX() - f.getPosX(), s.getPosY() - f.getPosY()));
                }
            }
            if (best != Double.MAX_VALUE) return best;
        }
        return Boolean.TRUE.equals(s.getNearFireLane()) ? 1.0 : 6.0;
    }

    // ================= 发起调位 =================

    public record ProposeReq(Long eventId, Long resultId, Long newStallId, String reason,
                             Integer floodDepthCm, String oldStallPhotos) {}

    @Transactional
    public RainRelocation propose(ProposeReq req, User operator) {
        if (req.reason() == null || req.reason().isBlank()) throw new BizException("调位原因必填（向摊主公开）");
        LotteryResult r = resultRepository.findById(req.resultId())
                .orElseThrow(() -> new BizException("分配单不存在"));
        if ("CANCELLED".equals(r.getStatus())) throw new BizException("该分配单已取消，无需调位");
        Stall target = stallRepository.findById(req.newStallId())
                .orElseThrow(() -> new BizException("目标摊位不存在"));
        if (!Boolean.TRUE.equals(target.getActive())) throw new BizException("目标摊位未启用");
        if (!Boolean.TRUE.equals(target.getHardened())) {
            throw new BizException("目标摊位 " + target.getStallNo() + " 不是硬化路面，雨天不能调入");
        }
        if (!compatible(r.getApplication(), target)) {
            throw new BizException("目标摊位与摊主经营条件不兼容（品类区域/电源/熟食/活禽/车长限制）");
        }
        if (rainRepository.existsByResultIdAndStatusIn(r.getId(), List.of("PROPOSED", "CONFIRMED", "COMPLETED"))) {
            throw new BizException("该摊位已有进行中或已完成的雨天调位单");
        }
        // 服务端重新校验候选位空闲（防止并发/前端篡改）
        for (Map<String, Object> c : candidates(r.getId(), null)) {
            Stall cs = (Stall) c.get("stall");
            if (cs.getId().equals(target.getId())) {
                RainRelocation rr = new RainRelocation();
                rr.setEvent(r.getApplication().getEvent());
                rr.setResult(r);
                rr.setVendor(r.getApplication().getVendor());
                rr.setOldStall(r.getStall());
                rr.setNewStall(target);
                rr.setOldStallNo(r.getStallNo());
                rr.setNewStallNo(target.getStallNo());
                rr.setStatus("PROPOSED");
                rr.setReason(req.reason());
                rr.setFloodDepthCm(req.floodDepthCm());
                rr.setOldStallPhotos(req.oldStallPhotos());
                rr.setFireLaneDistance(((Number) c.get("fireLaneDistance")).doubleValue());
                rr.setNeighborImpact(toJson(c.get("neighbors")));
                rr.setOldFee((BigDecimal) c.get("oldFee"));
                rr.setNewFee((BigDecimal) c.get("newFee"));
                rr.setFeeDelta((BigDecimal) c.get("feeDelta"));
                rr.setOperator(operator);
                RainRelocation saved = rainRepository.save(rr);
                auditService.log(operator.getId(), operator.getName(), "RAIN_PROPOSE",
                        "雨天调位单#" + saved.getId() + " 摊主[" + r.getApplication().getVendor().getName()
                                + "] " + rr.getOldStallNo() + "（积水）→ 候选 " + rr.getNewStallNo()
                                + "（硬化路面，距消防通道 " + rr.getFireLaneDistance() + " 米，差额 ¥"
                                + rr.getFeeDelta() + "），原因：" + req.reason());
                return saved;
            }
        }
        throw new BizException("目标摊位不可用（不兼容或已被占用）");
    }

    // ================= 摊主答复 =================

    public record RespondReq(Boolean accept, String decision, String note) {}

    @Transactional
    public RainRelocation respond(Long id, RespondReq req, User vendor) {
        RainRelocation rr = rainRepository.findById(id)
                .orElseThrow(() -> new BizException("调位单不存在"));
        if (!rr.getVendor().getId().equals(vendor.getId())) {
            throw new BizException("无权处理他人的调位单");
        }
        if (!"PROPOSED".equals(rr.getStatus())) throw new BizException("该调位单已处理，请勿重复操作");
        rr.setVendorNote(req.note());
        rr.setVendorRespondedAt(LocalDateTime.now());

        if (Boolean.TRUE.equals(req.accept())) {
            confirm(rr, vendor);
        } else {
            String decision = req.decision();
            if ("ABANDON".equals(decision)) {
                rr.setStatus("ABANDONED");
                rr.getResult().setStatus("CANCELLED");
                resultRepository.save(rr.getResult());
                // 放弃经营：未缴费用免缴（避免对已取消摊位误收费）；已缴款项由财务线下退还
                List<Payment> bills = paymentRepository.findByResultId(rr.getResult().getId());
                for (Payment p : bills) {
                    if ("UNPAID".equals(p.getStatus())) {
                        p.setStatus("WAIVED");
                        p.setRemark("雨天拒绝调位、放弃本场经营，未缴费用免缴");
                        paymentRepository.save(p);
                    }
                }
                Adjustment adj = writeAdjustment(rr, "RAIN_ABANDON",
                        "摊位 " + rr.getOldStallNo() + "（积水）",
                        "摊主拒绝调位，放弃本场经营，分配已取消，未缴费用免缴");
                rr.setAdjustmentId(adj.getId());
                auditService.log(vendor.getId(), vendor.getName(), "RAIN_REJECT_ABANDON",
                        "雨天调位单#" + rr.getId() + " 摊主[" + vendor.getName()
                                + "] 拒绝由 " + rr.getOldStallNo() + " 调往 " + rr.getNewStallNo()
                                + "，放弃经营（未缴账单已免缴）");
            } else if ("RISK_SELF".equals(decision)) {
                rr.setStatus("RISK_SELF");
                Adjustment adj = writeAdjustment(rr, "RAIN_RISK_SELF",
                        "摊位 " + rr.getOldStallNo() + "（积水）",
                        "摊主拒绝调位，保留原位经营，积水风险自担");
                rr.setAdjustmentId(adj.getId());
                auditService.log(vendor.getId(), vendor.getName(), "RAIN_REJECT_RISK",
                        "雨天调位单#" + rr.getId() + " 摊主[" + vendor.getName()
                                + "] 拒绝调位，保留 " + rr.getOldStallNo() + " 原位经营并风险自担");
            } else {
                throw new BizException("拒绝调位时必须选择：放弃经营 或 保留原位风险自担");
            }
        }
        return rainRepository.save(rr);
    }

    /** 摊主同意：换摊位、重生成二维码、生成差额账单、写调整追溯 */
    private void confirm(RainRelocation rr, User vendor) {
        LotteryResult result = rr.getResult();
        Stall target = rr.getNewStall();
        // 确认前再次校验目标位未被他人抢先占用
        boolean occupied = resultRepository.findByApplicationEventId(rr.getEvent().getId()).stream()
                .anyMatch(x -> !x.getId().equals(result.getId()) && !"CANCELLED".equals(x.getStatus())
                        && x.getStall().getId().equals(target.getId()));
        if (occupied) throw new BizException("目标摊位 " + target.getStallNo() + " 刚被占用，请联系管理员重新安排");

        String before = result.getStallNo();
        String oldToken = result.getQrToken();

        result.setStall(target);
        result.setStallNo(target.getStallNo());
        result.setEntryTime(calcEntryTime(target.getZone().getEntryOffsetMinutes()));
        result.setStatus("RELOCATED");
        result.setQrToken(UUID.randomUUID().toString().replace("-", ""));
        // 按新摊位重建收费项目快照
        Application app = result.getApplication();
        List<Map<String, Object>> feeItems = new ArrayList<>();
        feeItems.add(Map.of("type", "STALL_FEE", "item", "摊位费(" + target.getZone().getName() + ")",
                "amount", target.getZone().getBaseFee()));
        feeItems.add(Map.of("type", "SANITATION", "item", "卫生费", "amount", SANITATION_FEE));
        if (Boolean.TRUE.equals(app.getNeedsPower())) {
            feeItems.add(Map.of("type", "POWER", "item", "电费", "amount", POWER_FEE));
        }
        result.setFeeItems(toJson(feeItems));
        resultRepository.save(result);

        // 收费差额账单（正数补收摊主缴纳；负数登记应退，由财务处理）
        BigDecimal delta = rr.getFeeDelta();
        if (delta.signum() != 0) {
            Payment p = new Payment();
            p.setEvent(rr.getEvent());
            p.setResult(result);
            p.setVendor(vendor);
            boolean surcharge = delta.signum() > 0;
            p.setItemType(surcharge ? "FEE_DIFF" : "FEE_REFUND");
            p.setItemName(surcharge ? "雨天调位补收摊位费差额" : "雨天调位应退摊位费差额");
            p.setAmount(delta.abs());
            p.setStatus("UNPAID");
            p.setRemark("雨天调位：" + rr.getOldStallNo() + " → " + rr.getNewStallNo()
                    + "，原摊位费 ¥" + rr.getOldFee() + "，新摊位费 ¥" + rr.getNewFee());
            paymentRepository.save(p);
            rr.setDeltaPaymentId(p.getId());
        }

        rr.setStatus("CONFIRMED");
        Adjustment adj = writeAdjustment(rr, "RAIN_RELOCATE",
                "摊位 " + before + "（积水，摊位费 ¥" + rr.getOldFee() + "）",
                "摊位 " + target.getStallNo() + "（硬化路面，摊位费 ¥" + rr.getNewFee()
                        + "，差额 ¥" + delta + "，入场二维码已重新生成）");
        rr.setAdjustmentId(adj.getId());

        auditService.log(vendor.getId(), vendor.getName(), "RAIN_CONFIRM",
                "雨天调位单#" + rr.getId() + " 摊主[" + vendor.getName() + "] 确认调位 "
                        + before + " → " + target.getStallNo() + "，二维码已重生成（旧令牌 "
                        + oldToken.substring(0, 8) + "… 作废），收费差额 ¥" + delta);
    }

    private Adjustment writeAdjustment(RainRelocation rr, String type, String before, String after) {
        Adjustment adj = new Adjustment();
        adj.setEvent(rr.getEvent());
        adj.setResult(rr.getResult());
        adj.setVendor(rr.getVendor());
        adj.setType(type);
        adj.setBeforeValue(before);
        adj.setAfterValue(after);
        adj.setReason(rr.getReason());
        adj.setSource("RAIN");
        adj.setSourceRefId(rr.getId());
        adj.setOperator(rr.getOperator());
        return adjustmentRepository.save(adj);
    }

    // ================= 调位完成归档 =================

    @Transactional
    public RainRelocation complete(Long id, String newStallPhotos, User operator) {
        RainRelocation rr = rainRepository.findById(id)
                .orElseThrow(() -> new BizException("调位单不存在"));
        if (!"CONFIRMED".equals(rr.getStatus())) {
            throw new BizException("仅摊主已确认的调位单可归档（当前：" + rr.getStatus() + "）");
        }
        if (newStallPhotos == null || newStallPhotos.isBlank()) {
            throw new BizException("请上传新摊位（硬化路面）布置照片后再归档");
        }
        rr.setNewStallPhotos(newStallPhotos);
        rr.setStatus("COMPLETED");
        rr.setCompletedAt(LocalDateTime.now());
        RainRelocation saved = rainRepository.save(rr);
        auditService.log(operator.getId(), operator.getName(), "RAIN_COMPLETE",
                "雨天调位单#" + rr.getId() + " 摊主[" + rr.getVendor().getName() + "] "
                        + rr.getOldStallNo() + " → " + rr.getNewStallNo() + " 已上传新摊位照片归档，推送财务对账");
        return saved;
    }

    // ================= 财务视图 =================

    /** 财务对账：已确认/已完成的雨天调位——新旧照片、摊主确认、收费差额及账单状态 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> financeView(Long eventId) {
        List<RainRelocation> list = eventId == null ? rainRepository.findAll()
                : rainRepository.findByEventIdOrderByCreatedAtDesc(eventId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (RainRelocation rr : list) {
            if (!Set.of("CONFIRMED", "COMPLETED").contains(rr.getStatus())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("relocation", rr);
            m.put("deltaPayment", rr.getDeltaPaymentId() == null ? null
                    : paymentRepository.findById(rr.getDeltaPaymentId()).orElse(null));
            out.add(m);
        }
        return out;
    }

    private String calcEntryTime(int offsetMinutes) {
        int total = 6 * 60 + offsetMinutes;
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new BizException("快照序列化失败: " + e.getMessage());
        }
    }
}
