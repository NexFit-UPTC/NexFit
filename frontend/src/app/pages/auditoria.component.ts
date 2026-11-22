import { DatePipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuditoriaService, EventoAuditoria, TIPOS_EVENTO } from '../core/auditoria.service';
import { Pagina } from '../core/pagina';
import { PaginacionComponent } from '../layout/paginacion.component';

/** Consulta de la auditoría (solo lectura, RN-40): filtra por fechas y tipo de evento. */
@Component({
  selector: 'app-auditoria',
  imports: [FormsModule, DatePipe, PaginacionComponent],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Auditoría</h1>
      </div>
      <div class="nf-filtros">
        <label>
          Tipo de evento
          <select name="tipo" [ngModel]="tipo()" (ngModelChange)="cambiar({ tipo: $event })">
            <option value="">Todos</option>
            @for (t of tipos; track t[0]) {
              <option [value]="t[0]">{{ t[1] }}</option>
            }
          </select>
        </label>
        <label>
          Desde
          <input name="desde" type="date" [ngModel]="desde()" (ngModelChange)="cambiar({ desde: $event })" />
        </label>
        <label>
          Hasta
          <input name="hasta" type="date" [ngModel]="hasta()" (ngModelChange)="cambiar({ hasta: $event })" />
        </label>
      </div>
      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (eventos().length === 0) {
        <p class="nf-vacio">No hay eventos para este filtro.</p>
      } @else {
        <table class="nf-tabla">
          <thead>
            <tr>
              <th>Fecha</th>
              <th>Evento</th>
              <th>Entidad</th>
              <th>Usuario</th>
              <th>Detalle</th>
            </tr>
          </thead>
          <tbody>
            @for (e of eventos(); track e.id) {
              <tr>
                <td>{{ e.occurredAt | date: 'dd/MM/yyyy HH:mm:ss' }}</td>
                <td>{{ nombre(e.eventType) }}</td>
                <td>{{ e.entityType ? e.entityType + ' #' + e.entityId : '—' }}</td>
                <td>{{ e.actorUserId ?? 'Sistema' }}</td>
                <td class="nf-ayuda">{{ detalle(e) }}</td>
              </tr>
            }
          </tbody>
        </table>
        @if (pagina(); as p) {
          <app-paginacion
            [pagina]="p.page"
            [paginas]="p.totalPages"
            [total]="p.totalElements"
            etiqueta="eventos"
            (cambiar)="irA($event)"
          />
        }
      }
    </section>
  `
})
export class AuditoriaComponent implements OnInit {
  private readonly api = inject(AuditoriaService);

  protected readonly tipos = Object.entries(TIPOS_EVENTO);
  protected readonly tipo = signal('');
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  protected readonly pagina = signal<Pagina<EventoAuditoria> | null>(null);
  protected readonly eventos = computed(() => this.pagina()?.content ?? []);
  private paginaActual = 0;
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  ngOnInit() {
    this.recargar();
  }

  protected nombre(tipo: string): string {
    return TIPOS_EVENTO[tipo] ?? tipo;
  }

  protected detalle(e: EventoAuditoria): string {
    const reason = e.metadata['reason'];
    return typeof reason === 'string' ? reason : '';
  }

  protected cambiar(cambio: { tipo?: string; desde?: string; hasta?: string }) {
    if (cambio.tipo !== undefined) this.tipo.set(cambio.tipo);
    if (cambio.desde !== undefined) this.desde.set(cambio.desde);
    if (cambio.hasta !== undefined) this.hasta.set(cambio.hasta);
    // Un filtro nuevo vuelve a la primera página; si no, se podría quedar en una que ya no existe.
    this.paginaActual = 0;
    this.recargar();
  }

  protected irA(pagina: number) {
    this.paginaActual = Math.max(pagina, 0);
    this.recargar();
  }

  private recargar() {
    this.cargando.set(true);
    this.api
      .buscar(
        {
          ...(this.tipo() ? { type: this.tipo() } : {}),
          ...(this.desde() ? { from: this.desde() } : {}),
          ...(this.hasta() ? { to: this.hasta() } : {})
        },
        this.paginaActual
      )
      .subscribe({
        next: (datos) => {
          this.pagina.set(datos);
          this.error.set(null);
          this.cargando.set(false);
        },
        error: () => {
          this.error.set('No se pudo cargar la auditoría.');
          this.cargando.set(false);
        }
      });
  }
}
