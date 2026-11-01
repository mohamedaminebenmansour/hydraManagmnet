package tn.hydra.event;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** GET /api/events?type=&projectId=&from=&to=&page= — owner only, read-only. */
@RestController
public class DomainEventController {

    private final DomainEventService events;

    public DomainEventController(DomainEventService events) {
        this.events = events;
    }

    @GetMapping("/api/events")
    public DomainEventService.DomainEventPage list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            Authentication auth) {
        return events.list(auth, type, projectId, from, to, page);
    }
}
