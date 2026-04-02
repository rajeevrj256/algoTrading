package com.algotrading.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class HealthReportDTO {
    private String           timestamp;
    private int              checkNumber;
    private String           overallStatus;
    private int              okCount;
    private int              warnCount;
    private int              failCount;
    private List<CheckItemDTO> items;

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class CheckItemDTO {
        private String name;
        private String status;
        private String detail;
        private String value;
    }
}
