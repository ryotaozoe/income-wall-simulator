package com.example.incomewallsimulator.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SimulationRequest {

    @NotNull(message = "年収見込みを入力してください")
    @Min(value = 0, message = "0以上の値を入力してください")
    @Max(value = 10000000, message = "1000万円以下の値を入力してください")
    private Integer annualIncome;

    // 19〜22歳かどうか（特定扶養親族控除の対象）
    @NotNull
    private Boolean isSpecificDependent;

    // 週20時間以上・月額賃金8.8万円以上・従業員51人以上企業 → 社会保険加入義務あり
    @NotNull
    private Boolean isSubjectToSocialInsurance;

    // 親が会社員（社会保険の被扶養者かどうかに関係）
    @NotNull
    private Boolean parentIsEmployee;

    // 学生かどうか（勤労学生控除の対象）
    @NotNull
    private Boolean isStudent;
}
