package com.cabinet.repo;

import com.cabinet.domain.Alert;
import com.cabinet.domain.enums.AlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findByStatusOrderByIdDesc(AlertStatus status);
    List<Alert> findByCabinetIdOrderByIdDesc(Long cabinetId);
    List<Alert> findByBizTypeAndBizIdOrderByIdDesc(String bizType, Long bizId);
}
