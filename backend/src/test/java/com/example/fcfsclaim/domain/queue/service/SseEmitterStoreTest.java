package com.example.fcfsclaim.domain.queue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SseEmitterStoreTest {

    SseEmitterStore store;

    private static final Long EVENT_ID = 1L;
    private static final Long USER_ID  = 100L;

    @BeforeEach
    void setUp() {
        store = new SseEmitterStore();
    }

    @Test
    @DisplayName("BUG-04: 재연결 시 이전 emitter의 complete()가 호출되지 않는다")
    void 재연결시_이전_emitter_complete_미호출() {
        SseEmitter first  = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);

        store.put(EVENT_ID, USER_ID, first);
        store.put(EVENT_ID, USER_ID, second); // 재연결 — first를 덮어씀

        // first 연결이 명시적으로 종료되지 않음 → 서버는 연결이 열려있다고 인식할 수 있음
        // 이 verify가 실패하면 버그 재현 성공
        verify(first).complete();
    }

    @Test
    @DisplayName("재연결 후 store에는 새 emitter만 남아야 한다")
    void 재연결_후_새_emitter만_저장() {
        SseEmitter first  = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);

        store.put(EVENT_ID, USER_ID, first);
        store.put(EVENT_ID, USER_ID, second);

        assertThat(store.get(EVENT_ID, USER_ID)).isSameAs(second);
    }

    @Test
    @DisplayName("put/get/remove 기본 동작")
    void put_get_remove_정상동작() {
        SseEmitter emitter = mock(SseEmitter.class);

        store.put(EVENT_ID, USER_ID, emitter);
        assertThat(store.get(EVENT_ID, USER_ID)).isSameAs(emitter);

        store.remove(EVENT_ID, USER_ID);
        assertThat(store.get(EVENT_ID, USER_ID)).isNull();
    }

    @Test
    @DisplayName("getByEventId()는 해당 이벤트 emitter만 반환한다")
    void getByEventId_해당이벤트만_반환() {
        SseEmitter emitterA = mock(SseEmitter.class);
        SseEmitter emitterB = mock(SseEmitter.class);
        SseEmitter otherEvent = mock(SseEmitter.class);

        store.put(EVENT_ID, 1L, emitterA);
        store.put(EVENT_ID, 2L, emitterB);
        store.put(99L, 1L, otherEvent); // 다른 이벤트

        Map<Long, SseEmitter> result = store.getByEventId(EVENT_ID);

        assertThat(result).hasSize(2);
        assertThat(result).containsKeys(1L, 2L);
        assertThat(result).doesNotContainKey(99L);
    }

    @Test
    @DisplayName("remove 이후 getByEventId()에서 제외된다")
    void remove_후_getByEventId_제외() {
        SseEmitter emitter = mock(SseEmitter.class);
        store.put(EVENT_ID, USER_ID, emitter);
        store.remove(EVENT_ID, USER_ID);

        assertThat(store.getByEventId(EVENT_ID)).isEmpty();
    }
}
