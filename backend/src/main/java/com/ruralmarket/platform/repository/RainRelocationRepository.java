package com.ruralmarket.platform.repository;

import com.ruralmarket.platform.entity.RainRelocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface RainRelocationRepository extends JpaRepository<RainRelocation, Long> {
    List<RainRelocation> findByEventIdOrderByCreatedAtDesc(Long eventId);
    List<RainRelocation> findByVendorIdOrderByCreatedAtDesc(Long vendorId);
    Optional<RainRelocation> findFirstByResultIdOrderByIdDesc(Long resultId);
    List<RainRelocation> findByResultIdOrderByIdDesc(Long resultId);
    boolean existsByResultIdAndStatusIn(Long resultId, List<String> statuses);
}
