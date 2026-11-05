/**
 * Tunisian identifiers: matricule fiscal (MF) and RIB validation.
 * Pure TypeScript, no Angular, ported from backend tn.hydra.organization.TunisianIds.
 */

const MF_PATTERN = /^(\d{7})([A-Z])([A-Z])([A-Z])(\d{3})$/;
const FORBIDDEN_CHECK_LETTERS = new Set(['I', 'O', 'U']);
const VALID_TVA_CODES = new Set(['A', 'P', 'B', 'D', 'N']);
const VALID_CATEGORIES = new Set(['M', 'P', 'C', 'N', 'E']);

/** Normalize a matricule fiscal: uppercase, remove spaces, '/', '.', '-'. */
export function normalizeMf(raw: string | null | undefined): string {
  if (!raw) return '';
  return raw.trim().toUpperCase()
    .replace(/\s/g, '')
    .replace(/\//g, '')
    .replace(/\./g, '')
    .replace(/-/g, '');
}

/** Validate a matricule fiscal. Returns an error message (French) or null if valid. */
export function validateMf(raw: string | null | undefined): string | null {
  const v = normalizeMf(raw);
  const m = MF_PATTERN.exec(v);
  if (!m) return 'Format attendu : 1234567/A/B/M/000.';
  const checkLetter = m[2];
  const tvaCode = m[3];
  const category = m[4];
  if (FORBIDDEN_CHECK_LETTERS.has(checkLetter)) {
    return 'La lettre de contrôle ne peut pas être I, O ou U.';
  }
  if (!VALID_TVA_CODES.has(tvaCode)) {
    return 'Code TVA invalide (A, P, B, D ou N).';
  }
  if (!VALID_CATEGORIES.has(category)) {
    return 'Catégorie invalide (M, P, C, N ou E).';
  }
  return null;
}

/** Format a normalized MF: '1234567/A/B/M/000'. */
export function formatMf(normalized: string | null | undefined): string | null {
  if (!normalized || normalized.length !== 13) return normalized ?? null;
  return normalized.substring(0, 7) + '/' +
         normalized.charAt(7) + '/' +
         normalized.charAt(8) + '/' +
         normalized.charAt(9) + '/' +
         normalized.substring(10);
}

// ── RIB ────────────────────────────────────────────────────────────────────

/** Normalize a RIB: remove spaces, '-', '.'. */
export function normalizeRib(raw: string | null | undefined): string {
  if (!raw) return '';
  return raw.trim()
    .replace(/\s/g, '')
    .replace(/-/g, '')
    .replace(/\./g, '');
}

/**
 * Validate a Tunisian RIB (20 digits).
 * Key check: expected = 97 - (first18 * 100 mod 97). Valid when expected == key.
 * Uses BigInt, never number — same algorithm as the Java backend.
 */
export function validateRib(raw: string | null | undefined): string | null {
  const v = normalizeRib(raw);
  if (!/^\d{20}$/.test(v)) return 'Le RIB doit contenir 20 chiffres.';
  const first18 = BigInt(v.substring(0, 18));
  const key = parseInt(v.substring(18, 20), 10);
  const mod = Number(first18 * 100n % 97n);
  const expected = 97 - mod;
  if (expected !== key) return 'Clé RIB incorrecte : vérifiez le numéro.';
  return null;
}

/**
 * Build an IBAN from a valid Tunisian RIB.
 * A valid Tunisian RIB is always a multiple of 97 when prefixed with TN00,
 * so the IBAN check digits are always 59.
 * IBAN = 'TN' + checkDigits + rib = 'TN59' + rib.
 */
export function ibanFromRib(rib: string | null | undefined): string {
  const v = normalizeRib(rib);
  return 'TN59' + v;
}

/** Format a RIB: '07 040 0058101111296 53' (groups of 2-3-13-2). */
export function formatRib(rib: string | null | undefined): string | null {
  const v = normalizeRib(rib);
  if (v.length !== 20) return rib ?? null;
  return v.substring(0, 2) + ' ' +
         v.substring(2, 5) + ' ' +
         v.substring(5, 18) + ' ' +
         v.substring(18, 20);
}

/** Format an IBAN: groups of 4: 'TN59 0704 0005 8101 1112 9653'. */
export function formatIban(iban: string | null | undefined): string | null {
  if (!iban || iban.length < 4) return iban ?? null;
  return iban.replace(/(.{4})/g, '$1 ').trim();
}

/** Mask a RIB for display: only the last 4 digits visible. */
export function maskRib(rib: string | null | undefined): string {
  const v = normalizeRib(rib);
  if (v.length < 4) return '****';
  return '****************' + v.substring(v.length - 4);
}
