package com.example.fcfsclaim.domain.product.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false)
    private int stock;

    @Column(name = "total_stock", nullable = false)
    private int totalStock;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static Product of(Long eventId, String name, String description, int stock) {
        return of(eventId, name, description, stock, stock);
    }

    public static Product of(Long eventId, String name, String description, int totalStock, int initialStock) {
        Product p = new Product();
        p.eventId = eventId;
        p.name = name;
        p.description = description;
        p.stock = initialStock;
        p.totalStock = totalStock;
        p.createdAt = LocalDateTime.now();
        return p;
    }
}
