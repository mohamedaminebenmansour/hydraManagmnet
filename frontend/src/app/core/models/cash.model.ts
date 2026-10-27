/**
 * ============================================================================
 *  CASH MODELS — trésorerie (caisses, mouvements, sous-traitants)
 * ============================================================================
 *  Phase 1 UI for the /api/cash/* + /api/parties backend APIs.
 *  Language: Tunisian BTP — "Caisse", "Opération", "Sous-traitant".
 * ============================================================================
 */

export type PotType = 'COMPANY' | 'PROJECT';
export type MovementType = 'IN' | 'OUT' | 'TRANSFER';
export type MovementStatus = 'DRAFT' | 'POSTED' | 'VOID';
export type PartyType = 'SUB' | 'SUPPLIER' | 'OTHER';

/** A caisse — either the company's central one or a project's chantier one. */
export interface CashPot {
  id: string;
  type: PotType;
  projectId?: string | null;
  name: string;
  currency?: string;
  active?: boolean;
  balance?: number; // filled from balance endpoint
}

/** Sous-traitant / fournisseur directory entry. */
export interface Party {
  id: string;
  name: string;
  type: PartyType;
  phone?: string;
  active?: boolean;
}

/** One money event. DRAFT → POSTED → VOID (immutable after POSTED). */
export interface CashMovement {
  id: string;
  movementType: MovementType;
  amount: number;
  currency?: string;
  movementDate: string;
  fromPotId?: string | null;
  toPotId?: string | null;
  projectId?: string | null;
  partyId?: string | null;
  label?: string;
  note?: string;
  status: MovementStatus;
  processInstanceId?: string | null;
  /** TASK 1 — claimed vs assessed: what the party asked vs what we pay. */
  claimedAmount?: number | null;
  assessedAmount?: number | null;
  varianceReason?: string | null;
  /** TASK 3 — proof document attached to this movement (Finance folder). */
  documentId?: string | null;
  documentName?: string | null;
}

/** Create/edit body for POST/PUT /api/cash/movements. */
export interface MovementBody {
  movementType: MovementType;
  amount: number;
  movementDate: string; // yyyy-MM-dd
  fromPotId: string | null;
  toPotId: string | null;
  projectId?: string | null;
  partyId?: string | null;
  label?: string;
  note?: string;
  processInstanceId?: string | null;
  /** TASK 1 — claimed vs assessed (OUT short form; omitted for IN/TRANSFER). */
  claimedAmount?: number;
  assessedAmount?: number;
  varianceReason?: string;
}

/** GET /api/cash/pots/{id}/balance response. */
export interface BalanceResponse {
  potId: string;
  balance: number;
  currency: string;
}

/** POST /api/cash/movements/{id}/post response. */
export interface PostResponse {
  id: string;
  status: string;
  fromPotBalanceAfter?: number | null;
}

/** Body for POST /api/cash/movements/submit-payment (B5 short payment UX). */
export interface SubmitPaymentBody {
  fromPotId: string;
  amount: number;
  movementDate: string; // yyyy-MM-dd
  projectId: string;
  partyId?: string | null;
  label?: string;
  note?: string;
  /** TASK 1 — claimed = what the party asked; assessed = what we accept to pay
   *  (amount posted). When assessed differs from claimed, varianceReason is
   *  REQUIRED (backend-enforced, mirrored in the payment form). */
  claimedAmount?: number;
  assessedAmount?: number;
  varianceReason?: string;
}

/** Response of submit-payment: the new DRAFT movement + the running process. */
export interface SubmitPaymentResponse {
  movement: CashMovement;
  processInstanceId: string;
}

/** One event of the movement timeline (from event_log). */
export interface MovementTimelineEvent {
  action: string; // CASH_MOVEMENT_CREATED | SUBMITTED | POSTED | VOIDED | REJECTED
  at: string | null;
  userId?: string | null;
  userName?: string | null;
  details?: string | null; // raw JSON string
  /** TASK 2 — where the event came from: 'cash_movement' | 'process_instance' | 'process_step'. */
  source?: string | null;
}

