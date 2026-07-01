package com.example.incomewallsimulator.service;

import com.example.incomewallsimulator.dto.SimulationRequest;
import com.example.incomewallsimulator.dto.SimulationResult;
import com.example.incomewallsimulator.dto.WallStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 税制計算ロジックの単体テスト（2025年・令和7年度税制改正対応）。
 *
 * お金に関わる計算のため、各「壁」の境界値と段階的控除を中心に正確性を担保する。
 * Spring コンテキストや DB に依存しない純粋な単体テスト。
 */
class TaxCalculationServiceTest {

    private final TaxCalculationService service = new TaxCalculationService();

    private SimulationRequest request(int income, boolean isSpecific,
                                      boolean isSubjectToSI, boolean parentIsEmployee) {
        return request(income, isSpecific, isSubjectToSI, parentIsEmployee, false);
    }

    private SimulationRequest request(int income, boolean isSpecific, boolean isSubjectToSI,
                                      boolean parentIsEmployee, boolean isStudent) {
        SimulationRequest req = new SimulationRequest();
        req.setAnnualIncome(income);
        req.setIsSpecificDependent(isSpecific);
        req.setIsSubjectToSocialInsurance(isSubjectToSI);
        req.setParentIsEmployee(parentIsEmployee);
        req.setIsStudent(isStudent);
        return req;
    }

    private WallStatus findWall(SimulationResult result, String namePart) {
        return result.getWallStatuses().stream()
                .filter(w -> w.getName().contains(namePart))
                .findFirst()
                .orElseThrow(() -> new AssertionError("壁が見つかりません: " + namePart));
    }

    @Nested
    @DisplayName("基礎控除95万円（2025年改正の時限措置）")
    class BasicDeduction {

        @Test
        @DisplayName("年収129万9999円・社会保険なしなら、基礎控除95万円により所得税は0円（旧基礎控除58万では課税されていた帯）")
        void raisedBasicDeduction_noTax() {
            // 給与所得 = 1,299,999 - 650,000 = 649,999、基礎控除95万 → 課税所得0
            SimulationResult result = service.simulate(request(1_299_999, true, false, false));
            assertThat(result.getIncomeTax()).isZero();
        }

        @Test
        @DisplayName("年収190万・社会保険ありでは課税所得が生じ所得税が発生する")
        void highIncome_taxOccurs() {
            // 社保 = 1,900,000 × 14.15% = 268,850
            // 給与所得 = 1,900,000 - 650,000 = 1,250,000、基礎控除95万
            // 課税所得 = 1,250,000 - 268,850 - 950,000 = 31,150 → 所得税 31,150 × 5% = 1,557
            SimulationResult result = service.simulate(request(1_900_000, true, true, false));
            assertThat(result.getIncomeTax()).isEqualTo(1_557);
        }
    }

    @Nested
    @DisplayName("160万円の壁（本人の所得税）")
    class IncomeTaxWall {

