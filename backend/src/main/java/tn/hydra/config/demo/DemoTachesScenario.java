package tn.hydra.config.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.ticket.TicketPhotoService;
import tn.hydra.ticket.TicketService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ============================================================================
 *  DemoTachesScenario — the 11-record demo pack (TACHE + PROBLEME + RESERVE + comments).
 * ============================================================================
 *
 *  <p>Creates through {@link TicketService} / {@link TicketPhotoService} as the
 *  user in brackets. Each title starts with « [Démo] ». Idempotent: skips a
 *  record if a ticket with the same title exists in the demo org. Due dates are
 *  relative to today. Photos and the PDF are tiny valid files generated in code.
 *
 *  <p>Records (see docs/DEMO.md for the full table):
 *  <ol>
 *   <li>[gerant] TACHE DOCUMENT → achats, due +3, OPEN.</li>
 *   <li>[gerant] TACHE ACHAT → achats, due +2, achats starts → IN_PROGRESS.</li>
 *   <li>[gerant] TACHE TECHNIQUE → metre, due +1, metre starts + uploads PDF + resolves → RESOLVED.</li>
 *   <li>[gerant] TACHE ADMINISTRATIF → finance, finance resolves, gerant verifies → CLOSED.</li>
 *   <li>[gerant] TACHE AUTRE → travaux, gerant cancels → CANCELLED.</li>
 *   <li>[gerant] TACHE DOCUMENT → travaux, due -2, OPEN (overdue).</li>
 *   <li>[gerant] TACHE FINANCE PRIVATE, NO chantier → finance, due +4, OPEN.</li>
 *   <li>[achats] TACHE ACHAT, NO chantier → achats himself, OPEN.</li>
 *   <li>[chef] PROBLEME PANNE_ENGIN → travaux, with 1 photo, OPEN.</li>
 *   <li>[quantites] RESERVE MALFACON origin CLIENT → chef, chef starts + AFTER photo + resolves → RESOLVED.</li>
 *   <li>Comments on #2 (achats) and #6 (gerant).</li>
 *  </ol>
 * ============================================================================
 */
@Component
@Profile("demo")
public class DemoTachesScenario implements DemoScenario {

    private static final Logger log = LoggerFactory.getLogger(DemoTachesScenario.class);

    private final TicketService tickets;
    private final TicketPhotoService photos;
    private final JdbcTemplate jdbc;

    public DemoTachesScenario(TicketService tickets, TicketPhotoService photos, JdbcTemplate jdbc) {
        this.tickets = tickets; this.photos = photos; this.jdbc = jdbc;
    }

    @Override public String key() { return "taches"; }

