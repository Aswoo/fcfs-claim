package com.example.fcfsclaim.domain.event.service;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.entity.EventStatus;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActiveEventCacheTest {

    @Mock
    EventRepository eventRepository;

    ActiveEventCache cache;

    @BeforeEach
    void setUp() {
        cache = new ActiveEventCache(eventRepository);
    }

    // ── BUG-01: 동시 add() 경쟁 조건 ──────────────────────────────────────────

    @RepeatedTest(200)
    @DisplayName("BUG-01: 두 스레드가 동시에 add() 하면 한쪽이 유실될 수 있다 (volatile → AtomicReference 수정)")
    void 동시_add_호출시_이벤트_유실없음() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cache.add(1L);
            doneLatch.countDown();
        });
        Thread t2 = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cache.add(2L);
            doneLatch.countDown();
        });

        t1.start();
        t2.start();
        startLatch.countDown(); // 두 스레드 동시 출발
        doneLatch.await(1, TimeUnit.SECONDS);

        // volatile + 비원자적 read-modify-write: 두 스레드가 둘 다 {} 를 읽고
        // 각각 {1L}, {2L} 를 쓰면 한쪽이 사라짐
        // AtomicReference.updateAndGet() (CAS): 한쪽이 먼저 쓰면 나머지가 재시도 → {1L, 2L}
        assertThat(cache.getAll())
                .as("두 스레드의 add() 결과가 모두 보존되어야 한다")
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @RepeatedTest(200)
    @DisplayName("BUG-01: 두 스레드가 동시에 remove() 하면 한쪽이 복구될 수 있다 (volatile → AtomicReference 수정)")
    void 동시_remove_호출시_정확히_제거됨() throws InterruptedException {
        cache.add(1L);
        cache.add(2L);
        cache.add(3L);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cache.remove(1L);
            doneLatch.countDown();
        });
        Thread t2 = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cache.remove(2L);
            doneLatch.countDown();
        });

        t1.start();
        t2.start();
        startLatch.countDown();
        doneLatch.await(1, TimeUnit.SECONDS);

        assertThat(cache.getAll())
                .as("두 remove()가 모두 반영되어야 한다")
                .containsExactly(3L);
    }

    // ── refresh() + add() 는 설계상 경쟁 조건 존재 ────────────────────────────
    // add()/remove()는 @Transactional 내부에서 호출 (DB 커밋 전)
    // refresh()가 같은 시점에 DB를 읽으면 반드시 stale 결과를 볼 수 있음
    // 근본 해결: @TransactionalEventListener(AFTER_COMMIT) 으로 add()/remove() 호출 시점 이동
    // 현재 구조에서는 최대 30초 윈도우의 inconsistency 존재 (알려진 한계)

    // ── 기본 동작 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("add/remove 기본 동작")
    void add_remove_정상동작() {
        cache.add(1L);
        cache.add(2L);
        assertThat(cache.getAll()).containsExactlyInAnyOrder(1L, 2L);

        cache.remove(1L);
        assertThat(cache.getAll()).containsExactly(2L);
    }

    @Test
    @DisplayName("refresh()는 DB 상태로 캐시를 갱신한다")
    void refresh_DB_상태로_갱신() {
        Event event1 = Event.of("이벤트1", LocalDateTime.now(), LocalDateTime.now().plusHours(1));
        ReflectionTestUtils.setField(event1, "id", 1L);
        when(eventRepository.findByStatus(EventStatus.ACTIVE)).thenReturn(List.of(event1));

        cache.add(99L); // DB에는 없는 값
        cache.refresh();

        assertThat(cache.getAll()).containsExactly(1L);
    }

    @Test
    @DisplayName("getAll()은 불변 Set을 반환한다")
    void getAll_반환값은_불변() {
        cache.add(1L);
        Set<Long> snapshot = cache.getAll();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(999L));
    }
}
