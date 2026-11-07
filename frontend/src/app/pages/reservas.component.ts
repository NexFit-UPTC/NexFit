import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorDeApi } from '../core/establecimientos.service';
import { Reserva, ReservasService } from '../core/reservas.service';
import { SessionService } from '../core/session.service';

/**
 * Reservas (US-33, US-34): el Cliente ve y cancela las suyas (confirmadas y canceladas); el personal ve las del
 * gimnasio (el Entrenador, las de sus clases) y el Staff puede cancelarlas.
 */
@Component({
  selector: 'app-reservas',
  imports: [FormsModule, DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>{{ esCliente() ? 'Mis reservas' : 'Reservas' }}</h1>
      </div>
      <div class="nf-filtros">
        <label>
          Estado
          <select name="estado" [ngModel]="estado()" (ngModelChange)="cambiarEstado($event)">
            <option value="">Todas</option>
            <option value="confirmed">Confirmadas</option>
            <option value="cancelled">Canceladas</option>
          </select>
        </label>
      </div>
      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (reservas().length === 0) {
        <p class="nf-vacio">No hay reservas.</p>
      } @else {
        <table class="nf-tabla">
          <thead>
            <tr>
              <th>Clase</th>
              @if (!esCliente()) {
                <th>Cliente</th>
              }
              <th>Sede</th>
              <th>Cuándo</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (r of reservas(); track r.id) {
              <tr>
                <td>{{ r.className }}</td>
                @if (!esCliente()) {
                  <td>{{ r.memberName }}</td>
                }
                <td>{{ r.branchName }}</td>
                <td>{{ r.classStartsAt | date: 'EEE dd/MM HH:mm' }}</td>
                <td>
                  <span class="nf-insignia" [class.ok]="r.status === 'confirmed'" [class.alerta]="r.status === 'cancelled'">
                    {{ r.status === 'confirmed' ? 'Confirmada' : 'Cancelada' }}
                  </span>
                </td>
                <td class="nf-acciones">
                  @if (puedeCancelar() && r.status === 'confirmed') {
                    <button type="button" class="nf-boton-sec" (click)="cancelar(r)">Cancelar reserva</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `
})
export class ReservasComponent implements OnInit {
  private readonly api = inject(ReservasService);
  private readonly sesion = inject(SessionService);

  protected readonly esCliente = computed(() => {
    const roles = this.sesion.roles();
    return roles.includes('client') && !roles.includes('staff') && !roles.includes('admin');
  });
  protected readonly puedeCancelar = computed(() => {
    const roles = this.sesion.roles();
    return roles.includes('client') || roles.includes('staff');
  });
  protected readonly reservas = signal<Reserva[]>([]);
  protected readonly estado = signal<'' | 'confirmed' | 'cancelled'>('');
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  ngOnInit() {
    this.recargar();
  }

  protected cambiarEstado(estado: '' | 'confirmed' | 'cancelled') {
    this.estado.set(estado);
    this.recargar();
  }

  protected cancelar(r: Reserva) {
    this.error.set(null);
    this.api.cancelar(r.id).subscribe({
      next: () => this.recargar(),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  private recargar() {
    this.cargando.set(true);
    this.api.listar(this.estado() ? { status: this.estado() as 'confirmed' | 'cancelled' } : {}).subscribe({
      next: (datos) => {
        this.reservas.set(datos);
        this.error.set(null);
        this.cargando.set(false);
      },
      error: () => {
        this.error.set('No se pudo cargar el listado.');
        this.cargando.set(false);
      }
    });
  }
}
