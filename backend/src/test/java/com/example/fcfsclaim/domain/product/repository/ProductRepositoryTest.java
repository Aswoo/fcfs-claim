package com.example.fcfsclaim.domain.product.repository;

import com.example.fcfsclaim.domain.product.entity.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ProductRepositoryTest {

    @Autowired ProductRepository productRepository;
    @Autowired PlatformTransactionManager txManager;

    private Long productId;

    @BeforeEach
    void setUp() {
        productId = productRepository.save(Product.of(1L, "테스트 상품", "설명", 3)).getId();
    }

    @Test
    void decrementStock_재고있음() {
        int affected = productRepository.decrementStock(productId);

        assertThat(affected).isEqualTo(1);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(2);
    }

    @Test
    void decrementStock_재고없음() {
        productRepository.decrementStock(productId);
        productRepository.decrementStock(productId);
        productRepository.decrementStock(productId);   // stock → 0

        int affected = productRepository.decrementStock(productId);   // 재고 0에서 시도

        assertThat(affected).isEqualTo(0);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(0);
    }

    // @Transactional(NOT_SUPPORTED): 이 테스트 메서드는 트랜잭션 없이 실행
    // → @BeforeEach의 save()가 별도 트랜잭션으로 커밋되어 다른 스레드에서 보임
    // → 각 스레드는 TransactionTemplate으로 독립적인 트랜잭션을 생성
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decrementStock_동시100건_재고3개() throws InterruptedException {
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        int threads = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();   // 모든 스레드가 준비될 때까지 대기 → 동시 출발
                    Integer affected = txTemplate.execute(
                            status -> productRepository.decrementStock(productId));
                    if (affected != null && affected > 0) successCount.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        executor.shutdown();

        Integer stock = txTemplate.execute(status ->
                productRepository.findById(productId).map(Product::getStock).orElse(-1));
        assertThat(stock).isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(3);

        // NOT_SUPPORTED로 롤백이 없으므로 직접 정리
        txTemplate.execute(status -> { productRepository.deleteById(productId); return null; });
    }
}
