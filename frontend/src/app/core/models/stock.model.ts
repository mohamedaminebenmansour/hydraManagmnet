/**
 * ============================================================================
 *  STOCK MODELS — matériel / stock (articles, emplacements, mouvements)
 * ============================================================================
 *  Phase 1.3 UI for the /api/stock/* backend (Part 1.2).
 *  Language: Tunisian BTP — "Article", "Emplacement", "Mouvement".
 *  Mirrors cash.model.ts conventions: typed interfaces + defensive map* +
 *  VISUAL records with FR labels + colors + icons.
 * ============================================================================
 */

// ── Backend value types (mirror StockMoveType / StockLocationType enums) ──

export type StockMoveType = 'IN' | 'OUT' | 'TRANSFER' | 'ADJUST' | 'DIRECT_USE';
export type StockLocationType = 'CENTRAL' | 'SITE';
export type StockDocKind = 'BL' | 'BC' | 'FACTURE' | 'AUTRE';

// ── Domain entities (defensive mirrors of the backend records) ─────────────

export interface StockItem {
  id: string;
  code?: string | null;
  name: string;
  uom: string;
  active: boolean;
  createdAt?: string | null;
}

export interface ItemAlias {
  id: string;
  itemId: string;
  alias: string;
  createdAt?: string | null;
}

export interface StockLocation {
  id: string;
  type: StockLocationType;
  projectId?: string | null;
  projectName?: string | null;
  code?: string | null;
  name: string;
  active: boolean;
}

export interface BalanceRow {
  itemId: string;
  itemName: string;
  itemCode?: string | null;
  uom: string;
  locationId: string;
  locationName: string;
  locationType: StockLocationType;
  projectId?: string | null;
  projectName?: string | null;
  qty: number;
}

export interface OrgBalanceRow {
  itemId: string;
  itemName: string;
  itemCode?: string | null;
  uom: string;
  totalQty: number;
}

export interface StockMove {
  id: string;
  moveType: StockMoveType;
  qty: number;
  uom: string;
  moveDate?: string | null;
  fromLocationId?: string | null;
  fromLocationName?: string | null;
  toLocationId?: string | null;
  toLocationName?: string | null;
  itemId: string;
  itemName: string;
  documentId?: string | null;
  projectId?: string | null;
  partyId?: string | null;
  cashMovementId?: string | null;
  note?: string | null;
  createdBy?: string | null;
  createdByName?: string | null;
  createdAt?: string | null;
}

export interface MovePage {
  items: StockMove[];
  total: number;
  page: number;
  size: number;
}

export interface LinkedDocument {
  id: string;
  title?: string | null;
  reference?: string | null;
  docType?: string | null;
  isPrimary?: boolean;
}

// ── Request bodies (mirror StockService.*Request records) ──────────────────

export interface CreateItemBody {
  code?: string | null;
  name: string;
  uom: string;
}

export interface UpdateItemBody {
  code?: string | null;
  name: string;
  uom: string;
  active?: boolean;
}

export interface CreateAliasBody {
  alias: string;
}

export interface CreateLocationBody {
  type: StockLocationType;
  projectId?: string | null;
  code?: string | null;
  name: string;
}

export interface PostMoveBody {
  moveType: StockMoveType;
  qty: number;
  uom: string;
  moveDate?: string | null;
  fromLocationId?: string | null;
  toLocationId?: string | null;
  itemId: string;
  documentId?: string | null;
  partyId?: string | null;
  cashMovementId?: string | null;
  note?: string | null;
}

export interface ReverseBody {
  reason?: string | null;
}

export interface LinkDocumentBody {
  documentId: string;
}

// ── Visual language (colors + icons + French labels) ───────────────────────

export interface MoveTypeVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
  verb: string; // CTA label, e.g. "Entrée" / "Sortie"
  direction: 'in' | 'out' | 'move' | 'adjust';
}

/**
 * The five stock_move types. Color rule:
 *   IN           = green (good news, stock grew)
 *   OUT          = red (consumption / sale)
 *   DIRECT_USE   = orange (carburant case — used on site)
 *   TRANSFER     = cyan (neutral move between locations)
 *   ADJUST       = purple (variance correction)
 */
