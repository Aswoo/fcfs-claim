package com.example.fcfsclaim.domain.event.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventLifecycleListener {

    private static final String QUEUE_ENDED_CHANNEL = "queue:ended";

    private final ActiveEventCache activeEventCache;
    private final StringRedisTemplate redis;

    // DB 커밋 이후에 실행 → refresh()가 커밋 전 DB를 읽어 캐시를 덮어쓰는 레이스 방지
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onActivated(EventActivatedEvent event) {
        activeEventCache.add(event.eventId());
        log.info("이벤트 {} 캐시 등록 (after commit)", event.eventId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnded(EventEndedEvent event) {
        activeEventCache.remove(event.eventId());
        redis.delete("queue:waiting:" + event.eventId());
        redis.convertAndSend(QUEUE_ENDED_CHANNEL, String.valueOf(event.eventId()));
        log.info("이벤트 {} 종료 후처리 완료 (after commit)", event.eventId());
    }
}
