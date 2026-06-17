package com.example.incomewallsimulator.service;

import com.example.incomewallsimulator.dto.SimulationRequest;
import com.example.incomewallsimulator.dto.SimulationResult;
import com.example.incomewallsimulator.dto.WallStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 2025年（令和7年度）税制改正対応の「年収の壁」シミュレーション計算ロジック。
 *
 * <h3>2025年改正の主なポイント</h3>
 * <ul>
 *   <li>基礎控除: 一律48万円 → 合計所得に応じて58〜95万円（2025・2026年の時限措置）。
 *       学生の所得帯（合計所得132万円以下）は95万円。</li>
 *   <li>給与所得控除の最低保障額: 55万円 → 65万円（給与収入190万円以下は一律65万円）。</li>
 *   <li>本人の所得税が発生する「壁」: 103万円 → <b>160万円</b>（基礎控除95万＋給与所得控除65万）。</li>
 *   <li>特定親族特別控除の新設（19〜22歳）: 給与収入123万円超でも、<b>188万円までは段階的に</b>
 *       親が控除を受けられる。従来の「150万円で控除が消える崖」ではなくなった。</li>
 *   <li>社会保険の被扶養者の年収要件（19〜22歳）: 2025年10月から130万円未満 → <b>150万円未満</b>に引き上げ。
 *       ただし週20時間以上・51人以上企業での加入義務（106万円の壁）は引き続き適用。</li>
 * </ul>
 */
@Service
public class TaxCalculationService {

    // 給与所得控除の最低保障額（2025年改正後）。給与収入190万円以下は一律この額
    private static final int EMPLOYMENT_INCOME_DEDUCTION_MIN = 650_000;
    private static final int EMPLOYMENT_DEDUCTION_FLAT_CEILING = 1_900_000;

    // 各種「壁」の閾値
    private static final int WALL_RESIDENT_TAX = 1_100_000;          // 住民税が発生し始める目安
    private static final int WALL_INCOME_TAX = 1_600_000;            // 本人の所得税の壁（基礎控除95万＋給与所得控除65万）
    private static final int WALL_SOCIAL_INSURANCE_SMALL = 1_060_000;// 社会保険の壁（106万・条件該当者）
    private static final int WALL_SOCIAL_INSURANCE_LARGE = 1_300_000;// 社会保険の壁（130万・被扶養者の上限）
    private static final int WALL_SI_DEPENDENT_STUDENT = 1_500_000;  // 19〜22歳の被扶養者の上限（2025年10月〜・130万→150万に引き上げ）
    private static final int WALL_DEDUCTION_START = 1_230_000;       // 扶養控除→特定親族特別控除の切替点
    private static final int WALL_DEDUCTION_REDUCE = 1_500_000;      // 19〜22歳：親の控除が減り始める点
    private static final int WALL_DEDUCTION_END = 1_880_000;         // 19〜22歳：親の控除が完全に消える点
    private static final int WALL_GENERAL_DEPENDENT = 1_230_000;     // 一般扶養：控除の上限（123万）

    // 控除の満額
    private static final int SPECIFIC_DEDUCTION_MAX = 630_000;       // 特定扶養親族／特定親族特別控除の満額
    private static final int GENERAL_DEPENDENT_DEDUCTION = 380_000;  // 一般扶養控除

    // 社会保険料率（本人負担分・概算）
    private static final double HEALTH_INSURANCE_RATE = 0.0500;      // 健康保険 約5%
    private static final double PENSION_RATE = 0.0915;              // 厚生年金 約9.15%
    private static final int NATIONAL_PENSION_ANNUAL = 203_760;      // 国民年金保険料（2025年・年額）

    // 親の所得税率の想定（一般的な会社員）
    private static final double ASSUMED_PARENT_TAX_RATE = 0.20;

