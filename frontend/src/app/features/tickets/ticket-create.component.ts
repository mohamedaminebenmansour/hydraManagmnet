import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { HttpEventType } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { SelectModule } from 'primeng/select';
import { DatePickerModule } from 'primeng/datepicker';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { ProjectService } from '@app/core/services/project.service';
import { UserService } from '@app/core/services/user.service';
import { TicketService } from '@app/core/services/ticket.service';
import { PartyService } from '@app/core/services/party.service';
import { SelectOption } from '@app/core/models/select-option';
import { Project } from '@app/core/models/project.model';
import { Ouvrage } from '@app/core/models/ouvrage.model';
import { Party } from '@app/core/models/cash.model';
import { TicketCatalogType } from '@app/core/models/ticket.model';
import { compressImage } from '@app/shared/image-compress';
import { typeVisual } from './signalement-visual';

interface PhotoItem { file: File; url: string; }

@Component({
  selector: 'app-ticket-create',
  standalone: true,
  imports: [
    FormsModule, ButtonModule, InputTextModule, TextareaModule, SelectModule, DatePickerModule
  ],
  templateUrl: './ticket-create.component.html',
  styleUrl: './ticket-create.component.scss'
})
export class TicketCreateComponent implements OnInit {
  private ticketService = inject(TicketService);
  private projectService = inject(ProjectService);
  private userService = inject(UserService);
  private partyService = inject(PartyService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private msgs = inject(MessageService);

  catalog = signal<TicketCatalogType[]>([]);
  selectedType = signal<string | null>(null);
  title = signal('');
  category = signal<string | null>(null);
  origin = signal('INTERNE');
  photos = signal<PhotoItem[]>([]);
  compressing = signal(false);
  projects = signal<Project[]>([]);
  ouvrages = signal<Ouvrage[]>([]);
  users = signal<SelectOption[]>([]);
  parties = signal<Party[]>([]);
  selectedProject = signal<string | null>(null);
  selectedOuvrage = signal<string | null>(null);
  locationText = signal('');
  assignee = signal<string | null>(null);
  responsibleParty = signal<string | null>(null);
  dueAt = signal<Date | null>(null);
  description = signal('');
  showDetails = signal(false);
  sending = signal(false);
  progress = signal(0);
  error = signal<string | null>(null);
  networkLost = signal(false);
  attemptedSave = signal(false);

  readonly maxPhotos = 10;
  canWrite = computed(() => this.auth.can(PERM.TICKET_WRITE));

  projectOpts = computed(() =>
    this.projects().filter(p => p.status === 'ACTIF')
      .map(p => ({ label: p.code ? `${p.code} — ${p.name}` : p.name, value: p.id })));
  ouvrageOpts = computed(() =>
    this.ouvrages().map(o => ({ label: o.name, value: o.id })));
  assigneeOpts = computed(() => this.users());
  partyOpts = computed(() =>
    this.parties().map(p => ({ label: p.name, value: p.id })));

  /** The selected catalog type (for categories, defaultDays, etc.). */
  selectedCatalogType = computed(() =>
    this.catalog().find(t => t.code === this.selectedType()) ?? null);

  /** Category chips for the selected type (hidden for VISA). */
  categoryChips = computed(() => this.selectedCatalogType()?.categories ?? []);

  /** Default delay text: « dans 2 jours ». */
  dueInLabel = computed(() => {
    const d = this.selectedCatalogType()?.defaultDays;
    return d ? `dans ${d} jour${d > 1 ? 's' : ''}` : '';
  });

  /** Title placeholder by type. */
  titlePlaceholder = computed(() => {
    switch (this.selectedType()) {
      case 'PROBLEME': return 'Ex. : Tractopelle en panne au PK 2+300';
      case 'RESERVE': return 'Ex. : Réserves sur le béton de la dalle RDC';
      case 'DEMANDE': return 'Ex. : Besoin de 50 sacs de ciment';
      case 'VISA_DOCUMENT': return 'Ex. : Visa plan de coffrage dallage';
      default: return 'Titre du signalement';
    }
  });

  /** Help lines for the 4 type tiles. */
  readonly typeHelp: Record<string, string> = {
    PROBLEME: 'Quelque chose bloque ou ralentit le chantier',
    RESERVE: 'Un défaut à corriger avant réception',
    DEMANDE: "Besoin de matériel, d'une information ou d'une décision",
    VISA_DOCUMENT: 'Un plan ou document à faire viser'
  };

  ngOnInit(): void {
    this.ticketService.catalog().subscribe({ next: c => this.catalog.set(c ?? []), error: () => {} });
    this.projectService.list().subscribe({ next: list => {
      this.projects.set(list ?? []);
      // pre-fill project from query param or first ACTIF project
      const qp = this.route.snapshot.queryParamMap.get('projectId');
      const first = (list ?? []).find(p => p.status === 'ACTIF');
      const pid = qp ?? first?.id ?? null;
      this.selectedProject.set(pid);
      if (pid) { this.loadOuvrages(pid); }
    }, error: () => {} });
    this.userService.options().subscribe({
      next: u => this.users.set((u ?? []).map(x => ({ label: x.fullName, value: x.id }))), error: () => {} });
    this.partyService.loadParties().subscribe({ next: p => this.parties.set(p ?? []), error: () => {} });
  }

  loadOuvrages(projectId: string): void {
    this.projectService.listOuvrages(projectId).subscribe({
      next: o => this.ouvrages.set(o ?? []), error: () => this.ouvrages.set([])
    });
  }

  typeV = typeVisual;

  onSelectType(code: string): void {
    this.selectedType.set(code);
    this.category.set(null);
    this.origin.set('INTERNE');
    this.attemptedSave.set(false);
    // pre-fill due date: today + defaultDays
    const ct = this.catalog().find(t => t.code === code);
    if (ct?.defaultDays) {
      const d = new Date(); d.setDate(d.getDate() + ct.defaultDays);
      this.dueAt.set(d);
    } else {
      this.dueAt.set(null);
    }
  }

  onCategoryClick(code: string): void {
    this.category.set(this.category() === code ? null : code);
  }

  onProjectChange(v: string | null): void {
    this.selectedProject.set(v);
    this.selectedOuvrage.set(null);
    if (v) { this.loadOuvrages(v); } else { this.ouvrages.set([]); }
  }

  async onPickPhoto(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) { return; }
    const remaining = this.maxPhotos - this.photos().length;
    if (remaining <= 0) { return; }
    this.compressing.set(true);
    try {
      for (let i = 0; i < Math.min(input.files.length, remaining); i++) {
        const compressed = await compressImage(input.files[i]);
        const url = URL.createObjectURL(compressed);
        this.photos.update(arr => [...arr, { file: compressed, url }]);
      }
    } finally {
      this.compressing.set(false);
      input.value = ''; // allow re-picking the same file
    }
  }

