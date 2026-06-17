package com.example.incomewallsimulator.service;

import com.example.incomewallsimulator.dto.SimulationRequest;
import com.example.incomewallsimulator.dto.SimulationResult;
import com.example.incomewallsimulator.dto.WallStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 2025年税制改正対応の年収の壁シミュレーション計算ロジック
 *
 * 主な2025年改正ポイント:
 * - 基礎控除: 48万円 → 58万円
 * - 給与所得控除最低額: 55万円 → 65万円
 * - 所得税非課税ライン(103万円の壁): 103万円 → 123万円
 * - 特定扶養親族控除(19〜22歳): 63万円 → 150万円未満まで維持（控除額は変わらず）
 */
@Service
public class TaxCalculationService {

    // 2025年改正後の基礎控除
    private static final int BASIC_DEDUCTION = 580_000;
    // 2025年改正後の給与所得控除最低額
    private static final int EMPLOYMENT_INCOME_DEDUCTION_MIN = 650_000;

    // 各種「壁」の閾値（2025年改正後）
    private static final int WALL_INCOME_TAX = 1_230_000;       // 所得税の壁（103万→123万）
    private static final int WALL_SOCIAL_INSURANCE_SMALL = 1_060_000; // 社会保険の壁（中小企業向け要件緩和後も概ね106万）
    private static final int WALL_SOCIAL_INSURANCE_LARGE = 1_300_000; // 社会保険の壁（大企業・130万）
    private static final int WALL_SPECIFIC_DEPENDENT = 1_500_000; // 特定扶養親族控除の壁（19〜22歳）
    private static final int WALL_SPOUSE_SPECIAL = 2_010_000;    // 配偶者特別控除の壁

    // 特定扶養親族控除額（19〜22歳）
    private static final int SPECIFIC_DEPENDENT_DEDUCTION = 630_000;
    // 一般扶養親族控除額（16〜18歳、23〜69歳）
    private static final int GENERAL_DEPENDENT_DEDUCTION = 380_000;

    // 社会保険料率（本人負担分・概算）
    private static final double HEALTH_INSURANCE_RATE = 0.0500; // 健康保険 約5%
    private static final double PENSION_RATE = 0.0915;           // 厚生年金 約9.15%

    public SimulationResult simulate(SimulationRequest req) {
        int income = req.getAnnualIncome();
        boolean isSpecific = req.getIsSpecificDependent();
        boolean isSubjectToSI = req.getIsSubjectToSocialInsurance();
        boolean parentIsEmployee = req.getParentIsEmployee();

        List<WallStatus> walls = buildWallStatuses(income, isSpecific, isSubjectToSI);

        int socialInsurancePremium = calcSocialInsurancePremium(income, isSubjectToSI);
        int taxableIncome = calcTaxableIncome(income, socialInsurancePremium);
        int incomeTax = calcIncomeTax(taxableIncome);
        int residentTax = calcResidentTax(taxableIncome);
        int takeHome = income - socialInsurancePremium - incomeTax - residentTax;

        boolean canBeDependent = determineDependentStatus(income, isSpecific, isSubjectToSI, parentIsEmployee);
        int recommendedMax = calcRecommendedMax(isSpecific, isSubjectToSI, parentIsEmployee);

        int parentDeduction = isSpecific ? SPECIFIC_DEPENDENT_DEDUCTION : GENERAL_DEPENDENT_DEDUCTION;
        int parentLostDeduction = canBeDependent ? 0 : parentDeduction;
        // 親の所得税率を仮に20%で計算（一般的な会社員の所得税率）
        int parentAdditionalTax = (int) (parentLostDeduction * 0.20);

        String advice = buildAdvice(income, canBeDependent, recommendedMax, isSpecific, isSubjectToSI);

        return SimulationResult.builder()
                .annualIncome(income)
                .takeHomeIncome(Math.max(takeHome, 0))
                .incomeTax(incomeTax)
                .residentTax(residentTax)
                .socialInsurancePremium(socialInsurancePremium)
                .wallStatuses(walls)
                .canBeDependent(canBeDependent)
                .recommendedMaxIncome(recommendedMax)
                .parentLostDeduction(parentLostDeduction)
                .parentAdditionalTax(parentAdditionalTax)
                .advice(advice)
                .build();
    }

