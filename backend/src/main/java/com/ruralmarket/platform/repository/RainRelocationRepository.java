package com.ruralmarket.platform.repository;

import com.ruralmarket.platform.entity.RainRelocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RainRelocationRepository extends JpaRepository<RainRelocation, Long> {
    List<RainRelocation> findByEventIdOrderByCreatedAtDesc(Long eventId);
    List<RainRelocation> findByVendorIdOrderByCreatedAtDesc(Long vendorId);
    List<RainRelocation> findByResultIdOrderByCreatedAtDesc(Long resultId);
}
