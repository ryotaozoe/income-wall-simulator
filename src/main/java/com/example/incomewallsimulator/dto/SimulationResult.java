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

    // 親が満額の控除を受けられるか（社会保険の被扶養も維持）
    private boolean canBeDependent;

    // 扶養に留まる（親が満額控除を受けられる）ための推奨上限年収
    private int recommendedMaxIncome;

    // 親が実際に受けられる控除額（所得税・段階的）
    private int parentDeduction;

    // 親が受けられる控除の満額（特定63万／一般38万）
    private int parentMaxDeduction;

    // 親が失う控除額（満額からの減少分）
    private int parentLostDeduction;

    // 親の追加税負担（概算）
    private int parentAdditionalTax;

    // 扶養の状態ラベル（満額／段階的減額／なし）
    private String dependentStatus;

    // アドバイスメッセージ
    private String advice;
}
