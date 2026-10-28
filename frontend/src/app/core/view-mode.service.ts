import { Injectable, signal, WritableSignal } from '@angular/core';

/**
 * ViewModeService — shared per-page view toggle (Table | Cards).
 *
 * Stores the user's preference in localStorage with a per-page key, so:
 * - The finance person who likes tables gets tables on every visit.
 * - The owner who likes cards gets cards.
 * - Each page can have a different default + different preference.
 *
 * Why: Excel users are familiar with dense rows. Card users prefer visual
 * summaries. Both are valid — the tool should adapt to the user, not the
 * other way around. localStorage persistence means the choice is set once
 * and never asked again.
 *
 * Usage in a component:
 *   viewMode = this.viewModeService.forPage('movements', 'table');
 *   // In template: @if (viewMode() === 'table') { ... } @else { ... }
 *   // Toggle: viewModeService.toggle('movements')
 */
@Injectable({ providedIn: 'root' })
export class ViewModeService {
  private readonly PREFIX = 'hydra.view.';
  /** In-memory signals per page key (so all components on the same page share). */
  private readonly signals = new Map<string, WritableSignal<'table' | 'cards'>>();

  /**
   * Returns a signal for the given page key. Initializes from localStorage
   * (or the provided default) on first call. Subsequent calls return the
   * same signal instance.
   *
   * @param pageKey e.g. 'movements', 'parties', 'projects'
   * @param defaultMode what to use if no localStorage value exists
   */
  forPage(pageKey: string, defaultMode: 'table' | 'cards' = 'table') {
    const key = this.PREFIX + pageKey;
    if (this.signals.has(key)) {
      return this.signals.get(key)!;
    }
    const stored = typeof localStorage !== 'undefined'
      ? (localStorage.getItem(key) as 'table' | 'cards' | null)
      : null;
    const initial = stored ?? defaultMode;
    const sig = signal<'table' | 'cards'>(initial);
    this.signals.set(key, sig);
    return sig;
  }

  /** Toggle between table and cards for the given page key. */
  toggle(pageKey: string) {
    const key = this.PREFIX + pageKey;
    const sig = this.signals.get(key);
    if (!sig) return;
    const next = sig() === 'table' ? 'cards' : 'table';
    sig.set(next);
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(key, next);
    }
  }

  /** Explicitly set the view mode (e.g. from a select button). */
  set(pageKey: string, mode: 'table' | 'cards') {
    const key = this.PREFIX + pageKey;
    const sig = this.signals.get(key);
    if (sig) sig.set(mode);
    if (typeof localStorage !== 'undefined') {
      localStorage.setItem(key, mode);
    }
  }
}
