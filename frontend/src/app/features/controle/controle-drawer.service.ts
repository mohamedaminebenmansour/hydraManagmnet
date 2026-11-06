import { Injectable, signal } from '@angular/core';

/**
 * ControleDrawerService — controls the line drawer (right side panel).
 * Any page opens a line with drawer.open(lineKey) — the key can be a money
 * line id OR a subcontractor line id. The drawer component (placed once in
 * ControleLayoutComponent) listens to openKey and renders the content.
 */
@Injectable({ providedIn: 'root' })
export class ControleDrawerService {
  /** The currently-open line key, or null when the drawer is closed. */
  readonly openKey = signal<string | null>(null);

  open(key: string): void {
    this.openKey.set(key);
  }

  close(): void {
    this.openKey.set(null);
  }
}
