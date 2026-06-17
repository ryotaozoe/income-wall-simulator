package com.example.incomewallsimulator.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "simulation_history")
@Data
public class SimulationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int annualIncome;
    private boolean isSpecificDependent;
    private boolean isSubjectToSocialInsurance;
    private boolean parentIsEmployee;

    private int takeHomeIncome;
    private int incomeTax;
    private int residentTax;
    private int socialInsurancePremium;
    private boolean canBeDependent;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
