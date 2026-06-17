package com.example.incomewallsimulator.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class WallStatus {
    private String name;
    private int threshold;
    private boolean exceeded;
    private String description;
    private String consequence;
}
