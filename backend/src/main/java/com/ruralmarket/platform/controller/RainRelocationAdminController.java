package com.ruralmarket.platform.controller;

import com.ruralmarket.platform.config.AuthUser;
import com.ruralmarket.platform.config.BizException;
import com.ruralmarket.platform.entity.Payment;
import com.ruralmarket.platform.entity.RainRelocation;
import com.ruralmarket.platform.entity.User;
import com.ruralmarket.platform.repository.PaymentRepository;
import com.ruralmarket.platform.repository.RainRelocationRepository;
import com.ruralmarket.platform.repository.UserRepository;
import com.ruralmarket.platform.service.AuditService;
import com.ruralmarket.platform.service.RainRelocationService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端-雨天临时调位：
 * 受影响摊位扫描、硬化路面候选位（消防通道距离/邻摊影响/收费差额）、
 * 发起调位、归档（新摊照片）、财务对账与应退差额核销。
 */
@RestController
@RequestMapping("/api/admin/rain-relocations")
public class RainRelocationAdminController {
    private final RainRelocationService rainService;
    private final RainRelocationRepository rainRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public RainRelocationAdminController(RainRelocationService rainService,
                                         RainRelocationRepository rainRepository,
                                         PaymentRepository paymentRepository,
                                         UserRepository userRepository, AuditService auditService) {
        this.rainService = rainService;
        this.rainRepository = rainRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    private User me() {
        return userRepository.findById(AuthUser.current().id()).orElseThrow();
    }

    /** 受影响摊位（低洼积水）清单 */
    @GetMapping("/affected")
    public List<Map<String, Object>> affected(@RequestParam Long eventId) {
        return rainService.scanAffected(eventId);
    }

    /** 某摊位可替代的硬化路面位置（含消防距离、邻摊影响、收费差额） */
    @GetMapping("/candidates")
    public List<Map<String, Object>> candidates(@RequestParam Long resultId) {
        return rainService.candidates(resultId, null);
    }

    /** 雨天调位单列表 */
    @GetMapping
    public List<RainRelocation> list(@RequestParam(required = false) Long eventId) {
        return eventId == null ? rainRepository.findAll()
                : rainRepository.findByEventIdOrderByCreatedAtDesc(eventId);
    }

    /** 发起雨天调位（通知摊主确认） */
    @PostMapping
    public RainRelocation propose(@RequestBody RainRelocationService.ProposeReq req) {
        return rainService.propose(req, me());
    }

    public record CompleteReq(@NotBlank String newStallPhotos) {}

    /** 摊主确认调位后，管理员上传新摊位照片归档并推送财务 */
    @PostMapping("/{id}/complete")
    public RainRelocation complete(@PathVariable Long id, @RequestBody CompleteReq req) {
        return rainService.complete(id, req.newStallPhotos(), me());
    }

    /** 财务视图：新旧摊位照片 + 摊主确认 + 收费差额/账单状态 */
    @GetMapping("/finance")
    public List<Map<String, Object>> finance(@RequestParam(required = false) Long eventId) {
        return rainService.financeView(eventId);
    }

    /** 财务核销「应退摊位费差额」：登记已退还摊主 */
    @PostMapping("/delta-payments/{paymentId}/refund")
    @Transactional
    public Payment refund(@PathVariable Long paymentId) {
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BizException("账单不存在"));
        if (!"FEE_REFUND".equals(p.getItemType())) throw new BizException("仅应退差额账单可核销退款");
        if ("REFUNDED".equals(p.getStatus())) throw new BizException("该退款已核销");
        p.setStatus("REFUNDED");
        p.setPaidAt(LocalDateTime.now());
        p.setRemark((p.getRemark() == null ? "" : p.getRemark() + "；") + "财务已退还摊主");
        Payment saved = paymentRepository.save(p);
        auditService.log(me().getId(), me().getName(), "RAIN_REFUND",
                "雨天调位应退差额已退还：摊主[" + p.getVendor().getName() + "] ¥" + p.getAmount());
        return saved;
    }
}
