package com.example.incomewallsimulator.repository;

import com.example.incomewallsimulator.entity.SimulationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SimulationHistoryRepository extends JpaRepository<SimulationHistory, Long> {
    List<SimulationHistory> findTop10ByOrderByCreatedAtDesc();
}