/** GET /api/cash/movements/{id}/timeline — snapshot + ordered events. */
export interface MovementTimeline {
  movementId: string;
  movementType: MovementType | string;
  amount: number;
  currency: string;
  status: MovementStatus | string;
  fromPotName?: string | null;
  toPotName?: string | null;
  partyName?: string | null;
  projectName?: string | null;
  processInstanceId?: string | null;
  /** TASK 1 — claimed vs assessed snapshot (for "where every dinar went"). */
  claimedAmount?: number | null;
  assessedAmount?: number | null;
  varianceReason?: string | null;
  /** TASK 3 — attached proof document. */
  documentId?: string | null;
  documentName?: string | null;
  events: MovementTimelineEvent[];
}

/** Timeline action → French label + icon + color. */
export const TIMELINE_ACTION_VISUAL: Record<string, { label: string; icon: string; color: string }> = {
  CASH_MOVEMENT_CREATED:   { label: 'Opération créée',             icon: 'pi-pencil',       color: '#64748b' },
  CASH_MOVEMENT_SUBMITTED: { label: 'Envoyée pour validation',     icon: 'pi-send',         color: '#d97706' },
  CASH_MOVEMENT_POSTED:    { label: 'Comptabilisée',               icon: 'pi-check-circle', color: '#16a34a' },
  CASH_MOVEMENT_VOIDED:    { label: 'Annulée',                     icon: 'pi-ban',          color: '#b91c1c' },
  CASH_MOVEMENT_REJECTED:  { label: 'Refusée',                     icon: 'pi-times',        color: '#b91c1c' },
  // TASK 2 — merged workflow events (process_instance + process_step sources)
  PROCESS_STARTED:         { label: 'Validation démarrée',         icon: 'pi-play',         color: '#0d9488' },
  PROCESS_COMPLETED:       { label: 'Validation terminée',         icon: 'pi-check-circle', color: '#16a34a' },
  PROCESS_REJECTED:        { label: 'Paiement refusé',             icon: 'pi-times',        color: '#b91c1c' },
  PROCESS_CANCELLED:       { label: 'Validation annulée',          icon: 'pi-ban',          color: '#64748b' },
  STEP_STARTED:            { label: 'Étape de validation commencée', icon: 'pi-hourglass', color: '#0891b2' },
  STEP_COMPLETED:          { label: 'Étape validée',               icon: 'pi-check',        color: '#16a34a' },
  STEP_REJECTED:           { label: 'Étape refusée',               icon: 'pi-times',        color: '#b91c1c' },
  // TASK 3 — proof document attached (Finance folder)
  DOCUMENT_ATTACHED_TO_MOVEMENT: { label: 'Pièce justificative jointe', icon: 'pi-paperclip', color: '#7c3aed' },
};

export function timelineActionVisual(action: string | null | undefined) {
  return TIMELINE_ACTION_VISUAL[String(action ?? '').toUpperCase()] ??
         { label: action || 'Événement', icon: 'pi-circle-fill', color: '#94a3b8' };
}

/** Parse the event details JSON defensively (null when not valid JSON). */
export function parseTimelineDetails(raw: string | null | undefined): Record<string, any> | null {
  if (!raw) return null;
  try {
    const v = JSON.parse(raw);
    return (v && typeof v === 'object' && !Array.isArray(v)) ? v : null;
  } catch { return null; }
}

// ============================================================================
//  VISUAL LANGUAGE — colors + icons (consistent with HYDRA design system)
// ============================================================================

/** Movement type → icon + color + French label. */
export interface MovementTypeVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
  verb: string; // CTA label, e.g. "Encaisser" / "Payer"
}

export const MOVEMENT_TYPE_VISUAL: Record<MovementType, MovementTypeVisual> = {
  IN: {
    icon: 'pi-arrow-circle-down', color: '#16a34a', soft: '#f0fdf4',
    label: 'Encaissement', verb: 'Encaisser',
  },
  OUT: {
    icon: 'pi-arrow-circle-up', color: '#dc2626', soft: '#fef2f2',
    label: 'Paiement', verb: 'Payer',
  },
  TRANSFER: {
    icon: 'pi-arrows-h', color: '#0891b2', soft: '#ecfeff',
    label: 'Transfert', verb: 'Transférer',
  },
};

