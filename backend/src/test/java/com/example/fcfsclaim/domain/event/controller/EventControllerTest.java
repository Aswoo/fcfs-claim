package com.example.fcfsclaim.domain.event.controller;

import com.example.fcfsclaim.domain.event.entity.Event;
import com.example.fcfsclaim.domain.event.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean EventRepository eventRepository;

    private Event activeEvent(long id) {
        Event e = Event.of("테스트이벤트",
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusHours(1));
        ReflectionTestUtils.setField(e, "id", id);
        e.activate();
        return e;
    }

    @Test
    void 이벤트_상태_200() throws Exception {
        when(eventRepository.findById(1L)).thenReturn(Optional.of(activeEvent(1L)));

        mockMvc.perform(get("/api/v1/events/1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void 이벤트_없으면_404() throws Exception {
        when(eventRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/events/999/status"))
                .andExpect(status().isNotFound());
    }
}
