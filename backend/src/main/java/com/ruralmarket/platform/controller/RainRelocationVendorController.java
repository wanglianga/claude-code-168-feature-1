package com.ruralmarket.platform.controller;

import com.ruralmarket.platform.config.AuthUser;
import com.ruralmarket.platform.entity.RainRelocation;
import com.ruralmarket.platform.entity.User;
import com.ruralmarket.platform.repository.RainRelocationRepository;
import com.ruralmarket.platform.repository.UserRepository;
import com.ruralmarket.platform.service.RainRelocationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 摊主端-雨天调位通知：查看、确认（换发新入场码+差额单）、拒绝 */
@RestController
@RequestMapping("/api/vendor/rain-relocations")
public class RainRelocationVendorController {
    private final RainRelocationService relocationService;
    private final RainRelocationRepository relocationRepository;
    private final UserRepository userRepository;

    public RainRelocationVendorController(RainRelocationService relocationService,
                                          RainRelocationRepository relocationRepository,
                                          UserRepository userRepository) {
        this.relocationService = relocationService;
        this.relocationRepository = relocationRepository;
        this.userRepository = userRepository;
    }

    private User me() {
        return userRepository.findById(AuthUser.current().id()).orElseThrow();
    }

    @GetMapping
    public List<RainRelocation> myTickets() {
        return relocationRepository.findByVendorIdOrderByCreatedAtDesc(me().getId());
    }

    public record NoteReq(String note) {}

    /** 确认调位：重新生成入场二维码并核算收费差额 */
    @PostMapping("/{id}/confirm")
    public RainRelocation confirm(@PathVariable Long id, @RequestBody(required = false) NoteReq req) {
        return relocationService.confirm(id, me(), req == null ? null : req.note());
    }

    /** 拒绝调位（必填说明，之后由管理员标记放弃经营或保留原位风险自担） */
    @PostMapping("/{id}/reject")
    public RainRelocation reject(@PathVariable Long id, @RequestBody NoteReq req) {
        return relocationService.reject(id, me(), req == null ? null : req.note());
    }
}