export function movementTypeVisual(t: MovementType | string | null | undefined): MovementTypeVisual {
  const key = String(t ?? '').toUpperCase();
  return MOVEMENT_TYPE_VISUAL[key as MovementType] ?? MOVEMENT_TYPE_VISUAL['OUT'];
}

/** Movement status → chip visuals. */
export interface MovementStatusVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
}

export const MOVEMENT_STATUS_VISUAL: Record<MovementStatus, MovementStatusVisual> = {
  DRAFT:  { icon: 'pi-pencil',       color: '#64748b', soft: '#f1f5f9',  label: 'Brouillon' },
  POSTED: { icon: 'pi-check-circle', color: '#16a34a', soft: '#f0fdf4',  label: 'Comptabilisé' },
  VOID:   { icon: 'pi-ban',          color: '#b91c1c', soft: '#fef2f2',  label: 'Annulé' },
};

/** Visual used while an approval process is running on a DRAFT movement. */
const PENDING_VISUAL: MovementStatusVisual = {
  icon: 'pi-clock', color: '#d97706', soft: '#fffbeb', label: 'En attente',
};

/**
 * Visual for a DRAFT movement whose approval process ended in a rejection.
 * The ledger keeps the movement DRAFT (money was never posted) — but the
 * owner must see at a glance that the payment was REFUSED, not still pending.
 */
export const REJECTED_VISUAL: MovementStatusVisual = {
  icon: 'pi-times-circle', color: '#b91c1c', soft: '#fef2f2', label: 'Rejeté',
};

/** Timeline actions that mark an approval as refused (backend: ProcessService + CashService). */
export const REJECTION_ACTIONS = ['PROCESS_REJECTED', 'STEP_REJECTED', 'CASH_MOVEMENT_REJECTED'];

/**
 * A DRAFT movement is "Rejeté" when its approval timeline contains a
 * rejection event (process/step/cash rejection). Used on the movement
 * detail page, where the merged timeline (TASK 2) is available.
 */
export function movementRejected(
  m: Pick<CashMovement, 'status'> | null | undefined,
  events?: { action: string }[] | null
): boolean {
  return String(m?.status ?? '').toUpperCase() === 'DRAFT' &&
         !!events?.some(e => REJECTION_ACTIONS.includes(String(e?.action ?? '').toUpperCase()));
}

/**
 * A DRAFT movement with a running approval process is "pending":
 * it is locked for editing and must display "En attente" instead of "Brouillon".
 */
export function movementPending(
  m: Pick<CashMovement, 'status' | 'processInstanceId'> | null | undefined
): boolean {
  return String(m?.status ?? '').toUpperCase() === 'DRAFT' && !!m?.processInstanceId;
}

export function movementStatusVisual(
  s: MovementStatus | string | null | undefined,
  pending = false
): MovementStatusVisual {
  if (pending && String(s ?? '').toUpperCase() === 'DRAFT') {
    return PENDING_VISUAL; // DRAFT + process en cours → "En attente"
  }
  const key = String(s ?? '').toUpperCase();
  return MOVEMENT_STATUS_VISUAL[key as MovementStatus] ?? MOVEMENT_STATUS_VISUAL['DRAFT'];
}

/** Pot type → visuals. */
export const POT_TYPE_VISUAL: Record<PotType, { icon: string; color: string; soft: string; label: string }> = {
  COMPANY: { icon: 'pi-building', color: '#b45309', soft: '#fffbeb', label: 'Centrale' },
  PROJECT: { icon: 'pi-briefcase', color: '#0d9488', soft: '#f0fdfa', label: 'Chantier' },
};

export function potTypeVisual(t: PotType | string | null | undefined) {
  const key = String(t ?? '').toUpperCase();
  return POT_TYPE_VISUAL[key as PotType] ?? POT_TYPE_VISUAL['COMPANY'];
}

