package com.example.fcfsclaim.domain.product.dto;

import com.example.fcfsclaim.domain.product.entity.Product;

public record ProductResponse(
        Long id,
        String name,
        String description,
        int stock,
        int totalStock
) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(
                p.getId(), p.getName(), p.getDescription(),
                p.getStock(), p.getTotalStock()
        );
    }
}