    @Override
    public void apply(DemoContext ctx) {
        UUID orgId = ctx.orgId();
        UUID projectId = ctx.projectId();
        LocalDate today = ctx.today();

        // ── 1. TACHE DOCUMENT → achats, due +3, OPEN ──
        createTache(orgId, ctx, GERANT_EMAIL,
                "[Démo] Préparer le dossier de consultation PEHD", "DOCUMENT",
                ACHATS_ID, projectId, today.plusDays(3));

        // ── 2. TACHE ACHAT → achats, due +2, achats starts → IN_PROGRESS ──
        TicketService.TicketResponse t2 = createTache(orgId, ctx, GERANT_EMAIL,
                "[Démo] Demander 3 devis pour le ciment", "ACHAT",
                ACHATS_ID, projectId, today.plusDays(2));
        if (t2 != null && "OPEN".equals(statusOf(orgId, t2.id()))) {
            Authentication achats = ctx.auth(ACHATS_EMAIL);
            runAs(achats, () -> tickets.start(achats, t2.id()));
        }

        // ── 3. TACHE TECHNIQUE → metre, due +1, metre starts + uploads PDF + resolves → RESOLVED ──
        TicketService.TicketResponse t3 = createTache(orgId, ctx, GERANT_EMAIL,
                "[Démo] Calculer les quantités de l'attachement N°1", "TECHNIQUE",
                METRE_ID, projectId, today.plusDays(1));
        if (t3 != null && "OPEN".equals(statusOf(orgId, t3.id()))) {
            Authentication metre = ctx.auth(METRE_EMAIL);
            runAs(metre, () -> {
                tickets.start(metre, t3.id());
                photos.addDeliverable(metre, t3.id(), tinyPdf("attachement-1.pdf"));
                tickets.resolve(metre, t3.id(), "Quantités calculées, fichier joint");
            });
        }

        // ── 4. TACHE ADMINISTRATIF → finance, finance resolves, gerant verifies → CLOSED ──
        TicketService.TicketResponse t4 = createTache(orgId, ctx, GERANT_EMAIL,
                "[Démo] Déposer la caution définitive", "ADMINISTRATIF",
                FINANCE_ID, projectId, today.plusDays(4));
        if (t4 != null && "OPEN".equals(statusOf(orgId, t4.id()))) {
            Authentication finance = ctx.auth(FINANCE_EMAIL);
            Authentication gerant = ctx.auth(GERANT_EMAIL);
            runAs(finance, () -> {
                tickets.start(finance, t4.id());
                tickets.resolve(finance, t4.id(), "Caution déposée à la banque");
            });
            runAs(gerant, () -> tickets.verify(gerant, t4.id()));
        }

        // ── 5. TACHE AUTRE → travaux, gerant cancels → CANCELLED ──
        TicketService.TicketResponse t5 = createTache(orgId, ctx, GERANT_EMAIL,
                "[Démo] Réserver la salle de réunion", "AUTRE",
                TRAVAUX_ID, projectId, today.plusDays(1));
        if (t5 != null && !"CANCELLED".equals(statusOf(orgId, t5.id()))) {
            Authentication gerant = ctx.auth(GERANT_EMAIL);
            runAs(gerant, () -> tickets.cancel(gerant, t5.id(), "Réunion déplacée sur le chantier"));
        }

        // ── 6. TACHE DOCUMENT → travaux, due -2, OPEN (overdue) ──
        TicketService.TicketResponse t6 = createTache(orgId, ctx, GERANT_EMAIL,
                "[Démo] Envoyer le planning au CRDA", "DOCUMENT",
                TRAVAUX_ID, projectId, today.minusDays(2));

        // ── 7. TACHE FINANCE PRIVATE, NO chantier → finance, due +4, OPEN ──
        findOrCreate(orgId, "[Démo] Préparer les salaires d'octobre", () -> {
            Authentication gerant = ctx.auth(GERANT_EMAIL);
            return runAsReturning(gerant, () -> tickets.create(gerant,
                    "[Démo] Préparer les salaires d'octobre", "Tâche privée de paie",
                    null, FINANCE_ID, null, null, today.plusDays(4), null,
                    "TACHE", "FINANCE", null, null, null, null, true));
        });

        // ── 8. TACHE ACHAT, NO chantier → achats himself, OPEN ──
        findOrCreate(orgId, "[Démo] Mettre à jour la liste des fournisseurs", () -> {
            Authentication achats = ctx.auth(ACHATS_EMAIL);
            return runAsReturning(achats, () -> tickets.create(achats,
                    "[Démo] Mettre à jour la liste des fournisseurs", "Liste à actualiser",
                    null, ACHATS_ID, null, null, null, null,
                    "TACHE", "ACHAT", null, null, null, null, false));
        });

        // ── 9. PROBLEME PANNE_ENGIN → travaux, with 1 photo, OPEN ──
        findOrCreate(orgId, "[Démo] Tractopelle en panne au PK 1+200", () -> {
            Authentication chef = ctx.auth(CHEF_EMAIL);
            return runAsReturning(chef, () -> photos.createWithPhotos(chef,
                    "[Démo] Tractopelle en panne au PK 1+200", "Panne d'engin",
                    projectId, TRAVAUX_ID, null, null, null, null,
                    "PROBLEME", "PANNE_ENGIN", null, null, null, null, false,
                    List.of(tinyJpeg("tractopelle.jpg"))));
        });

        // ── 10. RESERVE MALFACON origin CLIENT → chef, chef starts + AFTER photo + resolves → RESOLVED ──
        TicketService.TicketResponse t10 = findOrCreate(orgId, "[Démo] Enduit fissuré sur la cuve", () -> {
            Authentication quantites = ctx.auth(QUANT_EMAIL);
            return runAsReturning(quantites, () -> tickets.create(quantites,
                    "[Démo] Enduit fissuré sur la cuve", "Malfaçon signalée par le client",
                    projectId, CHEF_ID, null, null, today.plusDays(15), null,
                    "RESERVE", "MALFACON", "CLIENT", null, null, null, false));
        });
        if (t10 != null && "OPEN".equals(statusOf(orgId, t10.id()))) {
            Authentication chef = ctx.auth(CHEF_EMAIL);
            runAs(chef, () -> {
                tickets.start(chef, t10.id());
                photos.addPhoto(chef, t10.id(), tinyJpeg("reprise-cuve.jpg"), "AFTER");
                tickets.resolve(chef, t10.id(), "Reprise de l'enduit faite et contrôlée");
            });
        }

        // ── 11. Comments on #2 (achats) and #6 (gerant) ──
        addCommentIfAbsent(orgId, t2, ACHATS_EMAIL, "J'attends encore le devis d'Ettaamir", ctx);
        addCommentIfAbsent(orgId, t6, GERANT_EMAIL, "Urgent, le CRDA l'a demandé hier", ctx);

        // ── extra: a DEMANDE so the demo shows every signalement type ──
        findOrCreate(orgId, "[Démo] Demander les attestations d'assurance chantier", () -> {
            Authentication gerant = ctx.auth(GERANT_EMAIL);
            return runAsReturning(gerant, () -> tickets.create(gerant,
                    "[Démo] Demander les attestations d'assurance chantier", "Documents à fournir par l'assureur",
                    projectId, ACHATS_ID, null, null, today.plusDays(5), null,
                    "DEMANDE", "INFORMATION", null, null, null, null, false));
        });

        log.info("[DemoTachesScenario] done — 11 records");
    }