    private List<WallStatus> buildWallStatuses(int income, boolean isSpecific, boolean isSubjectToSI) {
        List<WallStatus> walls = new ArrayList<>();

        walls.add(WallStatus.builder()
                .name("123万円の壁（所得税）")
                .threshold(WALL_INCOME_TAX)
                .exceeded(income >= WALL_INCOME_TAX)
                .description("2025年改正で103万円→123万円に引き上げ。この金額を超えると所得税が発生します。")
                .consequence("所得税の納税義務が発生します")
                .build());

        if (isSubjectToSI) {
            walls.add(WallStatus.builder()
                    .name("106万円の壁（社会保険）")
                    .threshold(WALL_SOCIAL_INSURANCE_SMALL)
                    .exceeded(income >= WALL_SOCIAL_INSURANCE_SMALL)
                    .description("週20時間以上・月額賃金8.8万円以上・51人以上企業に該当する場合、社会保険に加入義務が生じます。")
                    .consequence("健康保険・厚生年金の保険料負担が発生（年間約15〜20万円）")
                    .build());
        } else {
            walls.add(WallStatus.builder()
                    .name("130万円の壁（社会保険）")
                    .threshold(WALL_SOCIAL_INSURANCE_LARGE)
                    .exceeded(income >= WALL_SOCIAL_INSURANCE_LARGE)
                    .description("親の健康保険の被扶養者でいられる上限。これを超えると自分で国民健康保険・国民年金に加入する必要があります。")
                    .consequence("国民健康保険・国民年金の保険料負担が発生（年間約20〜30万円）")
                    .build());
        }

        if (isSpecific) {
            walls.add(WallStatus.builder()
                    .name("150万円の壁（特定扶養親族控除）")
                    .threshold(WALL_SPECIFIC_DEPENDENT)
                    .exceeded(income >= WALL_SPECIFIC_DEPENDENT)
                    .description("19〜22歳の特定扶養親族として、親が63万円の控除を受けられる上限です（2025年改正で新設）。")
                    .consequence("親の扶養控除（63万円）が消滅し、親の税負担が年間約12.6万円増加")
                    .build());
        } else {
            walls.add(WallStatus.builder()
                    .name("103万円の壁（一般扶養控除）")
                    .threshold(1_030_000)
                    .exceeded(income >= 1_030_000)
                    .description("16〜18歳・23〜69歳の一般扶養親族として、親が38万円の控除を受けられる上限（2025年以降も変更なし）。")
                    .consequence("親の扶養控除（38万円）が消滅し、親の税負担が年間約7.6万円増加")
                    .build());
        }

        return walls;
    }

    private int calcSocialInsurancePremium(int income, boolean isSubjectToSI) {
        if (isSubjectToSI && income >= WALL_SOCIAL_INSURANCE_SMALL) {
            // 厚生年金・健康保険（本人負担分）
            return (int) (income * (HEALTH_INSURANCE_RATE + PENSION_RATE));
        } else if (!isSubjectToSI && income >= WALL_SOCIAL_INSURANCE_LARGE) {
            // 国民健康保険・国民年金（概算）
            int nationalHealthInsurance = Math.min((int) (income * 0.08), 830_000);
            int nationalPension = 203_760; // 2025年の国民年金保険料（年額）
            return nationalHealthInsurance + nationalPension;
        }
        return 0;
    }

    private int calcTaxableIncome(int income, int socialInsurancePremium) {
        // 給与所得控除の計算（2025年改正後）
        int employmentIncomeDeduction = calcEmploymentIncomeDeduction(income);
        // 給与所得 = 収入 - 給与所得控除
        int employmentIncome = income - employmentIncomeDeduction;
        // 課税所得 = 給与所得 - 社会保険料控除 - 基礎控除
        int taxableIncome = employmentIncome - socialInsurancePremium - BASIC_DEDUCTION;
        return Math.max(taxableIncome, 0);
    }

