package com.example.fcfsclaim.domain.product.service;

import com.example.fcfsclaim.domain.product.dto.ProductResponse;
import com.example.fcfsclaim.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<ProductResponse> getProducts(Long eventId) {
        return productRepository.findByEventIdOrderByIdAsc(eventId)
                .stream()
                .map(ProductResponse::from)
                .toList();
    }
}
