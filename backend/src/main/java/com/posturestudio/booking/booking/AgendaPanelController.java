package com.posturestudio.booking.booking;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/**
 * The agendas compared (D-091), for every practitioner: only busy and free. Under /api/panel: practitioners only.
 */
@RestController
@RequestMapping("/api/panel/agendas")
class AgendaPanelController {

    private final AgendaService agendas;

    AgendaPanelController(AgendaService agendas) {
        this.agendas = agendas;
    }

    @GetMapping
    List<AgendaView> agendas(@Valid AgendaPeriod period) {
        return agendas.agendas(period);
    }

    @GetMapping("/free-together")
    List<TimeSpan> freeTogether(@Valid FreeTogetherQuery query) {
        return agendas.freeTogether(query);
    }
}
