package com.aaa.aihealthcare.vo;

import lombok.Data;

import java.util.List;

/**
 * 医生开处方请求，只接收药品与用法字段。
 */
@Data
public class PrescriptionRequest {
    private List<Item> items;

    @Data
    public static class Item {
        private Long medicineId;
        private String dosage;
        private String frequency;
        private Integer days;
        private Integer quantity;
    }
}
