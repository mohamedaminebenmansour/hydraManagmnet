package tn.hydra.ticket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TicketServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private AppUserRepository users;
    private EventLogService audit;
    private TicketService service;
    private AppUser me;
    private Authentication auth;
    private Authentication writer;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AppUserRepository.class);
        audit = mock(EventLogService.class);
        service = new TicketService(jdbc, users, audit);

        me = new AppUser();
        me.setId(UUID.randomUUID());
        me.setOrgId(ORG);
        me.setEmail("me@hydra.tn");
        when(users.findByEmail("me@hydra.tn")).thenReturn(Optional.of(me));

        auth = new UsernamePasswordAuthenticationToken("me@hydra.tn", "pw");
        writer = new UsernamePasswordAuthenticationToken("me@hydra.tn", "pw",
                List.of(new SimpleGrantedAuthority("PERM_TICKET_WRITE")));
    }

    private Map<String, Object> ticketRow(String status, UUID assignedTo, UUID projectId) {
        var row = new HashMap<String, Object>();
        row.put("id", TICKET_ID);
        row.put("title", "Fuite");
        row.put("description", "regard");
        row.put("status", status);
        row.put("priority", "MEDIUM");
        row.put("project_id", projectId);
        row.put("project_name", "Chantier Nord");
        row.put("assigned_to", assignedTo);
        row.put("assigned_to_name", null);
        row.put("start_at", null);
        row.put("due_at", null);
        row.put("created_at", Instant.now());
        row.put("created_by", me.getId());
        row.put("created_by_name", "Moi");
        row.put("resolved_at", null);
        row.put("resolved_by", null);
        row.put("document_id", null);
        row.put("process_instance_id", null);
        return row;
    }

    // ---- create ----

    @Test
    void create_requiresTitleAndProject() {
        assertThatThrownBy(() -> service.create(auth, " ", null, null, null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("titre");
        assertThatThrownBy(() -> service.create(auth, "Fuite", null, null, null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("projet");
        verifyNoInteractions(audit);
    }

    @Test
    void create_rejectsForeignOrClosedProject() {
        when(jdbc.queryForList("select status from project where id = ? and org_id = ?",
                PROJECT_ID, ORG)).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(auth, "Fuite", null, PROJECT_ID,
                null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Projet");

        when(jdbc.queryForList("select status from project where id = ? and org_id = ?",
                PROJECT_ID, ORG)).thenReturn(List.of(Map.of("status", "CLOTURE")));
        assertThatThrownBy(() -> service.create(auth, "Fuite", null, PROJECT_ID,
                null, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("clôturé");
    }

    @Test
    void create_insertsDraftTicket_andAudits() {
        when(jdbc.queryForList("select status from project where id = ? and org_id = ?",
                PROJECT_ID, ORG)).thenReturn(List.of(Map.of("status", "ACTIF")));
        when(jdbc.queryForObject(contains("insert into ticket"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(TICKET_ID);
        var createdRow = ticketRow("DRAFT", null, PROJECT_ID);
        createdRow.put("priority", "HIGH");
        when(jdbc.queryForList(contains("from ticket t"), eq(TICKET_ID), eq(ORG)))
                .thenReturn(List.of(createdRow));

        TicketService.TicketResponse created = service.create(auth, "Fuite", "regard",
                PROJECT_ID, null, "HIGH", LocalDate.now(), LocalDate.now().plusDays(2), null);

        assertThat(created.status()).isEqualTo("DRAFT");
        assertThat(created.priority()).isEqualTo("HIGH");
        assertThat(created.processInstanceId()).isNull();
        verify(jdbc).queryForObject(contains("'DRAFT'"), eq(UUID.class),
                eq(ORG), eq("Fuite"), eq("regard"), eq("HIGH"),
                eq(PROJECT_ID), isNull(), any(LocalDate.class), any(LocalDate.class), eq(me.getId()), isNull());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(audit).log(eq("TICKET_CREATED"), eq("ticket"), eq(TICKET_ID), captor.capture());
        assertThat(captor.getValue()).contains("Fuite");
    }

    @Test
    void create_defaultsPriorityToMedium_andValidatesAssignee() {
        when(jdbc.queryForList("select status from project where id = ? and org_id = ?",
                PROJECT_ID, ORG)).thenReturn(List.of(Map.of("status", "ACTIF")));

        when(jdbc.queryForList("select id from app_user where id = ? and org_id = ?",
                UUID.class, OTHER_USER, ORG)).thenReturn(List.of());
        assertThatThrownBy(() -> service.create(auth, "Fuite", null, PROJECT_ID,
                OTHER_USER, null, null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Utilisateur");

        assertThatThrownBy(() -> service.create(auth, "Fuite", null, PROJECT_ID,
                null, "URGENT", null, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Priorité");
    }

    // ---- list filters ----

    @Test
    void list_alwaysScopesToOrg_andAddsMineFilter() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(ticketRow("DRAFT", null, PROJECT_ID)));

        service.list(auth, null, null, null, null, true);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("where t.org_id = ?"), captor.capture());
        assertThat(captor.getValue()[0]).isEqualTo(ORG);
        verify(jdbc).queryForList(contains("and t.assigned_to = ?"), any(Object[].class));
    }

    @Test
    void list_appliesStatusAndPriorityFilters() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.list(auth, null, null, "DRAFT", "HIGH", false);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("and t.status = ?"), captor.capture());
        assertThat(captor.getValue()).contains("DRAFT");
        assertThat(captor.getValue()).contains("HIGH");
    }

    // ---- update ----

    @Test
    void update_rejectsForeignTicketWith404() {
        when(jdbc.queryForList(contains("from ticket where id = ? and org_id = ?"),
                eq(TICKET_ID), eq(ORG))).thenReturn(List.of());

        assertThatThrownBy(() -> service.update(auth, TICKET_ID, "Fuite", null,
                null, null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
    }

    @Test
    void update_onlyChangesProvidedFields() {
        when(jdbc.queryForList(contains("from ticket where id = ? and org_id = ?"),
                eq(TICKET_ID), eq(ORG)))
                .thenReturn(List.of(ticketRow("DRAFT", null, PROJECT_ID)));
        when(jdbc.queryForList(contains("from ticket t"), eq(TICKET_ID), eq(ORG)))
                .thenReturn(List.of(ticketRow("DRAFT", null, PROJECT_ID)));

        service.update(auth, TICKET_ID, "Nouveau titre", null,
                null, null, "LOW", null, null, null);

        verify(jdbc).update(contains("update ticket set updated_at = now(), title = ?, priority = ?"),
                eq("Nouveau titre"), eq("LOW"), eq(TICKET_ID), eq(ORG));
        verify(audit).log(eq("TICKET_UPDATED"), eq("ticket"), eq(TICKET_ID), isNull());
    }

    // ---- close (simple task semantics — no workflow walk, no process) ----

    private void stubTicket(String status, UUID assignedTo) {
        when(jdbc.queryForList(contains("from ticket where id = ? and org_id = ?"),
                eq(TICKET_ID), eq(ORG)))
                .thenReturn(List.of(ticketRow(status, assignedTo, PROJECT_ID)));
        when(jdbc.queryForList(contains("from ticket t"), eq(TICKET_ID), eq(ORG)))
                .thenReturn(List.of(ticketRow(status, assignedTo, PROJECT_ID)));
    }

    @Test
    void close_closesFromAnyNonTerminalStatus_andAudits() {
        stubTicket("DRAFT", OTHER_USER);

        TicketService.TicketResponse closed = service.close(writer, TICKET_ID);

        assertThat(closed.status()).isEqualTo("DRAFT"); // row stub unchanged; SQL does the update
        verify(jdbc).update(contains("status = 'AS_BUILT', resolved_at = now(), resolved_by = ?"),
                eq(me.getId()), eq(TICKET_ID), eq(ORG));
        verify(audit).log(eq("TICKET_CLOSED"), eq("ticket"), eq(TICKET_ID),
                contains("\"from\":\"DRAFT\""));
    }

    @Test
    void close_rejectsAlreadyClosedTicket() {
        stubTicket("AS_BUILT", me.getId());

        assertThatThrownBy(() -> service.close(writer, TICKET_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("clôturé");
        verify(jdbc, never()).update(contains("update ticket"), any(), any(), any(), any());
    }

    @Test
    void close_rejectsForeignTicketWith404() {
        when(jdbc.queryForList(contains("from ticket where id = ? and org_id = ?"),
                eq(TICKET_ID), eq(ORG))).thenReturn(List.of());

        assertThatThrownBy(() -> service.close(writer, TICKET_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verifyNoInteractions(audit);
    }

    // ---- workflow transitions ----

    @Test
    void setStatus_rejectsIllegalTransition() {
        stubTicket("DRAFT", me.getId());

        assertThatThrownBy(() -> service.setStatus(auth, TICKET_ID, "AS_BUILT"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Transition");
        verify(jdbc, never()).update(contains("update ticket"), any(), any(), any(), any());
    }

    @Test
    void setStatus_rejectsUnknownStatus() {
        stubTicket("DRAFT", me.getId());

        assertThatThrownBy(() -> service.setStatus(auth, TICKET_ID, "OUVERT"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Statut inconnu");
    }

    @Test
    void transitions_rejectForeignTicketWith404() {
        when(jdbc.queryForList(contains("from ticket where id = ? and org_id = ?"),
                eq(TICKET_ID), eq(ORG))).thenReturn(List.of());

        assertThatThrownBy(() -> service.availableTransitions(auth, TICKET_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Ticket");
        verifyNoInteractions(audit);
    }
}