export const STOCK_MOVE_TYPE_VISUAL: Record<StockMoveType, MoveTypeVisual> = {
  IN: {
    icon: 'pi-arrow-circle-down', color: '#16a34a', soft: '#f0fdf4',
    label: 'Entrée', verb: 'Réceptionner', direction: 'in',
  },
  OUT: {
    icon: 'pi-arrow-circle-up', color: '#dc2626', soft: '#fef2f2',
    label: 'Sortie', verb: 'Sortir', direction: 'out',
  },
  DIRECT_USE: {
    icon: 'pi-fire', color: '#ea580c', soft: '#fff7ed',
    label: 'Consommation chantier', verb: 'Consommer', direction: 'out',
  },
  TRANSFER: {
    icon: 'pi-arrows-h', color: '#0891b2', soft: '#ecfeff',
    label: 'Transfert', verb: 'Transférer', direction: 'move',
  },
  ADJUST: {
    icon: 'pi-sliders-h', color: '#7c3aed', soft: '#f5f3ff',
    label: 'Ajustement', verb: 'Ajuster', direction: 'adjust',
  },
};

export function moveTypeVisual(t: StockMoveType | string | null | undefined): MoveTypeVisual {
  const key = String(t ?? '').toUpperCase();
  return STOCK_MOVE_TYPE_VISUAL[key as StockMoveType] ?? STOCK_MOVE_TYPE_VISUAL['IN'];
}

export interface LocationTypeVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
}

export const STOCK_LOCATION_TYPE_VISUAL: Record<StockLocationType, LocationTypeVisual> = {
  CENTRAL: { icon: 'pi-building',   color: '#b45309', soft: '#fffbeb', label: 'Dépôt central' },
  SITE:    { icon: 'pi-briefcase', color: '#0d9488', soft: '#f0fdfa', label: 'Chantier' },
};

export function locationTypeVisual(t: StockLocationType | string | null | undefined): LocationTypeVisual {
  const key = String(t ?? '').toUpperCase();
  return STOCK_LOCATION_TYPE_VISUAL[key as StockLocationType] ?? STOCK_LOCATION_TYPE_VISUAL['SITE'];
}

export const STOCK_DOC_KIND_LABEL: Record<StockDocKind, string> = {
  BL: 'Bon de livraison',
  BC: 'Bon de commande',
  FACTURE: 'Facture',
  AUTRE: 'Autre pièce',
};

// ── Stock-level thresholds (UX rule from Part 1.3 brief) ───────────────────
//
// A single threshold can't fit every UoM (sac vs m³ vs kg), but the spec
// asks for color-by-qty low/ok. We use a constant per-row default until
// Part 1.5 introduces item-level thresholds.
//
// Rules applied by stockLevelVisual():
//   qty === 0       → red    (rupture)
//   0 < qty <= LOW  → amber  (à commander)
//   qty > LOW        → green  (en stock)
export const LOW_STOCK_THRESHOLD = 10;

export interface StockLevelVisual {
  level: 'ok' | 'low' | 'zero';
  color: string;
  soft: string;
  label: string;
  icon: string;
}

export function stockLevelVisual(
  qty: number | null | undefined,
  threshold: number = LOW_STOCK_THRESHOLD,
): StockLevelVisual {
  const q = Number(qty ?? 0);
  if (q === 0) {
    return { level: 'zero', color: '#dc2626', soft: '#fef2f2', label: 'Rupture', icon: 'pi-times-circle' };
  }
  if (q <= threshold) {
    return { level: 'low', color: '#ea580c', soft: '#fff7ed', label: 'Faible', icon: 'pi-exclamation-triangle' };
  }
  return { level: 'ok', color: '#16a34a', soft: '#f0fdf4', label: 'En stock', icon: 'pi-check-circle' };
}

// ── Helpers ────────────────────────────────────────────────────────────────

/**
 * Format a quantity the Tunisian BTP way: "1 500 sac"
 * (space thousands separator, up to 3 decimals, then the unit).
 * Falls back to '—' on null/NaN.
 */
export function formatQty(qty: number | null | undefined, uom?: string | null): string {
  if (qty == null || isNaN(qty)) return '—';
  const n = new Intl.NumberFormat('fr-FR', {
    minimumFractionDigits: 0,
    maximumFractionDigits: 3,
  }).format(qty);
  return uom ? `${n} ${uom}` : n;
}

/** Format an ISO date as "27/10/2026" (matches cash.model.formatDate). */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
}

