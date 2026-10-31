import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastModule } from 'primeng/toast'; // PHASE-B-DOC-ACTIONS

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, ToastModule], // PHASE-B-DOC-ACTIONS
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  title = 'hydra-web';
}
