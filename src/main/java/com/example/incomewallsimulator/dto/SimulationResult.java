package com.example.incomewallsimulator.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SimulationResult {

    private int annualIncome;

    // 手取り
    private int takeHomeIncome;

    // 所得税
    private int incomeTax;

    // 住民税（翌年課税）
    private int residentTax;

    // 社会保険料（本人負担分）
    private int socialInsurancePremium;

    // 各壁のステータス
    private List<WallStatus> wallStatuses;

    // 扶養に入れるか
    private boolean canBeDependent;

    // 扶養に留まるための推奨上限年収
    private int recommendedMaxIncome;

    // 親が失う控除額（扶養を外れた場合）
    private int parentLostDeduction;

    // 親の追加税負担（扶養を外れた場合）
    private int parentAdditionalTax;

    // アドバイスメッセージ
    private String advice;
}
