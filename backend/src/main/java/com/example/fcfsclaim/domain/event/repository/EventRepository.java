package com.example.fcfsclaim.domain.event.repository;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.entity.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByStatus(EventStatus status);

    // 시작 시각이 됐는데 아직 SCHEDULED 상태인 이벤트 (활성화 대상)
    @Query("SELECT e FROM Event e WHERE e.status = 'SCHEDULED' " +
           "AND e.startAt <= :now AND e.endAt > :now")
    List<Event> findActivatable(@Param("now") LocalDateTime now);

    // end_at이 지났는데 아직 종료 처리 안 된 이벤트 (복구 대상)
    @Query("SELECT e FROM Event e WHERE e.status IN ('SCHEDULED', 'ACTIVE') " +
           "AND e.endAt <= :now")
    List<Event> findOverdue(@Param("now") LocalDateTime now);

    @Modifying
    @Query(value = "UPDATE event SET status = 'ACTIVE', end_at = :endAt", nativeQuery = true)
    void reactivateAll(@Param("endAt") LocalDateTime endAt);
}
