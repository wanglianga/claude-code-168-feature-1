package com.ruralmarket.platform.controller;

import com.ruralmarket.platform.config.AuthUser;
import com.ruralmarket.platform.config.BizException;
import com.ruralmarket.platform.entity.*;
import com.ruralmarket.platform.repository.*;
import com.ruralmarket.platform.service.Constants;
import com.ruralmarket.platform.service.RainRelocationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 摊主端：报名、抽签结果、缴费、留样、信用、申诉 */
@RestController
@RequestMapping("/api/vendor")
public class VendorController {
    private final ApplicationRepository applicationRepository;
    private final MarketEventRepository eventRepository;
    private final LotteryResultRepository resultRepository;
    private final PaymentRepository paymentRepository;
    private final FoodSampleRepository sampleRepository;
    private final CreditLogRepository creditLogRepository;
    private final AdjustmentRepository adjustmentRepository;
    private final ComplaintRepository complaintRepository;
    private final UserRepository userRepository;
    private final RainRelocationRepository rainRelocationRepository;
    private final RainRelocationService rainRelocationService;

    public VendorController(ApplicationRepository applicationRepository, MarketEventRepository eventRepository,
                            LotteryResultRepository resultRepository, PaymentRepository paymentRepository,
                            FoodSampleRepository sampleRepository, CreditLogRepository creditLogRepository,
                            AdjustmentRepository adjustmentRepository, ComplaintRepository complaintRepository,
                            UserRepository userRepository, RainRelocationRepository rainRelocationRepository,
                            RainRelocationService rainRelocationService) {
        this.applicationRepository = applicationRepository;
        this.eventRepository = eventRepository;
        this.resultRepository = resultRepository;
        this.paymentRepository = paymentRepository;
        this.sampleRepository = sampleRepository;
        this.creditLogRepository = creditLogRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.complaintRepository = complaintRepository;
        this.userRepository = userRepository;
        this.rainRelocationRepository = rainRelocationRepository;
        this.rainRelocationService = rainRelocationService;
    }

    private User me() {
        return userRepository.findById(AuthUser.current().id()).orElseThrow();
    }

    // ===== 报名 =====
    public record ApplyReq(@NotNull Long eventId, @NotBlank String category,
                           Double vehicleLength, Double vehicleWidth,
                           Boolean needsPower, Boolean sellsCookedFood, Boolean sellsLivePoultry,
                           String licensePhotos, String historyRecord) {}

    @PostMapping("/applications")
    @Transactional
    public Application apply(@RequestBody ApplyReq req) {
        User vendor = me();
        MarketEvent event = eventRepository.findById(req.eventId())
                .orElseThrow(() -> new BizException("集市不存在"));
        if (!"OPEN".equals(event.getStatus())) throw new BizException("该集市当前不在报名期");
        if (!Constants.CATEGORIES.containsKey(req.category())) throw new BizException("经营品类不合法");
        applicationRepository.findByEventIdAndVendorId(event.getId(), vendor.getId())
                .ifPresent(a -> { throw new BizException("您已提交过该集市的报名"); });

        Application app = new Application();
        app.setEvent(event);
        app.setVendor(vendor);
        app.setCategory(req.category());
        app.setVehicleLength(req.vehicleLength());
        app.setVehicleWidth(req.vehicleWidth());
        app.setNeedsPower(Boolean.TRUE.equals(req.needsPower()));
        app.setSellsCookedFood(Boolean.TRUE.equals(req.sellsCookedFood()));
        app.setSellsLivePoultry(Boolean.TRUE.equals(req.sellsLivePoultry()));
        app.setLicensePhotos(req.licensePhotos());
        app.setHistoryRecord(req.historyRecord());
        app.setVeteran(Constants.isVeteran(vendor.getHistoryCount(), vendor.getCreditScore()));
        return applicationRepository.save(app);
    }

    @GetMapping("/applications")
    public List<Application> myApplications() {
        return applicationRepository.findByVendorIdOrderByCreatedAtDesc(me().getId());
    }