    public SimulationResult simulate(SimulationRequest req) {
        int income = req.getAnnualIncome();
        boolean isSpecific = req.getIsSpecificDependent();
        boolean isSubjectToSI = req.getIsSubjectToSocialInsurance();
        boolean parentIsEmployee = req.getParentIsEmployee();

        // --- 本人の税・社会保険料 ---
        int socialInsurancePremium = calcSocialInsurancePremium(income, isSubjectToSI, isSpecific);
        int employmentIncome = income - calcEmploymentIncomeDeduction(income); // 給与所得
        int totalIncome = employmentIncome; // 給与のみ前提なので合計所得＝給与所得
        int basicDeduction = calcBasicDeduction(totalIncome);
        int taxableIncome = Math.max(totalIncome - socialInsurancePremium - basicDeduction, 0);
        int incomeTax = calcIncomeTax(taxableIncome);
        int residentTax = calcResidentTax(taxableIncome, income);
        int takeHome = income - socialInsurancePremium - incomeTax - residentTax;

        // --- 親の扶養控除（段階的）---
        int parentMaxDeduction = isSpecific ? SPECIFIC_DEDUCTION_MAX : GENERAL_DEPENDENT_DEDUCTION;
        int parentDeduction = isSpecific
                ? calcSpecificDeduction(income)
                : calcGeneralDependentDeduction(income);
        int parentLostDeduction = parentMaxDeduction - parentDeduction;
        int parentAdditionalTax = (int) (parentLostDeduction * ASSUMED_PARENT_TAX_RATE);

        // 親が満額の控除を受けられ、かつ社会保険の被扶養も維持できているか
        boolean fullDeduction = (parentDeduction == parentMaxDeduction);
        boolean siDependentKept = !parentIsEmployee
                || income < (isSubjectToSI ? WALL_SOCIAL_INSURANCE_SMALL : socialInsuranceDependentWall(isSpecific));
        boolean canBeDependent = fullDeduction && siDependentKept;

        String dependentStatus = buildDependentStatus(income, isSpecific, parentDeduction, parentMaxDeduction);

        List<WallStatus> walls = buildWallStatuses(income, isSpecific, isSubjectToSI);
        int recommendedMax = calcRecommendedMax(isSpecific, isSubjectToSI, parentIsEmployee);
        String advice = buildAdvice(income, canBeDependent, recommendedMax, isSpecific, isSubjectToSI, parentDeduction, parentMaxDeduction);

        return SimulationResult.builder()
                .annualIncome(income)
                .takeHomeIncome(Math.max(takeHome, 0))
                .incomeTax(incomeTax)
                .residentTax(residentTax)
                .socialInsurancePremium(socialInsurancePremium)
                .wallStatuses(walls)
                .canBeDependent(canBeDependent)
                .recommendedMaxIncome(recommendedMax)
                .parentDeduction(parentDeduction)
                .parentMaxDeduction(parentMaxDeduction)
                .parentLostDeduction(parentLostDeduction)
                .parentAdditionalTax(parentAdditionalTax)
                .dependentStatus(dependentStatus)
                .advice(advice)
                .build();
    }