    private int calcEmploymentIncomeDeduction(int income) {
        // 2025年改正後の給与所得控除額
        if (income <= 1_625_000) {
            return EMPLOYMENT_INCOME_DEDUCTION_MIN; // 65万円（最低額）
        } else if (income <= 1_800_000) {
            return (int) (income * 0.40) - 100_000;
        } else if (income <= 3_600_000) {
            return (int) (income * 0.30) + 80_000;
        } else if (income <= 6_600_000) {
            return (int) (income * 0.20) + 440_000;
        } else if (income <= 8_500_000) {
            return (int) (income * 0.10) + 1_100_000;
        } else {
            return 1_950_000;
        }
    }

    private int calcIncomeTax(int taxableIncome) {
        // 所得税の累進課税（超過累進税率）
        if (taxableIncome <= 0) return 0;
        if (taxableIncome <= 1_950_000) return (int) (taxableIncome * 0.05);
        if (taxableIncome <= 3_300_000) return (int) (taxableIncome * 0.10) - 97_500;
        if (taxableIncome <= 6_950_000) return (int) (taxableIncome * 0.20) - 427_500;
        if (taxableIncome <= 9_000_000) return (int) (taxableIncome * 0.23) - 636_000;
        if (taxableIncome <= 18_000_000) return (int) (taxableIncome * 0.33) - 1_536_000;
        if (taxableIncome <= 40_000_000) return (int) (taxableIncome * 0.40) - 2_796_000;
        return (int) (taxableIncome * 0.45) - 4_796_000;
    }

    private int calcResidentTax(int taxableIncome) {
        // 住民税：所得割10% + 均等割5,000円（翌年課税）
        if (taxableIncome <= 0) return 0;
        return (int) (taxableIncome * 0.10) + 5_000;
    }

    private boolean determineDependentStatus(int income, boolean isSpecific, boolean isSubjectToSI, boolean parentIsEmployee) {
        // 税法上の扶養（所得税）の判定
        if (isSpecific) {
            // 特定扶養親族（19〜22歳）は2025年改正で150万円未満
            if (income >= WALL_SPECIFIC_DEPENDENT) return false;
        } else {
            // 一般扶養は103万円未満（2025年以降も変更なし）
            if (income >= 1_030_000) return false;
        }

        // 社会保険の被扶養者判定（親が会社員の場合のみ関係）
        if (parentIsEmployee) {
            int siWall = isSubjectToSI ? WALL_SOCIAL_INSURANCE_SMALL : WALL_SOCIAL_INSURANCE_LARGE;
            if (income >= siWall) return false;
        }

        return true;
    }

    private int calcRecommendedMax(boolean isSpecific, boolean isSubjectToSI, boolean parentIsEmployee) {
        int taxWall = isSpecific ? WALL_SPECIFIC_DEPENDENT - 1 : 1_030_000 - 1;
        if (!parentIsEmployee) return taxWall;
        int siWall = (isSubjectToSI ? WALL_SOCIAL_INSURANCE_SMALL : WALL_SOCIAL_INSURANCE_LARGE) - 1;
        return Math.min(taxWall, siWall);
    }

    private String buildAdvice(int income, boolean canBeDependent, int recommendedMax, boolean isSpecific, boolean isSubjectToSI) {
        if (canBeDependent) {
            int margin = recommendedMax - income;
            return String.format(
                "現在の年収は扶養範囲内です。あと %,d 円まで稼げます（推奨上限: %,d 円）。",
                margin, recommendedMax
            );
        }

        int over = income - recommendedMax;
        String wallName = isSpecific ? "150万円の壁（特定扶養親族控除）" : "103万円の壁（一般扶養控除）";
        if (isSubjectToSI && income >= WALL_SOCIAL_INSURANCE_SMALL) {
            wallName = "106万円の壁（社会保険）";
        } else if (!isSubjectToSI && income >= WALL_SOCIAL_INSURANCE_LARGE) {
            wallName = "130万円の壁（社会保険）";
        }

        return String.format(
            "「%s」を %,d 円超過しています。扶養に残るには年収を %,d 円以下に抑えるか、扶養を外れた場合の手取りと比較して判断してください。",
            wallName, over, recommendedMax
        );
    }
}
