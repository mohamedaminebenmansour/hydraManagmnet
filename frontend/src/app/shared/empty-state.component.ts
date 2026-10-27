import { Component, EventEmitter, Input, Output } from '@angular/core';
import { ButtonModule } from 'primeng/button';

@Component({
  selector: 'app-empty-state',
  standalone: true,
  imports: [ButtonModule],
  template: `
    <div class="empty-state">
      <i class="pi {{ icon }}"></i>
      <p>{{ message }}</p>
      @if (buttonLabel) {
        <p-button [label]="buttonLabel" (onClick)="buttonAction.emit()"></p-button>
      }
      <ng-content></ng-content>
    </div>
  `,
  styles: [`
    .empty-state { text-align: center; padding: 60px 20px; color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 12px; }
    .empty-state i { font-size: 3rem; color: #94a3b8; }
    .empty-state p { font-size: 18px; margin: 0; }
  `]
})
export class EmptyStateComponent {
  @Input({ required: true }) icon!: string;
  @Input({ required: true }) message!: string;
  @Input() buttonLabel = '';
  @Output() buttonAction = new EventEmitter<void>();
}