        @ParameterizedTest(name = "年収{0}円 → 壁超過={1}")
        @CsvSource({
                "1599999, false",
                "1600000, true",
                "1800000, true"
        })
        @DisplayName("160万円の壁の超過判定が境界値で正しい")
        void wallExceededFlag(int income, boolean expected) {
            SimulationResult result = service.simulate(request(income, true, true, false));
            assertThat(findWall(result, "160万円").isExceeded()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("特定親族特別控除（19〜22歳・188万円まで段階的）")
    class SpecificDependentDeduction {

        @ParameterizedTest(name = "子の年収{0}円 → 親の控除{1}円")
        @CsvSource({
                "1450000, 630000",  // 〜150万：満額
                "1500000, 630000",  // 150万ちょうど：満額
                "1550000, 610000",  // 〜155万
                "1600000, 510000",  // 〜160万
                "1650000, 410000",  // 〜165万
                "1880000, 30000",   // 〜188万
                "1900000, 0"        // 188万超：消滅
        })
        @DisplayName("子の年収に応じて親の控除が段階的に減少する（崖ではなくスロープ）")
        void deductionDecreasesGradually(int income, int expectedDeduction) {
            SimulationResult result = service.simulate(request(income, true, false, false));
            assertThat(result.getParentDeduction()).isEqualTo(expectedDeduction);
        }

        @Test
        @DisplayName("年収160万円では満額63万から51万に減り、親の追加税負担は約2.4万円")
        void parentImpactAt160() {
            SimulationResult result = service.simulate(request(1_600_000, true, false, false));
            assertThat(result.getParentMaxDeduction()).isEqualTo(630_000);
            assertThat(result.getParentDeduction()).isEqualTo(510_000);
            assertThat(result.getParentLostDeduction()).isEqualTo(120_000);     // 630,000 - 510,000
            assertThat(result.getParentAdditionalTax()).isEqualTo(24_000);      // 120,000 × 20%
        }

        @Test
        @DisplayName("150万円までは親が満額控除を受けられる（追加負担なし）")
        void noImpactUnder150() {
            SimulationResult result = service.simulate(request(1_450_000, true, false, false));
            assertThat(result.getParentLostDeduction()).isZero();
            assertThat(result.isCanBeDependent()).isTrue();
        }
    }

    @Nested
    @DisplayName("一般扶養控除（19〜22歳以外・123万円の崖）")
    class GeneralDependentDeduction {

        @Test
        @DisplayName("年収123万円以下なら親は38万円の控除を受けられる")
        void under123_fullDeduction() {
            SimulationResult result = service.simulate(request(1_230_000, false, false, false));
            assertThat(result.getParentDeduction()).isEqualTo(380_000);
            assertThat(result.isCanBeDependent()).isTrue();
        }

        @Test
        @DisplayName("年収123万円を超えると一般扶養控除は消滅する（段階的控除なし）")
        void over123_noDeduction() {
            SimulationResult result = service.simulate(request(1_230_001, false, false, false));
            assertThat(result.getParentDeduction()).isZero();
            assertThat(result.getParentLostDeduction()).isEqualTo(380_000);
        }
    }

    @Nested
    @DisplayName("社会保険の壁")
    class SocialInsuranceWall {

        @Test
        @DisplayName("106万円の壁：週20時間以上・51人以上企業では106万円から社会保険料が発生する")
        void wall106_premiumOccurs() {
            // 1,060,000 × (健康保険5% + 厚生年金9.15%) = 149,990
            SimulationResult result = service.simulate(request(1_060_000, true, true, true));
            assertThat(result.getSocialInsurancePremium()).isEqualTo(149_990);
            assertThat(findWall(result, "106万円").isExceeded()).isTrue();
        }

        @Test
        @DisplayName("106万円未満なら社会保険料は発生しない")
        void under106_noPremium() {
            SimulationResult result = service.simulate(request(1_000_000, true, true, true));
            assertThat(result.getSocialInsurancePremium()).isZero();
        }

        @Test
        @DisplayName("130万円の壁：19〜22歳以外は130万円から国民健康保険・国民年金の負担が発生する")
        void wall130_premiumOccurs() {
            // 国保: min(1,300,000 × 8%, 830,000) = 104,000 ＋ 国民年金: 203,760 = 307,760
            SimulationResult result = service.simulate(request(1_300_000, false, false, true));
            assertThat(result.getSocialInsurancePremium()).isEqualTo(307_760);
            assertThat(findWall(result, "130万円").isExceeded()).isTrue();
        }

        @Test
        @DisplayName("19〜22歳の被扶養者の壁は2025年10月から150万円に引き上げ：140万円では社会保険料が発生しない")
        void student_dependentWallRaisedTo150_noPremiumAt140() {
            // 19〜22歳(isSpecific=true)・適用拡大対象外(isSubjectToSI=false)
            // 140万 < 150万 なので被扶養を維持でき、社会保険料は0
            SimulationResult result = service.simulate(request(1_400_000, true, false, true));
            assertThat(result.getSocialInsurancePremium()).isZero();
            assertThat(findWall(result, "150万円の壁（社会保険").isExceeded()).isFalse();
        }

        @Test
        @DisplayName("19〜22歳でも150万円に達すると被扶養を外れ社会保険料が発生する")
        void student_premiumOccursAt150() {
            // 国保: min(1,500,000 × 8%, 830,000) = 120,000 ＋ 国民年金: 203,760 = 323,760
            SimulationResult result = service.simulate(request(1_500_000, true, false, true));
            assertThat(result.getSocialInsurancePremium()).isEqualTo(323_760);
            assertThat(findWall(result, "150万円の壁（社会保険").isExceeded()).isTrue();
        }

        @Test
        @DisplayName("19〜22歳でも（学生でなければ）適用拡大の対象（106万の壁）は残る：社会保険料が発生する")
        void nonStudent_wall106StillApplies() {
            // isSpecific=true でも 学生でなく isSubjectToSI=true なら106万から加入義務
            SimulationResult result = service.simulate(request(1_060_000, true, true, true, false));
            assertThat(result.getSocialInsurancePremium()).isEqualTo(149_990);
            assertThat(findWall(result, "106万円").isExceeded()).isTrue();
        }

        @Test
        @DisplayName("昼間学生は106万円の壁（被用者保険の適用拡大）の対象外：106万でも社会保険料は発生しない")
        void student_excludedFromWall106() {
            // 学生(isStudent=true)は適用拡大の対象外。isSubjectToSI=true を選んでも106万では加入しない
            SimulationResult result = service.simulate(request(1_060_000, true, true, true, true));
            assertThat(result.getSocialInsurancePremium()).isZero();
            // 106万の壁は表示されず、被扶養者の壁（19〜22歳=150万）が基準になる
            boolean hasWall106 = result.getWallStatuses().stream()
                    .anyMatch(w -> w.getName().contains("106万円"));
            assertThat(hasWall106).isFalse();
            assertThat(findWall(result, "150万円の壁（社会保険").isExceeded()).isFalse();
        }

        @Test
        @DisplayName("学生が適用拡大企業を選んでも、被扶養者の壁（150万）を超えれば国保・国民年金が発生する")
        void student_fallsBackToDependentWall() {
            // 学生・isSubjectToSI=true でも 150万到達で被扶養を外れ国保・国民年金
            SimulationResult result = service.simulate(request(1_500_000, true, true, true, true));
            assertThat(result.getSocialInsurancePremium()).isEqualTo(323_760);
            assertThat(findWall(result, "150万円の壁（社会保険").isExceeded()).isTrue();
        }
    }

    @Nested
    @DisplayName("住民税（均等割・所得割・勤労学生控除）")
    class ResidentTaxCalculation {

        @Test
        @DisplayName("年収110万円以下なら住民税は0円（非課税限度額）")
        void under110_noResidentTax() {
            SimulationResult result = service.simulate(request(1_050_000, true, false, false, true));
            assertThat(result.getResidentTax()).isZero();
        }

        @Test
        @DisplayName("学生以外は110万円超で均等割＋所得割が発生する")
        void nonStudent_over110() {
            // 合計所得 = 1,200,000 - 650,000 = 550,000
            // 所得割 = (550,000 - 430,000) × 10% = 12,000 − 調整控除 2,500（50,000×5%）= 9,500
            //        ＋ 均等割 5,000 = 14,500
            SimulationResult result = service.simulate(request(1_200_000, false, false, false, false));
            assertThat(result.getResidentTax()).isEqualTo(14_500);
        }

        @Test
        @DisplayName("学生は110〜134万円では均等割のみ（勤労学生控除で所得割は非課税）")
        void student_between110And134_onlyPerCapita() {
            // 勤労学生控除26万で課税所得0 → 所得割0、均等割5,000のみ
            SimulationResult result = service.simulate(request(1_200_000, true, false, false, true));
            assertThat(result.getResidentTax()).isEqualTo(5_000);
        }

        @Test
        @DisplayName("学生の134万円の壁：134万ちょうどは所得割0、134万超で所得割が発生する")
        void student_wall134() {
            SimulationResult at134 = service.simulate(request(1_340_000, true, false, false, true));
            assertThat(at134.getResidentTax()).isEqualTo(5_000); // 均等割のみ

            // 1,350,000: 合計所得700,000、課税所得 = 700,000-430,000-260,000 = 10,000
            // 所得割 = 1,000 − 調整控除 500（min(60,000,10,000)×5%）= 500
            SimulationResult over134 = service.simulate(request(1_350_000, true, false, false, true));
            assertThat(over134.getResidentTax()).isEqualTo(5_500); // 均等割5,000＋所得割500
            assertThat(findWall(over134, "134万円").isExceeded()).isTrue();
        }

        @Test
        @DisplayName("学生でなければ134万円の壁（住民税・所得割）は表示されない")
        void nonStudent_noStudentWall() {
            SimulationResult result = service.simulate(request(1_350_000, false, false, false, false));
            boolean hasStudentWall = result.getWallStatuses().stream()
                    .anyMatch(w -> w.getName().contains("134万円"));
            assertThat(hasStudentWall).isFalse();
        }
    }

    @Nested
    @DisplayName("手取り計算")
    class TakeHomeCalculation {

        @Test
        @DisplayName("非課税ライン以下なら手取りは年収と一致する（控除がすべて0）")
        void nonTaxable_takeHomeEqualsIncome() {
            SimulationResult result = service.simulate(request(1_000_000, true, false, false));
            assertThat(result.getIncomeTax()).isZero();
            assertThat(result.getResidentTax()).isZero();
            assertThat(result.getSocialInsurancePremium()).isZero();
            assertThat(result.getTakeHomeIncome()).isEqualTo(1_000_000);
        }

        @Test
        @DisplayName("手取り = 年収 − 社会保険料 − 所得税 − 住民税 が成立する")
        void takeHomeIsConsistent() {
            SimulationResult r = service.simulate(request(1_900_000, true, true, true));
            int expected = r.getAnnualIncome()
                    - r.getSocialInsurancePremium()
                    - r.getIncomeTax()
                    - r.getResidentTax();
            assertThat(r.getTakeHomeIncome()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("満額控除の推奨上限年収")
    class RecommendedMax {

        @Test
        @DisplayName("19〜22歳・親が会社員なら、税の壁150万と社保の壁150万（2025年10月引き上げ後）が一致し150万が推奨上限")
        void specificDependent_employeeParent() {
            // 社保の被扶養上限が130万→150万に上がったため、税の壁150万と揃う
            SimulationResult result = service.simulate(request(1_600_000, true, false, true));
            assertThat(result.getRecommendedMaxIncome()).isEqualTo(1_499_999);
        }

        @Test
        @DisplayName("19〜22歳・親が自営業なら、社会保険の被扶養判定が無いため控除の壁150万が推奨上限")
        void specificDependent_selfEmployedParent() {
            SimulationResult result = service.simulate(request(1_600_000, true, false, false));
            assertThat(result.getRecommendedMaxIncome()).isEqualTo(1_499_999);
        }

        @Test
        @DisplayName("一般扶養・親が自営業なら123万円が推奨上限")
        void generalDependent_selfEmployedParent() {
            SimulationResult result = service.simulate(request(1_300_000, false, false, false));
            assertThat(result.getRecommendedMaxIncome()).isEqualTo(1_229_999);
        }
    }
}
