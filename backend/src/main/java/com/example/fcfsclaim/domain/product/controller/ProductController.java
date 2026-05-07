package com.example.fcfsclaim.domain.product.controller;

import com.example.fcfsclaim.common.response.ApiResponse;
import com.example.fcfsclaim.domain.product.dto.ProductResponse;
import com.example.fcfsclaim.domain.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/{eventId}/products")
    public ApiResponse<List<ProductResponse>> getProducts(@PathVariable Long eventId) {
        return ApiResponse.ok(productService.getProducts(eventId));
    }
}
