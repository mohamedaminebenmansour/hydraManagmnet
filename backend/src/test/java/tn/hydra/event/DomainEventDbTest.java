package tn.hydra.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.cash.CashService;
import tn.hydra.cash.PaymentFacade;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.document.service.FileStorage;
import tn.hydra.document.service.StoredFile;
import tn.hydra.process.ProcessService;
import tn.hydra.project.BordereauImportService;
import tn.hydra.project.BordereauService;
import tn.hydra.purchase.BonLivraisonService;
import tn.hydra.purchase.FactureService;
import tn.hydra.purchase.PurchaseOrderService;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ============================================================================
 *  DomainEventDbTest — every listed business action writes exactly ONE
 *  domain_event (right type + subject) in its transaction; a failed action
 *  writes none; GET /api/events is owner-only and org-filtered.
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean. FileStorage is mocked (bordereau / document uploads).
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class DomainEventDbTest {

    private static final String BORDEREAU_FILE = "src/test/resources/bordereau/HYDRA-test-bordereau-Bouslim.xlsx";

    @Autowired private PaymentFacade paymentFacade;
    @Autowired private CashService cashService;
    @Autowired private ProcessService processService;
    @Autowired private PurchaseOrderService purchaseOrders;
    @Autowired private BonLivraisonService bonsLivraison;
    @Autowired private FactureService factures;
    @Autowired private BordereauImportService bordereauImport;
    @Autowired private BordereauService bordereau;
    @Autowired private DomainEventController controller;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private FileStorage storage;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private UUID partyId;
    private UUID potId;
    private String ownerEmail;
    private Authentication auth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        partyId = UUID.randomUUID();
        potId = UUID.randomUUID();
        ownerEmail = "events.owner." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Events Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, ownerEmail, "hash", "Events Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Events Chantier " + projectId, ownerId);
        jdbc.update("insert into party (id, org_id, name, type, active) values (?, ?, ?, 'SUB', true)",
                partyId, orgId, "Events Fournisseur");
        jdbc.update("""
                insert into cash_pot (id, org_id, type, project_id, name, currency, active)
                values (?, ?, 'PROJECT', ?, ?, 'TND', true)
                """, potId, orgId, projectId, "Caisse chantier");
        jdbc.update("""
                insert into cash_movement (id, org_id, movement_type, amount, currency, movement_date,
                                           to_pot_id, status, created_by)
                values (?, ?, 'IN', 1000, 'TND', ?, ?, 'POSTED', ?)
                """, UUID.randomUUID(), orgId, Date.valueOf(LocalDate.now()), potId, ownerId);

        auth = new UsernamePasswordAuthenticationToken(ownerEmail, null, List.of(
                new SimpleGrantedAuthority("PERM_STOCK_MOVE"), new SimpleGrantedAuthority("PERM_STOCK_VIEW"),
                new SimpleGrantedAuthority("PERM_CASH_POST"), new SimpleGrantedAuthority("PERM_PROJECT_WRITE")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(storage.save(anyString(), any(), any())).thenAnswer(inv ->
                new StoredFile(inv.getArgument(0), ((byte[]) inv.getArgument(1)).length, "sha-test"));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private int count(EventType type, String subjectType, UUID subjectId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from domain_event
                where org_id = ? and type = ? and subject_type = ? and subject_id = ?
                """, Integer.class, orgId, type.name(), subjectType, subjectId);
        return n == null ? 0 : n;
    }

    private int count(EventType type) {
        Integer n = jdbc.queryForObject("select count(*) from domain_event where org_id = ? and type = ?",
                Integer.class, orgId, type.name());
        return n == null ? 0 : n;
    }

    private PaymentFacade.SubmitPaymentRequest payment(String amount) {
        return new PaymentFacade.SubmitPaymentRequest(
                potId, new BigDecimal(amount), LocalDate.now(), projectId, partyId,
                "Paiement fournisseur", null,
                null, null, null,
                "CHEQUE", null, null,
                "SOUS_TRAITANCE", null,
                null, null);
    }

    private MockMultipartFile excel() throws Exception {
        return new MockMultipartFile("file", "HYDRA-test-bordereau-Bouslim.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(Path.of(BORDEREAU_FILE)));
    }

    // ── one event per action ────────────────────────────────────────────

    @Test
    void submitPayment_publishesOnePaymentSubmitted() {
        UUID movementId = paymentFacade.submitPayment(auth, payment("300")).movement().id();

        assertThat(count(EventType.PAYMENT_SUBMITTED, "cash_movement", movementId)).isEqualTo(1);
        assertThat(count(EventType.PAYMENT_SUBMITTED)).isEqualTo(1);
    }

    @Test
    void completingTheApprovalSteps_publishesOneStepEventPerStep_andOnePostedEvent() {
        PaymentFacade.SubmitPaymentResponse resp = paymentFacade.submitPayment(auth, payment("300"));
        UUID instanceId = resp.processInstanceId();

        int steps = 0;
        while (true) {
            List<UUID> pending = jdbc.queryForList("""
                    select id from process_step_instance where instance_id = ? and status = 'PENDING'
                    order by step_order limit 1
                    """, UUID.class, instanceId);
            if (pending.isEmpty()) break;
            processService.completeStep(auth, instanceId, pending.get(0),
                    new ProcessService.CompleteStepRequest("ok", null));
            assertThat(count(EventType.PROCESS_STEP_COMPLETED, "process_step", pending.get(0))).isEqualTo(1);
            steps++;
        }

        assertThat(steps).isPositive();
        assertThat(count(EventType.PROCESS_STEP_COMPLETED)).isEqualTo(steps);
        // the last step auto-posts the payment (CashService.onProcessCompletedPostMovement)
        assertThat(count(EventType.CASH_MOVEMENT_POSTED, "cash_movement", resp.movement().id())).isEqualTo(1);
    }

    @Test
    void manualPost_publishesOneCashMovementPosted() {
        UUID movementId = UUID.randomUUID();
        jdbc.update("""
                insert into cash_movement (id, org_id, movement_type, amount, currency, movement_date,
                                           from_pot_id, project_id, status, created_by)
                values (?, ?, 'OUT', 100, 'TND', ?, ?, ?, 'DRAFT', ?)
                """, movementId, orgId, Date.valueOf(LocalDate.now()), potId, projectId, ownerId);

        cashService.postMovement(auth, movementId, true); // owner force

        assertThat(count(EventType.CASH_MOVEMENT_POSTED, "cash_movement", movementId)).isEqualTo(1);
    }

    @Test
    void purchaseOrderCreate_publishesOnePurchaseOrderCreated() {
        UUID poId = purchaseOrders.create(auth, new PurchaseOrderService.CreateBcRequest(
                projectId, partyId, "BC-EVT", null, null, null, null, null)).id();

        assertThat(count(EventType.PURCHASE_ORDER_CREATED, "purchase_order", poId)).isEqualTo(1);
    }

    @Test
    void blPost_publishesOneDeliveryReceived() {
        UUID itemId = jdbc.queryForObject(
                "insert into stock_item (org_id, name, uom, created_by) values (?, 'Ciment', 'U', ?) returning id",
                UUID.class, orgId, ownerId);
        UUID locationId = jdbc.queryForObject("""
                insert into stock_location (org_id, type, project_id, name, created_by)
                values (?, 'SITE', ?, 'Chantier', ?) returning id
                """, UUID.class, orgId, projectId, ownerId);
        UUID blId = bonsLivraison.create(auth, new BonLivraisonService.CreateBlRequest(
                projectId, null, partyId, "BL-EVT", locationId, null, null,
                List.of(new BonLivraisonService.CreateBlLineRequest(null, itemId, new BigDecimal("5"), "U", null)))).id();
        assertThat(count(EventType.DELIVERY_RECEIVED)).isZero(); // a DRAFT BL is not received

        bonsLivraison.post(auth, blId);

        assertThat(count(EventType.DELIVERY_RECEIVED, "bon_livraison", blId)).isEqualTo(1);
    }

    @Test
    void factureCreate_publishesOneInvoiceRecorded() {
        UUID factureId = factures.create(auth, new FactureService.CreateFactureRequest(
                projectId, partyId, null, null, "FAC-EVT", LocalDate.now(), null,
                new BigDecimal("100"), new BigDecimal("19"), new BigDecimal("119"), null, null, null)).id();

        assertThat(count(EventType.INVOICE_RECORDED, "facture", factureId)).isEqualTo(1);
    }

    @Test
    void bordereauImportTwice_andActivate_publishOneEventEach_plusOneDocumentUploadedPerImport() throws Exception {
        UUID v1 = bordereauImport.importExcel(auth, projectId, excel()).versionId();
        assertThat(count(EventType.BORDEREAU_IMPORTED, "bordereau_version", v1)).isEqualTo(1);
        UUID docId = jdbc.queryForObject("select source_document_id from bordereau_version where id = ?", UUID.class, v1);
        assertThat(count(EventType.DOCUMENT_UPLOADED, "document", docId)).isEqualTo(1);   // DocumentService.upload

        UUID v2 = bordereauImport.importExcel(auth, projectId, excel()).versionId();
        assertThat(count(EventType.BORDEREAU_IMPORTED, "bordereau_version", v2)).isEqualTo(1);
        assertThat(count(EventType.DOCUMENT_UPLOADED, "document", docId)).isEqualTo(2);   // + uploadNewRevision

        bordereau.activateVersion(auth, projectId, v2);
        assertThat(count(EventType.BORDEREAU_ACTIVATED, "bordereau_version", v2)).isEqualTo(1);
        assertThat(count(EventType.BORDEREAU_ACTIVATED)).isEqualTo(1);
    }

    // ── failure leaves no event ─────────────────────────────────────────

    @Test
    void failedPayment_caisseInsuffisante_leavesNoDomainEvent() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> paymentFacade.submitPayment(auth, payment("63000")));
        assertThat(ex.getCode()).isEqualTo("CAISSE_INSUFFISANTE");

        assertThat(jdbc.queryForObject("select count(*) from domain_event where org_id = ?", Integer.class, orgId))
                .isZero();
    }

    // ── GET /api/events ─────────────────────────────────────────────────

    @Test
    void eventsApi_refusesANonOwner() {
        UUID userId = UUID.randomUUID();
        String email = "events.user." + userId + "@hydra.test";
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, false, true)
                """, userId, orgId, email, "hash", "Events User");

        assertThrows(AccessDeniedException.class, () -> controller.list(null, null, null, null, 0,
                new UsernamePasswordAuthenticationToken(email, null, List.of())));
    }

    @Test
    void eventsApi_neverReturnsAnotherOrgsEvents() {
        UUID otherOrg = UUID.randomUUID();
        jdbc.update("insert into organization (id, name) values (?, ?)", otherOrg, "Other Org " + otherOrg);
        jdbc.update("""
                insert into domain_event (org_id, type, subject_type, subject_id) values (?, 'PAYMENT_SUBMITTED', 'cash_movement', ?)
                """, otherOrg, UUID.randomUUID());
        UUID mine = paymentFacade.submitPayment(auth, payment("300")).movement().id();

        DomainEventService.DomainEventPage page = controller.list(null, null, null, null, 0, auth);

        assertThat(page.items()).isNotEmpty();
        assertThat(page.items()).allSatisfy(e -> assertThat(e.subjectId()).isNotNull());
        assertThat(page.items()).extracting(DomainEventService.DomainEventResponse::subjectId).contains(mine);
        Integer foreignShown = jdbc.queryForObject(
                "select count(*) from domain_event where org_id = ? and id in (" +
                        String.join(",", page.items().stream().map(e -> "'" + e.id() + "'").toList()) + ")",
                Integer.class, otherOrg);
        assertThat(foreignShown).isZero();
        assertThat(page.items()).allSatisfy(e -> assertThat(e.typeLabel()).isNotBlank());
    }
}