    /** 給与所得控除（2025年改正後） */
    private int calcEmploymentIncomeDeduction(int income) {
        if (income <= EMPLOYMENT_DEDUCTION_FLAT_CEILING) {
            return EMPLOYMENT_INCOME_DEDUCTION_MIN;           // 190万円以下は一律65万円
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

    /** 基礎控除（2025・2026年の時限措置／合計所得に応じて段階的） */
    private int calcBasicDeduction(int totalIncome) {
        if (totalIncome <= 1_320_000) return 950_000;   // 給与収入 約200万円以下
        if (totalIncome <= 3_360_000) return 880_000;
        if (totalIncome <= 4_890_000) return 680_000;
        if (totalIncome <= 6_650_000) return 630_000;
        if (totalIncome <= 23_500_000) return 580_000;
        // 2,350万円超は逓減するが、本ツールの対象外として最小値を返す
        return 480_000;
    }

    /**
     * 親の健康保険の被扶養者でいられる年収上限。
     * 19〜22歳（特定扶養）は2025年10月から130万円→150万円に引き上げ。
     */
    private int socialInsuranceDependentWall(boolean isSpecific) {
        return isSpecific ? WALL_SI_DEPENDENT_STUDENT : WALL_SOCIAL_INSURANCE_LARGE;
    }

    /** 社会保険料（本人負担分・概算） */
    private int calcSocialInsurancePremium(int income, boolean isSubjectToSI, boolean isSpecific) {
        if (isSubjectToSI && income >= WALL_SOCIAL_INSURANCE_SMALL) {
            // 厚生年金・健康保険（本人負担分）。加入義務は被扶養者の年齢区分に関わらず106万円から
            return (int) (income * (HEALTH_INSURANCE_RATE + PENSION_RATE));
        } else if (!isSubjectToSI && income >= socialInsuranceDependentWall(isSpecific)) {
            // 被扶養者の上限を超えたら国民健康保険・国民年金（概算）
            int nationalHealthInsurance = Math.min((int) (income * 0.08), 830_000);
            return nationalHealthInsurance + NATIONAL_PENSION_ANNUAL;
        }
        return 0;
    }

    /** 所得税（超過累進課税） */
    private int calcIncomeTax(int taxableIncome) {
        if (taxableIncome <= 0) return 0;
        if (taxableIncome <= 1_950_000) return (int) (taxableIncome * 0.05);
        if (taxableIncome <= 3_300_000) return (int) (taxableIncome * 0.10) - 97_500;
        if (taxableIncome <= 6_950_000) return (int) (taxableIncome * 0.20) - 427_500;
        if (taxableIncome <= 9_000_000) return (int) (taxableIncome * 0.23) - 636_000;
        if (taxableIncome <= 18_000_000) return (int) (taxableIncome * 0.33) - 1_536_000;
        if (taxableIncome <= 40_000_000) return (int) (taxableIncome * 0.40) - 2_796_000;
        return (int) (taxableIncome * 0.45) - 4_796_000;
    }

    /** 住民税（所得割10% ＋ 均等割。給与収入が非課税限度以下なら0） */
    private int calcResidentTax(int taxableIncome, int income) {
        if (income < WALL_RESIDENT_TAX || taxableIncome <= 0) return 0;
        return (int) (taxableIncome * 0.10) + 5_000;
    }

    /**
     * 特定親族特別控除（19〜22歳・所得税）。
     * 123万円までは扶養控除（特定扶養親族）として満額63万円、
     * 123万円超〜188万円は段階的に減少、188万円超で0円。
     */
    private int calcSpecificDeduction(int income) {
        if (income <= 1_500_000) return 630_000; // 〜150万：満額（123万以下の扶養控除と同額）
        if (income <= 1_550_000) return 610_000;
        if (income <= 1_600_000) return 510_000;
        if (income <= 1_650_000) return 410_000;
        if (income <= 1_700_000) return 310_000;
        if (income <= 1_750_000) return 210_000;
        if (income <= 1_800_000) return 110_000;
        if (income <= 1_850_000) return 60_000;
        if (income <= 1_880_000) return 30_000;
        return 0; // 188万円超
    }

    /** 一般扶養控除（19〜22歳以外）。123万円以下なら38万円、超で0円（段階的控除なし） */
    private int calcGeneralDependentDeduction(int income) {
        return income <= WALL_GENERAL_DEPENDENT ? GENERAL_DEPENDENT_DEDUCTION : 0;
    }

    private List<WallStatus> buildWallStatuses(int income, boolean isSpecific, boolean isSubjectToSI) {
        List<WallStatus> walls = new ArrayList<>();

        walls.add(WallStatus.builder()
                .name("160万円の壁（所得税）")
                .threshold(WALL_INCOME_TAX)
                .exceeded(income >= WALL_INCOME_TAX)
                .description("2025年改正で基礎控除95万円＋給与所得控除65万円となり、所得税が発生する本人の壁は160万円に。")
                .consequence("本人に所得税の納税義務が発生します")
                .build());

        if (isSubjectToSI) {
            walls.add(WallStatus.builder()
                    .name("106万円の壁（社会保険）")
                    .threshold(WALL_SOCIAL_INSURANCE_SMALL)
                    .exceeded(income >= WALL_SOCIAL_INSURANCE_SMALL)
                    .description("週20時間以上・月額賃金8.8万円以上・51人以上企業に該当する場合、社会保険に加入義務が生じます（年齢区分に関わらず適用）。")
                    .consequence("健康保険・厚生年金の保険料負担が発生（年間約15〜20万円）")
                    .build());
        } else {
            int siWall = socialInsuranceDependentWall(isSpecific);
            String name = isSpecific ? "150万円の壁（社会保険・被扶養者）" : "130万円の壁（社会保険）";
            String desc = isSpecific
                    ? "親の健康保険の被扶養者でいられる上限。19〜22歳は2025年10月から130万→150万円に引き上げられました。超えると自分で国民健康保険・国民年金に加入が必要です。"
                    : "親の健康保険の被扶養者でいられる上限。これを超えると自分で国民健康保険・国民年金に加入する必要があります。";
            walls.add(WallStatus.builder()
                    .name(name)
                    .threshold(siWall)
                    .exceeded(income >= siWall)
                    .description(desc)
                    .consequence("国民健康保険・国民年金の保険料負担が発生（年間約20〜30万円）")
                    .build());
        }

        if (isSpecific) {
            walls.add(WallStatus.builder()
                    .name("150万円の壁（特定親族特別控除の減額開始）")
                    .threshold(WALL_DEDUCTION_REDUCE)
                    .exceeded(income >= WALL_DEDUCTION_REDUCE)
                    .description("19〜22歳は2025年改正で、123万円超でも188万円までは親が段階的に控除を受けられます（特定親族特別控除）。150万円までは満額63万円。")
                    .consequence("ここから親の控除が段階的に減り始め、親の税負担がじわじわ増加します")
                    .build());
            walls.add(WallStatus.builder()
                    .name("188万円の壁（特定親族特別控除の消滅）")
                    .threshold(WALL_DEDUCTION_END)
                    .exceeded(income >= WALL_DEDUCTION_END)
                    .description("19〜22歳の子に対する親の控除が完全になくなる上限です。")
                    .consequence("親の控除が0円になります")
                    .build());
        } else {
            walls.add(WallStatus.builder()
                    .name("123万円の壁（一般扶養控除）")
                    .threshold(WALL_GENERAL_DEPENDENT)
                    .exceeded(income >= WALL_GENERAL_DEPENDENT)
                    .description("16〜18歳・23〜69歳の一般扶養親族として、親が38万円の控除を受けられる上限（2025年改正で103万→123万に）。")
                    .consequence("親の扶養控除（38万円）が消滅し、親の税負担が年間約7.6万円増加")
                    .build());
        }

        return walls;
    }

    private String buildDependentStatus(int income, boolean isSpecific, int deduction, int maxDeduction) {
        if (deduction == 0) {
            return "控除なし（扶養から完全に外れています）";
        }
        if (deduction == maxDeduction) {
            return "満額控除（親は満額の控除を受けられます）";
        }
        return String.format("段階的に減額中（親の控除は %,d 円 / 満額 %,d 円）", deduction, maxDeduction);
    }

    private int calcRecommendedMax(boolean isSpecific, boolean isSubjectToSI, boolean parentIsEmployee) {
        // 親が満額の控除を受けられる上限
        int deductionWall = isSpecific ? WALL_DEDUCTION_REDUCE - 1 : WALL_GENERAL_DEPENDENT - 1;
        if (!parentIsEmployee) {
            return deductionWall;
        }
        // 親が会社員なら社会保険の被扶養者の上限も考慮
        int siWall = (isSubjectToSI ? WALL_SOCIAL_INSURANCE_SMALL : socialInsuranceDependentWall(isSpecific)) - 1;
        return Math.min(deductionWall, siWall);
    }

    private String buildAdvice(int income, boolean canBeDependent, int recommendedMax,
                               boolean isSpecific, boolean isSubjectToSI,
                               int parentDeduction, int parentMaxDeduction) {
        if (canBeDependent) {
            int margin = recommendedMax - income;
            return String.format(
                "現在の年収は扶養範囲内（親は満額控除）です。あと %,d 円まで稼げます（推奨上限: %,d 円）。",
                Math.max(margin, 0), recommendedMax
            );
        }

        if (isSpecific && parentDeduction > 0) {
            // 19〜22歳で段階的減額の途中
            return String.format(
                "親の控除が満額（%,d 円）から %,d 円に減っています。19〜22歳は188万円まで段階的に控除が残るため、" +
                "急に手取りが落ちる「崖」はありません。手取りの増加と親の負担増を比較して判断しましょう。",
                parentMaxDeduction, parentDeduction
            );
        }

        int over = income - recommendedMax;
        String wallName;
        if (isSubjectToSI && income >= WALL_SOCIAL_INSURANCE_SMALL) {
            wallName = "106万円の壁（社会保険）";
        } else if (!isSubjectToSI && income >= socialInsuranceDependentWall(isSpecific)) {
            wallName = isSpecific ? "150万円の壁（社会保険・被扶養者）" : "130万円の壁（社会保険）";
        } else if (isSpecific) {
            wallName = "188万円の壁（特定親族特別控除の消滅）";
        } else {
            wallName = "123万円の壁（一般扶養控除）";
        }

        return String.format(
            "「%s」を %,d 円超過しています。扶養に残るには年収を %,d 円以下に抑えるか、扶養を外れた場合の手取りと比較して判断してください。",
            wallName, Math.max(over, 0), recommendedMax
        );
    }
}
