import { Component, EventEmitter, Input, Output } from '@angular/core';
import { ButtonModule } from 'primeng/button';

/**
 * A failed load, never shown as an empty list:
 * <app-load-error message="Impossible de charger les tickets." (retry)="load()" />
 */
@Component({
  selector: 'app-load-error',
  standalone: true,
  imports: [ButtonModule],
  template: `
    <div class="load-error" role="alert">
      <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>
      <span class="le-text">{{ message }}</span>
      <p-button label="Réessayer" icon="pi pi-refresh" [outlined]="true" size="small" (onClick)="retry.emit()" />
    </div>
  `,
  styles: [`
    .load-error { display: flex; align-items: center; justify-content: center; gap: 10px; flex-wrap: wrap;
      padding: 14px 16px; margin: 8px 0; border-radius: 10px;
      color: #b91c1c; background: #fef2f2; border: 1px solid #fecaca; }
    .load-error i { font-size: 1.1rem; }
    .le-text { font-weight: 600; }
  `]
})
export class LoadErrorComponent {
  @Input() message = 'Impossible de charger les données.';
  @Output() retry = new EventEmitter<void>();
}
