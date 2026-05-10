package com.example.fcfsclaim.common.init;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import com.example.fcfsclaim.domain.event.service.EventRecoveryService;
import com.example.fcfsclaim.domain.product.entity.Product;
import com.example.fcfsclaim.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final EventRepository eventRepository;
    private final ProductRepository productRepository;
    private final EventRecoveryService recoveryService;

    @Override
    public void run(String... args) {
        if (eventRepository.count() > 0) return;

        // 1분 뒤 시작, 24시간 진행 (테스트 중 만료 방지)
        LocalDateTime start = LocalDateTime.now().plusMinutes(1);
        LocalDateTime end   = start.plusHours(24);
        Event event = eventRepository.save(Event.of("스타벅스 MD 한정 증정", start, end));

        // TaskScheduler에 활성화·종료 예약
        recoveryService.scheduleActivation(event);
        recoveryService.scheduleEnd(event);

        productRepository.save(Product.of(event.getId(), "써머 텀블러", "한정 수량 5개", 5));
        productRepository.save(Product.of(event.getId(), "에코백", "품절 체험용 — 선택 즉시 재고 소진 안내", 8, 0));
        productRepository.save(Product.of(event.getId(), "머그컵", "한정 수량 3개", 3));
        productRepository.save(Product.of(event.getId(), "키링", "한정 수량 4개", 4));
    }
}
