package com.asia.saas.repository;

import com.asia.saas.entity.Device;
import com.asia.saas.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {
    int countByUser(User user);
    Optional<Device> findByApiKey(String apiKey);
    boolean existsByApiKey(String apiKey);
    List<Device> findByUserOrderByCreatedAtDesc(User user);
}