    // ===== 抽签结果 =====
    @GetMapping("/results")
    public List<LotteryResult> myResults() {
        Long vid = me().getId();
        return resultRepository.findAll().stream()
                .filter(r -> r.getApplication().getVendor().getId().equals(vid))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    @GetMapping("/results/{id}")
    public LotteryResult resultDetail(@PathVariable Long id) {
        LotteryResult r = resultRepository.findById(id).orElseThrow(() -> new BizException("结果不存在"));
        if (!r.getApplication().getVendor().getId().equals(me().getId())) {
            throw new BizException("无权查看他人抽签结果");
        }
        return r;
    }

    // ===== 缴费 =====
    @GetMapping("/payments")
    public List<Payment> myPayments() {
        return paymentRepository.findByVendorIdOrderByCreatedAtDesc(me().getId());
    }

    @PostMapping("/payments/{id}/pay")
    @Transactional
    public Payment pay(@PathVariable Long id) {
        Payment p = paymentRepository.findById(id).orElseThrow(() -> new BizException("账单不存在"));
        if (!p.getVendor().getId().equals(me().getId())) throw new BizException("无权缴纳他人账单");
        if (!"UNPAID".equals(p.getStatus())) throw new BizException("该账单当前状态不可缴费");
        p.setStatus("PAID");
        p.setPaidAt(LocalDateTime.now());
        return paymentRepository.save(p);
    }

    // ===== 食品留样 =====
    public record SampleReq(@NotNull Long eventId, Long resultId, @NotBlank String productName,
                            @NotBlank String batchNo, @NotBlank String photo,
                            @NotBlank String storageLocation, String remark) {}

    @PostMapping("/samples")
    @Transactional
    public FoodSample addSample(@RequestBody SampleReq req) {
        User vendor = me();
        MarketEvent event = eventRepository.findById(req.eventId())
                .orElseThrow(() -> new BizException("集市不存在"));
        FoodSample s = new FoodSample();
        s.setEvent(event);
        s.setVendor(vendor);
        if (req.resultId() != null) {
            resultRepository.findById(req.resultId()).ifPresent(s::setResult);
        }
        s.setProductName(req.productName());
        s.setBatchNo(req.batchNo());
        s.setPhoto(req.photo());
        s.setStorageLocation(req.storageLocation());
        s.setRemark(req.remark());
        return sampleRepository.save(s);
    }

    @GetMapping("/samples")
    public List<FoodSample> mySamples() {
        return sampleRepository.findByVendorIdOrderByRetainedAtDesc(me().getId());
    }

    // ===== 信用与追溯 =====
    @GetMapping("/credit-logs")
    public List<CreditLog> myCreditLogs() {
        return creditLogRepository.findByVendorIdOrderByCreatedAtDesc(me().getId());
    }

    @GetMapping("/adjustments")
    public List<Adjustment> myAdjustments() {
        return adjustmentRepository.findByVendorIdOrderByCreatedAtDesc(me().getId());
    }

    @GetMapping("/complaints")
    public List<Complaint> myComplaints() {
        return complaintRepository.findByVendorIdOrderByCreatedAtDesc(me().getId());
    }

    /** 摊主申诉（费用争议/保证金扣罚争议/边界纠纷等） */
    public record AppealReq(@NotNull Long eventId, Long resultId, @NotBlank String type,
                            @NotBlank String description) {}

    @PostMapping("/complaints")
    @Transactional
    public Complaint appeal(@RequestBody AppealReq req) {
        User vendor = me();
        MarketEvent event = eventRepository.findById(req.eventId())
                .orElseThrow(() -> new BizException("集市不存在"));
        Complaint c = new Complaint();
        c.setEvent(event);
        c.setVendor(vendor);
        if (req.resultId() != null) {
            resultRepository.findById(req.resultId()).ifPresent(c::setResult);
        }
        c.setReporterType("VENDOR");
        c.setReporterName(vendor.getName());
        c.setReporterPhone(vendor.getPhone());
        c.setType(req.type());
        c.setDescription(req.description());
        return complaintRepository.save(c);
    }

    // ===== 雨天临时调位确认 =====
    @GetMapping("/rain-relocations")
    public List<RainRelocation> myRainRelocations() {
        return rainRelocationRepository.findByVendorIdOrderByCreatedAtDesc(me().getId());
    }

    @GetMapping("/rain-relocations/{id}")
    public RainRelocation rainDetail(@PathVariable Long id) {
        RainRelocation rr = rainRelocationRepository.findById(id)
                .orElseThrow(() -> new BizException("调位单不存在"));
        if (!rr.getVendor().getId().equals(me().getId())) throw new BizException("无权查看他人调位单");
        return rr;
    }

    /**
     * 摊主答复雨天调位：
     * accept=true 同意（换摊位、重生成二维码、生成差额账单）；
     * accept=false 时 decision 必填：ABANDON 放弃经营 / RISK_SELF 保留原位风险自担。
     */
    @PostMapping("/rain-relocations/{id}/respond")
    public RainRelocation respondRain(@PathVariable Long id,
                                      @RequestBody RainRelocationService.RespondReq req) {
        return rainRelocationService.respond(id, req, me());
    }
}
