"""Phase B2-B7 — Frontend surgical edits.

1. stock.model.ts: add linkedDocCount to StockMove + mapMove.
2. Create document-picker-dialog.component.ts (NEW file).
3. move-form.component.ts: fix docType bug, add project selector for CENTRAL,
   add "pick existing" link, compensation toast for orphaned uploads.
4. stock-moves.component.ts: "Aucun justificatif" empty state, "Ajouter un
   justificatif" button in dialog, remove N+1 preloadDocCounts.
"""
from pathlib import Path

ROOT = Path('/tmp/my-project/frontend/src/app')

# ─── 1. stock.model.ts — add linkedDocCount ──────────────────────────────
p = ROOT / 'core/models/stock.model.ts'
s = p.read_text(encoding='utf-8')

# Add field to interface
old_iface_end = """  createdBy?: string | null;
  createdByName?: string | null;
  createdAt?: string | null;
}

export interface MovePage {"""
new_iface_end = """  createdBy?: string | null;
  createdByName?: string | null;
  createdAt?: string | null;
  linkedDocCount?: number;
}

export interface MovePage {"""
assert old_iface_end in s, "StockMove interface end not found"
s = s.replace(old_iface_end, new_iface_end, 1)

# Add to mapMove (add before the closing `}` of mapMove)
old_map_end = """    createdBy: raw?.createdBy ?? raw?.created_by ?? null,
    createdByName: raw?.createdByName ?? raw?.created_by_name ?? null,
    createdAt: raw?.createdAt ?? raw?.created_at ?? null,
  };
}

export function mapMovePage"""
new_map_end = """    createdBy: raw?.createdBy ?? raw?.created_by ?? null,
    createdByName: raw?.createdByName ?? raw?.created_by_name ?? null,
    createdAt: raw?.createdAt ?? raw?.created_at ?? null,
    linkedDocCount: raw?.linkedDocCount ?? raw?.linked_doc_count ?? null,
  };
}

export function mapMovePage"""
assert old_map_end in s, "mapMove end not found"
s = s.replace(old_map_end, new_map_end, 1)

# Enrich mapLinkedDocument to read title/reference/docType from the enriched
# documentsOf response (already defensively tolerates missing fields)
old_map_doc = """export function mapLinkedDocument(raw: any): LinkedDocument {
  return {
    id: String(raw?.id ?? raw?.documentId ?? raw?.document_id ?? ''),
    title: raw?.title ?? raw?.name ?? null,
    reference: raw?.reference ?? raw?.code ?? null,
    docType: raw?.docType ?? raw?.doc_type ?? raw?.type ?? null,
    isPrimary: Boolean(raw?.isPrimary ?? raw?.is_primary ?? raw?.primary ?? false),
  };
}"""
new_map_doc = """export function mapLinkedDocument(raw: any): LinkedDocument {
  return {
    id: String(raw?.id ?? raw?.documentId ?? raw?.document_id ?? ''),
    title: raw?.title ?? raw?.name ?? null,
    reference: raw?.reference ?? raw?.code ?? null,
    docType: raw?.docType ?? raw?.doc_type ?? raw?.type ?? null,
    // Backend uses "primary" (boolean) in the enriched response;
    // "isPrimary" / "is_primary" kept for backwards compat.
    isPrimary: Boolean(raw?.isPrimary ?? raw?.is_primary ?? raw?.primary ?? false),
  };
}"""
assert old_map_doc in s, "mapLinkedDocument not found"
s = s.replace(old_map_doc, new_map_doc, 1)

p.write_text(s, encoding='utf-8')
print('OK  stock.model.ts — +linkedDocCount field + enriched mapLinkedDocument')
