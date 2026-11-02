/**
 * ============================================================================
 *  LANGAGE VISUEL SIGNALEMENT — types + statuts (T4)
 * ============================================================================
 *  Couleurs et icônes exactes de la spec :
 *    PROBLEME #ea580c pi-exclamation-triangle · RESERVE #dc2626 pi-flag
 *    DEMANDE #2563eb pi-question-circle   · VISA_DOCUMENT #7c3aed pi-file-check
 *  Statuts (PROBLEME/RESERVE/DEMANDE + VISA) → tokens CSS --proc-* :
 *    OPEN/RESOLVED = --proc-pending (orange) · IN_PROGRESS/APPROVED/IFC = --proc-running (bleu)
 *    CLOSED/AS_BUILT = --proc-done (vert) · CANCELLED/DRAFT = --proc-muted (gris)
 *  Le fond doux (soft) = la couleur à ~10 % d'opacité.
 *  Les libellés viennent de GET /api/tickets/catalog (chargés une fois,
 *  mis en cache par TicketService.catalog → cacheSignalementLabels).
 * ============================================================================
 */
export interface SignalementVisual {
  icon: string;
  color: string;
  soft: string;
  label: string;
}

interface V { icon: string; color: string; label: string; }

const TYPE_VISUAL: Record<string, V> = {
  PROBLEME: { icon: 'pi-exclamation-triangle', color: '#ea580c', label: 'Problème chantier' },
  RESERVE: { icon: 'pi-flag', color: '#dc2626', label: 'Réserve / non-conformité' },
  DEMANDE: { icon: 'pi-question-circle', color: '#2563eb', label: 'Demande' },
  VISA_DOCUMENT: { icon: 'pi-file-check', color: '#7c3aed', label: 'Visa de document' }
};

const STATUS_VISUAL: Record<string, V> = {
  // signalement lifecycle
  OPEN: { icon: 'pi-exclamation-triangle', color: 'var(--proc-pending)', label: 'Ouvert' },
  IN_PROGRESS: { icon: 'pi-spinner', color: 'var(--proc-running)', label: 'En cours' },
  RESOLVED: { icon: 'pi-check', color: 'var(--proc-pending)', label: 'Résolu — à vérifier' },
  CLOSED: { icon: 'pi-check-circle', color: 'var(--proc-done)', label: 'Clos' },
  CANCELLED: { icon: 'pi-ban', color: 'var(--proc-muted)', label: 'Annulé' },
  // VISA_DOCUMENT transmittal
  DRAFT: { icon: 'pi-pencil', color: 'var(--proc-muted)', label: 'Brouillon' },
  IN_REVIEW: { icon: 'pi-clock', color: 'var(--proc-pending)', label: 'En revue' },
  APPROVED: { icon: 'pi-check', color: 'var(--proc-running)', label: 'Approuvé' },
  IFC: { icon: 'pi-check', color: 'var(--proc-running)', label: 'Bon pour exécution' },
  AS_BUILT: { icon: 'pi-check-circle', color: 'var(--proc-done)', label: 'As-built' }
};

/**
 * Populate the label cache from the catalogue (called once by TicketService).
 * Icons + colours stay from the spec; only the labels are overridden.
 */
export function cacheSignalementLabels(catalog: any[] | null | undefined): void {
  for (const t of catalog ?? []) {
    if (t?.code && t?.label) {
      const base = TYPE_VISUAL[t.code] ?? { icon: 'pi-file', color: '#64748b' };
      TYPE_VISUAL[t.code] = { ...base, label: t.label };
    }
    for (const s of t?.statuses ?? []) {
      if (s?.code && s?.label) {
        const base = STATUS_VISUAL[s.code] ?? { icon: 'pi-clock', color: 'var(--proc-pending)' };
        STATUS_VISUAL[s.code] = { ...base, label: s.label };
      }
    }
  }
}

/** ~10 % opacity background: 8-digit hex for hex colours, color-mix for CSS vars. */
function soft(color: string): string {
  if (color.startsWith('#')) { return color + '1a'; }
  return `color-mix(in srgb, ${color} 12%, transparent)`;
}

/** Visual identity of a signalement type (icon, colour, soft bg, FR label). */
export function typeVisual(type: string | null | undefined): SignalementVisual {
  const code = (type ?? 'VISA_DOCUMENT').toUpperCase();
  const v = TYPE_VISUAL[code] ?? TYPE_VISUAL['VISA_DOCUMENT'];
  return { icon: v.icon, color: v.color, soft: soft(v.color), label: v.label };
}

/** Visual identity of a status (icon, colour token, soft bg, FR label). */
export function statusVisual(status: string | null | undefined): SignalementVisual {
  const code = (status ?? 'OPEN').toUpperCase();
  const v = STATUS_VISUAL[code] ?? STATUS_VISUAL['OPEN'];
  return { icon: v.icon, color: v.color, soft: soft(v.color), label: v.label };
}