/** Party type → visuals + French label. */
export const PARTY_TYPE_VISUAL: Record<PartyType, { icon: string; color: string; soft: string; label: string }> = {
  SUB:      { icon: 'pi-users',    color: '#7c3aed', soft: '#f5f3ff', label: 'Sous-traitant' },
  SUPPLIER: { icon: 'pi-shopping-cart', color: '#b45309', soft: '#fffbeb', label: 'Fournisseur' },
  OTHER:    { icon: 'pi-user',     color: '#64748b', soft: '#f1f5f9', label: 'Autre' },
};

export function partyTypeVisual(t: PartyType | string | null | undefined) {
  const key = String(t ?? '').toUpperCase();
  return PARTY_TYPE_VISUAL[key as PartyType] ?? PARTY_TYPE_VISUAL['OTHER'];
}

// ============================================================================
//  HELPERS
// ============================================================================

/**
 * Format an amount the Tunisian BTP way: "1 500,000 TND"
 * (space thousands separator, comma decimals, 3 decimals like TND millimes).
 */
export function formatAmount(amount: number | null | undefined, currency: string = 'TND'): string {
  if (amount == null || isNaN(amount)) return '— ' + currency;
  const formatted = new Intl.NumberFormat('fr-FR', {
    minimumFractionDigits: 3,
    maximumFractionDigits: 3,
  }).format(amount);
  return `${formatted} ${currency}`;
}

/** Format a date (yyyy-MM-dd or ISO) as "27/10/2026". */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

/** Map any raw backend row defensively into a CashPot. */
export function mapPot(raw: any): CashPot {
  return {
    id: String(raw?.id ?? raw?.potId ?? ''),
    type: String(raw?.type ?? raw?.potType ?? 'COMPANY').toUpperCase() as PotType,
    projectId: raw?.projectId ?? raw?.project_id ?? null,
    name: String(raw?.name ?? raw?.potName ?? 'Caisse'),
    currency: String(raw?.currency ?? 'TND'),
    active: Boolean(raw?.active ?? true),
    balance: raw?.balance ?? undefined,
  };
}

/** Map any raw backend row defensively into a Party. */
export function mapParty(raw: any): Party {
  return {
    id: String(raw?.id ?? raw?.partyId ?? ''),
    name: String(raw?.name ?? raw?.partyName ?? '—'),
    type: String(raw?.type ?? raw?.partyType ?? 'OTHER').toUpperCase() as PartyType,
    phone: raw?.phone ?? undefined,
    active: Boolean(raw?.active ?? true),
  };
}

/** Map any raw backend row defensively into a CashMovement. */
export function mapMovement(raw: any): CashMovement {
  return {
    id: String(raw?.id ?? raw?.movementId ?? ''),
    movementType: String(raw?.movementType ?? raw?.movement_type ?? 'OUT').toUpperCase() as MovementType,
    amount: Number(raw?.amount ?? 0),
    currency: String(raw?.currency ?? 'TND'),
    movementDate: String(raw?.movementDate ?? raw?.movement_date ?? ''),
    fromPotId: raw?.fromPotId ?? raw?.from_pot_id ?? null,
    toPotId: raw?.toPotId ?? raw?.to_pot_id ?? null,
    projectId: raw?.projectId ?? raw?.project_id ?? null,
    partyId: raw?.partyId ?? raw?.party_id ?? null,
    label: raw?.label ?? raw?.libelle ?? undefined,
    note: raw?.note ?? undefined,
    status: String(raw?.status ?? raw?.movementStatus ?? 'DRAFT').toUpperCase() as MovementStatus,
    processInstanceId: raw?.processInstanceId ?? raw?.process_instance_id ?? null,
    claimedAmount: raw?.claimedAmount != null ? Number(raw.claimedAmount) : null,
    assessedAmount: raw?.assessedAmount != null ? Number(raw.assessedAmount) : null,
    varianceReason: raw?.varianceReason ?? raw?.variance_reason ?? null,
    documentId: raw?.documentId ?? raw?.document_id ?? null,
    documentName: raw?.documentName ?? raw?.document_name ?? null,
  };
}
