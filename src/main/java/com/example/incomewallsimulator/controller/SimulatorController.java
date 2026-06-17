package com.example.incomewallsimulator.controller;

import com.example.incomewallsimulator.dto.SimulationRequest;
import com.example.incomewallsimulator.dto.SimulationResult;
import com.example.incomewallsimulator.entity.SimulationHistory;
import com.example.incomewallsimulator.repository.SimulationHistoryRepository;
import com.example.incomewallsimulator.service.TaxCalculationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class SimulatorController {

    private final TaxCalculationService taxCalculationService;
    private final SimulationHistoryRepository historyRepository;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("request", new SimulationRequest());
        model.addAttribute("recentHistory", historyRepository.findTop10ByOrderByCreatedAtDesc());
        return "index";
    }

    @PostMapping("/simulate")
    public String simulate(
            @Valid @ModelAttribute("request") SimulationRequest request,
            BindingResult bindingResult,
            Model model
    ) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("recentHistory", historyRepository.findTop10ByOrderByCreatedAtDesc());
            return "index";
        }

        SimulationResult result = taxCalculationService.simulate(request);

        // 履歴保存
        SimulationHistory history = new SimulationHistory();
        history.setAnnualIncome(request.getAnnualIncome());
        history.setSpecificDependent(request.getIsSpecificDependent());
        history.setSubjectToSocialInsurance(request.getIsSubjectToSocialInsurance());
        history.setParentIsEmployee(request.getParentIsEmployee());
        history.setTakeHomeIncome(result.getTakeHomeIncome());
        history.setIncomeTax(result.getIncomeTax());
        history.setResidentTax(result.getResidentTax());
        history.setSocialInsurancePremium(result.getSocialInsurancePremium());
        history.setCanBeDependent(result.isCanBeDependent());
        historyRepository.save(history);

        model.addAttribute("request", request);
        model.addAttribute("result", result);
        model.addAttribute("recentHistory", historyRepository.findTop10ByOrderByCreatedAtDesc());
        return "index";
    }
}
