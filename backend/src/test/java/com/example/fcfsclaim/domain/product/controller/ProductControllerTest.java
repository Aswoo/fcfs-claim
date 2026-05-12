package com.example.fcfsclaim.domain.product.controller;

import com.example.fcfsclaim.domain.product.dto.ProductResponse;
import com.example.fcfsclaim.domain.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ProductService productService;

    @Test
    void 상품목록_200() throws Exception {
        when(productService.getProducts(1L)).thenReturn(List.of(
                new ProductResponse(10L, "한정판 굿즈", "설명", 3, 10)
        ));

        mockMvc.perform(get("/api/v1/events/1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(10))
                .andExpect(jsonPath("$.data[0].name").value("한정판 굿즈"))
                .andExpect(jsonPath("$.data[0].stock").value(3));
    }

    @Test
    void 상품없으면_빈배열_200() throws Exception {
        when(productService.getProducts(99L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/events/99/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