    // ── constants (mirror DemoScenarioRunner; duplicated so this class is self-contained) ──
    // The fixed ids/emails live on DemoScenarioRunner; we re-declare the email constants here
    // as private copies so scenario code reads naturally without qualifying the runner class.
    private static final String GERANT_EMAIL  = DemoScenarioRunner.GERANT_EMAIL;
    private static final String ACHATS_EMAIL  = DemoScenarioRunner.ACHATS_EMAIL;
    private static final String METRE_EMAIL   = DemoScenarioRunner.METRE_EMAIL;
    private static final String FINANCE_EMAIL = DemoScenarioRunner.FINANCE_EMAIL;
    private static final String TRAVAUX_EMAIL = DemoScenarioRunner.TRAVAUX_EMAIL;
    private static final String CHEF_EMAIL    = DemoScenarioRunner.CHEF_EMAIL;
    private static final String QUANT_EMAIL   = DemoScenarioRunner.QUANT_EMAIL;
    private static final UUID GERANT_ID   = DemoScenarioRunner.GERANT_ID;
    private static final UUID ACHATS_ID   = DemoScenarioRunner.ACHATS_ID;
    private static final UUID METRE_ID    = DemoScenarioRunner.METRE_ID;
    private static final UUID FINANCE_ID  = DemoScenarioRunner.FINANCE_ID;
    private static final UUID TRAVAUX_ID  = DemoScenarioRunner.TRAVAUX_ID;
    private static final UUID CHEF_ID     = DemoScenarioRunner.CHEF_ID;
    private static final UUID QUANT_ID    = DemoScenarioRunner.QUANT_ID;

    // ── helpers ───────────────────────────────────────────────────────

    private TicketService.TicketResponse createTache(UUID orgId, DemoContext ctx, String creatorEmail,
                                                     String title, String category, UUID assigneeId,
                                                     UUID projectId, LocalDate dueAt) {
        return findOrCreate(orgId, title, () -> {
            Authentication creator = ctx.auth(creatorEmail);
            return runAsReturning(creator, () -> tickets.create(creator,
                    title, null, projectId, assigneeId, null, null, dueAt, null,
                    "TACHE", category, null, null, null, null, false));
        });
    }

    @FunctionalInterface private interface Creator { TicketService.TicketResponse create(); }

    private TicketService.TicketResponse findOrCreate(UUID orgId, String title, Creator c) {
        Integer n = jdbc.queryForObject(
                "select count(*) from ticket where org_id = ? and title = ?", Integer.class, orgId, title);
        if (n != null && n > 0) {
            UUID existingId = jdbc.queryForObject(
                    "select id from ticket where org_id = ? and title = ? limit 1", UUID.class, orgId, title);
            return stub(existingId, title, statusOf(orgId, existingId));
        }
        return c.create();
    }

    private String statusOf(UUID orgId, UUID ticketId) {
        return jdbc.queryForObject("select status from ticket where id = ?", String.class, ticketId);
    }

    /** Minimal stub so callers can read id/title/status; other fields are null. */
    private static TicketService.TicketResponse stub(UUID id, String title, String status) {
        return new TicketService.TicketResponse(id, title, null, status, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, false, false);
    }

    private void runAs(Authentication auth, Runnable block) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(auth);
        try { block.run(); } finally { restore(previous); }
    }

    private TicketService.TicketResponse runAsReturning(Authentication auth, Creator block) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(auth);
        try { return block.create(); } finally { restore(previous); }
    }

    private void restore(Authentication previous) {
        if (previous == null) SecurityContextHolder.clearContext();
        else SecurityContextHolder.getContext().setAuthentication(previous);
    }

    private void addCommentIfAbsent(UUID orgId, TicketService.TicketResponse t, String email, String body, DemoContext ctx) {
        if (t == null || t.id() == null) return;
        Integer n = jdbc.queryForObject(
                "select count(*) from ticket_comment where ticket_id = ? and body = ? and org_id = ?",
                Integer.class, t.id(), body, orgId);
        if (n != null && n > 0) return;
        Authentication auth = ctx.auth(email);
        runAs(auth, () -> tickets.addComment(auth, t.id(), body));
    }

    // ── tiny valid files (generated in code, never committed) ─────────

    /** A 2×2 JPEG generated via ImageIO. */
    static MultipartFile tinyJpeg(String name) {
        try {
            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", bos);
            return new InMemoryMultipartFile("photos", name, "image/jpeg", bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("ImageIO write failed", e);
        }
    }

    /** A minimal valid PDF built by hand. */
    static MultipartFile tinyPdf(String name) {
        String pdf = "%PDF-1.4\n1 0 obj<< /Type /Catalog /Pages 2 0 R >>endobj\n"
                + "2 0 obj<< /Type /Pages /Kids [] /Count 0 >>endobj\n"
                + "xref\n0 3\n0000000000 65535 f \ntrailer<< /Size 3 /Root 1 0 R >>\nstartxref\n0\n%%EOF";
        return new InMemoryMultipartFile("file", name, "application/pdf", pdf.getBytes());
    }
}