/** Format an ISO timestamp as "27/10/2026 14:32". */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleString('fr-FR', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

/** Today's date as yyyy-MM-dd (default moveDate for the move form). */
export function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

// ── Defensive mappers (any raw backend row → typed interface) ─────────────

export function mapItem(raw: any): StockItem {
  return {
    id: String(raw?.id ?? ''),
    code: raw?.code ?? null,
    name: String(raw?.name ?? '—'),
    uom: String(raw?.uom ?? ''),
    active: Boolean(raw?.active ?? true),
    createdAt: raw?.createdAt ?? raw?.created_at ?? null,
  };
}

export function mapAlias(raw: any): ItemAlias {
  return {
    id: String(raw?.id ?? ''),
    itemId: String(raw?.itemId ?? raw?.item_id ?? ''),
    alias: String(raw?.alias ?? ''),
    createdAt: raw?.createdAt ?? raw?.created_at ?? null,
  };
}

export function mapLocation(raw: any): StockLocation {
  const type = String(raw?.type ?? 'SITE').toUpperCase() as StockLocationType;
  return {
    id: String(raw?.id ?? ''),
    type,
    projectId: raw?.projectId ?? raw?.project_id ?? null,
    projectName: raw?.projectName ?? raw?.project_name ?? null,
    code: raw?.code ?? null,
    name: String(raw?.name ?? 'Emplacement'),
    active: Boolean(raw?.active ?? true),
  };
}

export function mapBalanceRow(raw: any): BalanceRow {
  return {
    itemId: String(raw?.itemId ?? raw?.item_id ?? ''),
    itemName: String(raw?.itemName ?? raw?.item_name ?? '—'),
    itemCode: raw?.itemCode ?? raw?.item_code ?? null,
    uom: String(raw?.uom ?? ''),
    locationId: String(raw?.locationId ?? raw?.location_id ?? ''),
    locationName: String(raw?.locationName ?? raw?.location_name ?? '—'),
    locationType: String(raw?.locationType ?? raw?.location_type ?? 'SITE').toUpperCase() as StockLocationType,
    projectId: raw?.projectId ?? raw?.project_id ?? null,
    projectName: raw?.projectName ?? raw?.project_name ?? null,
    qty: Number(raw?.qty ?? 0),
  };
}

export function mapOrgBalanceRow(raw: any): OrgBalanceRow {
  return {
    itemId: String(raw?.itemId ?? raw?.item_id ?? ''),
    itemName: String(raw?.itemName ?? raw?.item_name ?? '—'),
    itemCode: raw?.itemCode ?? raw?.item_code ?? null,
    uom: String(raw?.uom ?? ''),
    totalQty: Number(raw?.totalQty ?? raw?.total_qty ?? 0),
  };
}

export function mapMove(raw: any): StockMove {
  return {
    id: String(raw?.id ?? ''),
    moveType: String(raw?.moveType ?? raw?.move_type ?? 'IN').toUpperCase() as StockMoveType,
    qty: Number(raw?.qty ?? 0),
    uom: String(raw?.uom ?? ''),
    moveDate: raw?.moveDate ?? raw?.move_date ?? null,
    fromLocationId: raw?.fromLocationId ?? raw?.from_location_id ?? null,
    fromLocationName: raw?.fromLocationName ?? raw?.from_location_name ?? null,
    toLocationId: raw?.toLocationId ?? raw?.to_location_id ?? null,
    toLocationName: raw?.toLocationName ?? raw?.to_location_name ?? null,
    itemId: String(raw?.itemId ?? raw?.item_id ?? ''),
    itemName: String(raw?.itemName ?? raw?.item_name ?? '—'),
    documentId: raw?.documentId ?? raw?.document_id ?? null,
    projectId: raw?.projectId ?? raw?.project_id ?? null,
    partyId: raw?.partyId ?? raw?.party_id ?? null,
    cashMovementId: raw?.cashMovementId ?? raw?.cash_movement_id ?? null,
    note: raw?.note ?? null,
    createdBy: raw?.createdBy ?? raw?.created_by ?? null,
    createdByName: raw?.createdByName ?? raw?.created_by_name ?? null,
    createdAt: raw?.createdAt ?? raw?.created_at ?? null,
  };
}

export function mapMovePage(raw: any): MovePage {
  const items = Array.isArray(raw?.items) ? raw.items.map(mapMove) : [];
  return {
    items,
    total: Number(raw?.total ?? items.length ?? 0),
    page: Number(raw?.page ?? 0),
    size: Number(raw?.size ?? items.length ?? 50),
  };
}

export function mapLinkedDocument(raw: any): LinkedDocument {
  return {
    id: String(raw?.id ?? raw?.documentId ?? raw?.document_id ?? ''),
    title: raw?.title ?? raw?.name ?? null,
    reference: raw?.reference ?? raw?.code ?? null,
    docType: raw?.docType ?? raw?.doc_type ?? raw?.type ?? null,
    isPrimary: Boolean(raw?.isPrimary ?? raw?.is_primary ?? false),
  };
}
