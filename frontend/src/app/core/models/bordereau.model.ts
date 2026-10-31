export type BordereauVersionStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';

export interface BordereauVersion {
  id: string; projectId: string; versionNo: number; label: string;
  reason?: string | null; documentId?: string | null;
  status: BordereauVersionStatus; createdBy?: string | null; createdAt?: string | null;
}

export interface BordereauLine {
  id: string; versionId: string; sectionPath: string[];
  articleNo?: string | null; sortOrder: number; designation: string;
  unitRaw?: string | null; unit?: string | null;
  quantity?: number | null; unitPrice?: number | null;
  ouvrageId?: string | null; workNature?: string | null;
  pairedLineId?: string | null;
  specs?: Record<string, any>; importWarnings?: string[];
  createdAt?: string | null;
}

export function lineMontant(l: BordereauLine): number | null {
  if (l.quantity != null && l.unitPrice != null) return Number(l.quantity) * Number(l.unitPrice);
  return null;
}

export interface ImportPreview {
  versionId: string; versionNo: number; label: string;
  lineCount: number; warningCount: number; pairCount: number;
  lines: any[];
}

export interface BordereauDiff {
  articleNo?: string | null; designation?: string | null;
  change: 'ADDED' | 'REMOVED' | 'QUANTITY_CHANGED' | 'PRICE_CHANGED';
  oldQuantity?: number | null; newQuantity?: number | null;
  oldUnitPrice?: number | null; newUnitPrice?: number | null;
}

export function mapVersion(raw: any): BordereauVersion {
  return {
    id: String(raw?.id ?? ''), projectId: String(raw?.projectId ?? raw?.project_id ?? ''),
    versionNo: Number(raw?.versionNo ?? raw?.version_no ?? 0), label: String(raw?.label ?? ''),
    reason: raw?.reason ?? null, documentId: raw?.documentId ?? raw?.document_id ?? null,
    status: String(raw?.status ?? 'DRAFT').toUpperCase() as BordereauVersionStatus,
    createdBy: raw?.createdBy ?? raw?.created_by ?? null, createdAt: raw?.createdAt ?? raw?.created_at ?? null,
  };
}

export function mapLine(raw: any): BordereauLine {
  const sp = raw?.sectionPath ?? raw?.section_path;
  return {
    id: String(raw?.id ?? ''), versionId: String(raw?.versionId ?? raw?.version_id ?? ''),
    sectionPath: Array.isArray(sp) ? sp : [],
    articleNo: raw?.articleNo ?? raw?.article_no ?? null,
    sortOrder: Number(raw?.sortOrder ?? raw?.sort_order ?? 0),
    designation: String(raw?.designation ?? ''),
    unitRaw: raw?.unitRaw ?? raw?.unit_raw ?? null,
    unit: raw?.unit ?? null,
    quantity: raw?.quantity != null ? Number(raw.quantity) : null,
    unitPrice: raw?.unitPrice ?? raw?.unit_price ?? null,
    ouvrageId: raw?.ouvrageId ?? raw?.ouvrage_id ?? null,
    workNature: raw?.workNature ?? raw?.work_nature ?? null,
    pairedLineId: raw?.pairedLineId ?? raw?.paired_line_id ?? null,
    specs: raw?.specs ?? {},
    importWarnings: Array.isArray(raw?.importWarnings) ? raw.importWarnings
      : Array.isArray(raw?.import_warnings) ? raw.import_warnings : [],
    createdAt: raw?.createdAt ?? raw?.created_at ?? null,
  };
}

export function mapImportPreview(raw: any): ImportPreview {
  return {
    versionId: String(raw?.versionId ?? ''), versionNo: Number(raw?.versionNo ?? 0),
    label: String(raw?.label ?? ''), lineCount: Number(raw?.lineCount ?? 0),
    warningCount: Number(raw?.warningCount ?? 0), pairCount: Number(raw?.pairCount ?? 0),
    lines: Array.isArray(raw?.lines) ? raw.lines : [],
  };
}

export function mapDiff(raw: any): BordereauDiff {
  return {
    articleNo: raw?.articleNo ?? raw?.article_no ?? null,
    designation: raw?.designation ?? null,
    change: String(raw?.change ?? '').toUpperCase() as BordereauDiff['change'],
    oldQuantity: raw?.oldQuantity != null ? Number(raw.oldQuantity) : null,
    newQuantity: raw?.newQuantity != null ? Number(raw.newQuantity) : null,
    oldUnitPrice: raw?.oldUnitPrice != null ? Number(raw.oldUnitPrice) : null,
    newUnitPrice: raw?.newUnitPrice != null ? Number(raw.newUnitPrice) : null,
  };
}