  removePhoto(index: number): void {
    this.photos.update(arr => {
      const item = arr[index];
      if (item) { URL.revokeObjectURL(item.url); }
      return arr.filter((_, i) => i !== index);
    });
  }

  get photoCount(): number { return this.photos().length; }

  private toYmd(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }

  private validate(): string | null {
    if (!this.selectedType()) { return 'Choisissez un type de signalement.'; }
    const t = this.title().trim();
    if (t.length < 5 || t.length > 120) { return 'Le titre doit faire entre 5 et 120 caractères.'; }
    if (this.selectedType() !== 'VISA_DOCUMENT' && !this.category()) {
      return 'Choisissez une catégorie.';
    }
    if (!this.selectedProject()) { return 'Choisissez un chantier.'; }
    if (this.photos().length > this.maxPhotos) { return `Maximum ${this.maxPhotos} photos.`; }
    return null;
  }

  save(): void {
    this.attemptedSave.set(true);
    const err = this.validate();
    if (err) { this.error.set(err); return; }
    this.error.set(null);
    this.networkLost.set(false);
    this.sending.set(true);
    this.progress.set(0);

    const ticket: Record<string, any> = {
      title: this.title().trim(),
      description: this.description().trim() || null,
      projectId: this.selectedProject(),
      assignedToId: this.assignee() || null,
      priority: 'MEDIUM',
      dueAt: this.dueAt() ? this.toYmd(this.dueAt()!) : null,
      type: this.selectedType(),
      category: this.selectedType() === 'VISA_DOCUMENT' ? null : this.category(),
      origin: this.selectedType() === 'RESERVE' ? this.origin() : null,
      locationText: this.locationText().trim() || null,
      ouvrageId: this.selectedOuvrage() || null,
      responsiblePartyId: this.responsibleParty() || null,
    };
    const files = this.photos().map(p => p.file);

    this.ticketService.createWithPhotos(ticket, files).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.progress.set(Math.round(event.loaded / event.total * 100));
        } else if (event.type === HttpEventType.Response) {
          this.sending.set(false);
          const id = event.body?.id;
          this.msgs.add({ severity: 'success', summary: 'Signalement créé' });
          this.router.navigate(['/tickets', id]);
        }
      },
      error: (err) => {
        this.sending.set(false);
        if (err?.status === 0 || err?.status === undefined && !err?.error) {
          this.networkLost.set(true);
          this.error.set('Connexion perdue. Vos données sont gardées, réessayez.');
        } else {
          this.error.set(err?.error?.message ?? err?.message ?? 'Création impossible.');
        }
      }
    });
  }

  retry(): void { this.save(); }

  cancel(): void { this.router.navigate(['/tickets']); }
}
