package com.ruralmarket.platform.controller;

import com.ruralmarket.platform.config.AuthUser;
import com.ruralmarket.platform.config.BizException;
import com.ruralmarket.platform.entity.RainRelocation;
import com.ruralmarket.platform.entity.User;
import com.ruralmarket.platform.repository.RainRelocationRepository;
import com.ruralmarket.platform.repository.UserRepository;
import com.ruralmarket.platform.service.RainRelocationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 管理端-雨天临时调位：受影响摊位、发起调位、拒绝单处理、财务核对 */
@RestController
@RequestMapping("/api/admin/rain-relocations")
public class RainRelocationAdminController {
    private final RainRelocationService relocationService;
    private final RainRelocationRepository relocationRepository;
    private final UserRepository userRepository;

    public RainRelocationAdminController(RainRelocationService relocationService,
                                         RainRelocationRepository relocationRepository,
                                         UserRepository userRepository) {
        this.relocationService = relocationService;
        this.relocationRepository = relocationRepository;
        this.userRepository = userRepository;
    }

    private User me() {
        return userRepository.findById(AuthUser.current().id()).orElseThrow();
    }

    /** 受影响摊位（低洼积水）及其可替代位置（含消防通道距离、邻摊影响） */
    @GetMapping("/events/{eventId}/affected")
    public List<Map<String, Object>> affected(@PathVariable Long eventId) {
        return relocationService.affected(eventId);
    }

    /** 调位单列表 */
    @GetMapping
    public List<RainRelocation> list(@RequestParam(required = false) Long eventId) {
        if (eventId != null) return relocationRepository.findByEventIdOrderByCreatedAtDesc(eventId);
        return relocationRepository.findAll();
    }

    public record CreateReq(@NotNull Long resultId, @NotNull Long newStallId, @NotBlank String reason,
                            String oldStallPhoto, String newStallPhoto) {}

    /** 发起雨天调位（生成待摊主确认工单） */
    @PostMapping
    public RainRelocation create(@RequestBody CreateReq req) {
        return relocationService.create(req.resultId(), req.newStallId(), req.reason(),
                req.oldStallPhoto(), req.newStallPhoto(), me());
    }

    public record ResolveReq(@NotBlank String action, @NotBlank String note) {}

    /** 摊主拒绝后处理：ABANDONED 放弃经营 / KEEP_ORIGINAL 保留原位风险自担 */
    @PostMapping("/{id}/resolve")
    public RainRelocation resolve(@PathVariable Long id, @RequestBody ResolveReq req) {
        return relocationService.resolve(id, req.action(), req.note(), me());
    }

    /** 财务核对：已确认调位的新旧照片、摊主确认、收费差额 */
    @GetMapping("/finance")
    public List<Map<String, Object>> finance(@RequestParam Long eventId) {
        return relocationService.finance(eventId);
    }

    /** 财务标记差额已退还（负差额） */
    @PostMapping("/{id}/settle")
    public RainRelocation settle(@PathVariable Long id) {
        return relocationService.settle(id, me());
    }
}
