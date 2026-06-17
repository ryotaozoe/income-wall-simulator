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
 * 税制計算ロジックの単体テスト。
 *
 * お金に関わる計算のため、各「壁」の境界値（ちょうど / 1円手前）を中心に
 * 正確性を担保する。Spring コンテキストや DB に依存しない純粋な単体テスト。
 */
class TaxCalculationServiceTest {

    private final TaxCalculationService service = new TaxCalculationService();

    /** テスト用の SimulationRequest を組み立てるヘルパー */
    private SimulationRequest request(int income, boolean isSpecific,
                                      boolean isSubjectToSI, boolean parentIsEmployee) {
        SimulationRequest req = new SimulationRequest();
        req.setAnnualIncome(income);
        req.setIsSpecificDependent(isSpecific);
        req.setIsSubjectToSocialInsurance(isSubjectToSI);
        req.setParentIsEmployee(parentIsEmployee);
        return req;
    }

    /** 指定した名前を含む壁のステータスを取り出す */
    private WallStatus findWall(SimulationResult result, String namePart) {
        return result.getWallStatuses().stream()
                .filter(w -> w.getName().contains(namePart))
                .findFirst()
                .orElseThrow(() -> new AssertionError("壁が見つかりません: " + namePart));
    }

    @Nested
    @DisplayName("123万円の壁（所得税）")
    class IncomeTaxWall {

        @Test
        @DisplayName("年収123万円ちょうどでは課税所得0のため所得税は発生しない（基礎控除58万＋給与所得控除65万）")
        void exactlyAtWall_noTax() {
            SimulationResult result = service.simulate(request(1_230_000, true, false, false));
            assertThat(result.getIncomeTax()).isZero();
        }

        @Test
        @DisplayName("123万円を超えると所得税が発生する")
        void overWall_taxOccurs() {
            // 年収124万円: 課税所得 = 1,240,000 - 650,000(給与所得控除) - 580,000(基礎控除) = 10,000
            //            所得税 = 10,000 × 5% = 500円
            SimulationResult result = service.simulate(request(1_240_000, true, false, false));
            assertThat(result.getIncomeTax()).isEqualTo(500);
        }

        @ParameterizedTest(name = "年収{0}円 → 壁超過={1}")
        @CsvSource({
                "1229999, false",
                "1230000, true",
                "1500000, true"
        })
        @DisplayName("123万円の壁の超過判定が境界値で正しい")
        void wallExceededFlag(int income, boolean expected) {
            SimulationResult result = service.simulate(request(income, true, false, false));
            assertThat(findWall(result, "123万円").isExceeded()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("特定扶養親族控除の壁（19〜22歳・150万円）")
    class SpecificDependentWall {

        @Test
        @DisplayName("年収149万9999円なら扶養内（2025年改正で150万円未満まで拡大）")
        void justUnder150_isDependent() {
            // 社会保険の判定を無効化するため親は自営業(false)とする
            SimulationResult result = service.simulate(request(1_499_999, true, false, false));
            assertThat(result.isCanBeDependent()).isTrue();
        }

        @Test
        @DisplayName("年収150万円ちょうどで扶養から外れる")
        void exactly150_notDependent() {
            SimulationResult result = service.simulate(request(1_500_000, true, false, false));
            assertThat(result.isCanBeDependent()).isFalse();
        }

        @Test
        @DisplayName("扶養を外れると親は特定扶養控除63万円を失い、追加税負担が約12.6万円発生する")
        void parentImpact() {
            SimulationResult result = service.simulate(request(1_600_000, true, false, false));
            assertThat(result.getParentLostDeduction()).isEqualTo(630_000);
            assertThat(result.getParentAdditionalTax()).isEqualTo(126_000); // 630,000 × 20%
        }
    }

    @Nested
    @DisplayName("一般扶養控除の壁（103万円・2025年以降も変更なし）")
    class GeneralDependentWall {

        @Test
        @DisplayName("年収102万9999円なら扶養内")
        void justUnder103_isDependent() {
            SimulationResult result = service.simulate(request(1_029_999, false, false, false));
            assertThat(result.isCanBeDependent()).isTrue();
        }

        @Test
        @DisplayName("年収103万円ちょうどで扶養から外れる")
        void exactly103_notDependent() {
            SimulationResult result = service.simulate(request(1_030_000, false, false, false));
            assertThat(result.isCanBeDependent()).isFalse();
        }

        @Test
        @DisplayName("扶養を外れると親は一般扶養控除38万円を失う")
        void parentImpact() {
            SimulationResult result = service.simulate(request(1_100_000, false, false, false));
            assertThat(result.getParentLostDeduction()).isEqualTo(380_000);
            assertThat(result.getParentAdditionalTax()).isEqualTo(76_000); // 380,000 × 20%
        }
    }

    @Nested
    @DisplayName("社会保険の壁")
    class SocialInsuranceWall {

        @Test
        @DisplayName("106万円の壁：週20時間以上・51人以上企業の場合、106万円から社会保険料が発生する")
        void wall106_premiumOccurs() {
            // 1,060,000 × (健康保険5% + 厚生年金9.15%) = 1,060,000 × 14.15% = 149,990
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
        @DisplayName("130万円の壁：中小企業等では130万円から国民健康保険・国民年金の負担が発生する")
        void wall130_premiumOccurs() {
            // 国保: min(1,300,000 × 8%, 830,000) = 104,000 ＋ 国民年金: 203,760 = 307,760
            SimulationResult result = service.simulate(request(1_300_000, false, false, true));
            assertThat(result.getSocialInsurancePremium()).isEqualTo(307_760);
            assertThat(findWall(result, "130万円").isExceeded()).isTrue();
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
            SimulationResult r = service.simulate(request(1_600_000, true, false, true));
            int expected = r.getAnnualIncome()
                    - r.getSocialInsurancePremium()
                    - r.getIncomeTax()
                    - r.getResidentTax();
            assertThat(r.getTakeHomeIncome()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("推奨上限年収")
    class RecommendedMax {

        @Test
        @DisplayName("親が会社員の場合、税の壁と社会保険の壁のうち低い方が推奨上限になる")
        void specificDependent_employeeParent() {
            // 税の壁150万 vs 社保の壁130万 → 低い方の130万−1円
            SimulationResult result = service.simulate(request(1_600_000, true, false, true));
            assertThat(result.getRecommendedMaxIncome()).isEqualTo(1_299_999);
        }

        @Test
        @DisplayName("親が自営業の場合、社会保険の被扶養者判定が無いため税の壁が推奨上限になる")
        void specificDependent_selfEmployedParent() {
            SimulationResult result = service.simulate(request(1_600_000, true, false, false));
            assertThat(result.getRecommendedMaxIncome()).isEqualTo(1_499_999);
        }
    }
}
