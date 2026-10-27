/**
 * ============================================================================
 *  CASH FEATURE (Phase 1 — Trésorerie UI)
 * ============================================================================
 *
 *  Routes → Screens → APIs
 *
 *  /tresorerie                     CashHubComponent
 *      GET  /api/cash/pots         (loadPots into shared cache)
 *      GET  /api/parties           (loadParties into shared cache)
 *      POST /api/cash/pots/ensure-company   (calm no-op if exists)
 *
 *  /tresorerie/caisses             PotListComponent
 *      GET  /api/cash/pots
 *      GET  /api/cash/pots/{id}/balance     (async per pot)
 *
 *  /tresorerie/caisses/:potId      PotDetailComponent
 *      GET  /api/cash/pots (from cache) + /{id}/balance
 *      GET  /api/cash/movements?potId=
 *      CTAs → /tresorerie/operations/new?type=IN|OUT|TRANSFER&fromPotId=&toPotId=
 *
 *  /tresorerie/operations/new      MovementFormComponent (create)
 *  /tresorerie/operations/:id/edit MovementFormComponent (edit DRAFT only)
 *      GET  /api/parties (party select)
 *      GET  /api/cash/pots (pot selects; project options derived from PROJECT pots)
 *      POST /api/cash/movements            (Enregistrer brouillon)
 *      PUT  /api/cash/movements/{id}       (edit mode)
 *      POST /api/cash/movements/{id}/post  (Enregistrer et valider)
 *
 *  /tresorerie/operations/:id      MovementDetailComponent
 *      GET  /api/cash/movements/{id}
 *      POST /api/cash/movements/{id}/post  (DRAFT → Valider)
 *      POST /api/cash/movements/{id}/void  (POSTED → dialog reason required)
 *      Optional link to /processes/{processInstanceId} (Phase 2)
 *
 *  /tresorerie/parties             PartyListComponent
 *      GET  /api/parties (type filter + client-side search)
 *      POST /api/parties (add dialog: name, type, phone)
 *
 *  /tresorerie/parties/:id         PartyDetailComponent
 *      GET  /api/parties/{id}
 *      GET  /api/cash/movements?partyId=   (history; total = POSTED OUT only)
 *      CTA  → /tresorerie/operations/new?partyId=&type=OUT
 *
 *  Project integration:
 *      projects/:id shows "Argent du chantier" strip
 *      POST /api/cash/pots/ensure-project/{projectId}   (auto-ensure on load)
 *      GET  /api/cash/pots/{id}/balance
 *      CTAs → operations/new prefilled (Payer / Encaisser) + link to pot detail
 *
 *  DESIGN SYSTEM:
 *      Colors: IN green #16a34a · OUT red #dc2626 · TRANSFER cyan #0891b2
 *      Status: DRAFT slate · POSTED green · VOID red-muted
 *      Amounts: formatAmount() → "1 500,000 TND" (fr-FR, 3 decimals)
 *      Icons carry meaning (arrow-down=in, arrow-up=out, arrows-h=transfer)
 * ============================================================================
 */
export const CASH_FEATURE_ROUTES = true;
