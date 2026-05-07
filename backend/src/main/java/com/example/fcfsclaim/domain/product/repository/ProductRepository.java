package com.example.fcfsclaim.domain.product.repository;

import com.example.fcfsclaim.domain.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByEventIdOrderByIdAsc(Long eventId);

    @Modifying
    @Query("UPDATE Product p SET p.stock = p.stock - 1 WHERE p.id = :productId AND p.stock > 0")
    int decrementStock(@Param("productId") Long productId);

    @Modifying
    @Query("UPDATE Product p SET p.stock = p.totalStock")
    void resetAllStock();
}
