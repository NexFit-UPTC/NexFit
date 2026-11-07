import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CifrasPlataforma, PanelGimnasio, PanelService } from '../core/panel.service';
import { SessionService } from '../core/session.service';

/**
 * Panel de indicadores (US-45, US-46): el Administrador ve los de su gimnasio; el Master, cifras agregadas de la
 * plataforma, sin listar personas.
 */
@Component({
  selector: 'app-panel',
  imports: [CurrencyPipe, DecimalPipe, RouterLink],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>{{ esMaster() ? 'Panel de la plataforma' : 'Panel del gimnasio' }}</h1>
      </div>
      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (gimnasio(); as g) {
        <p class="nf-ayuda">Datos al {{ g.date }} (zona horaria {{ g.timezone }}).</p>
        <div class="nf-indicadores">
          <a class="nf-indicador" routerLink="/clientes">
            <span class="nf-indicador-valor">{{ g.activeClients | number }}</span>
            <span class="nf-indicador-nombre">Clientes activos</span>
          </a>
          <a class="nf-indicador" routerLink="/membresias">
            <span class="nf-indicador-valor">{{ g.currentMemberships | number }}</span>
            <span class="nf-indicador-nombre">Membresías vigentes</span>
          </a>
          <a class="nf-indicador" routerLink="/membresias">
            <span class="nf-indicador-valor">{{ g.expiringMemberships | number }}</span>
            <span class="nf-indicador-nombre">Por vencer en {{ g.expiringInDays }} días</span>
          </a>
          <a class="nf-indicador" routerLink="/pagos">
            <span class="nf-indicador-valor">{{ g.monthRevenue | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</span>
            <span class="nf-indicador-nombre">Ingresos del mes (pagos completados)</span>
          </a>
          <a class="nf-indicador" routerLink="/asistencia">
            <span class="nf-indicador-valor">{{ g.todayCheckIns | number }}</span>
            <span class="nf-indicador-nombre">Asistencias de hoy</span>
          </a>
          <a class="nf-indicador" routerLink="/clases">
            <span class="nf-indicador-valor">{{ g.upcomingOccupancy.percent | number: '1.0-1' }}%</span>
            <span class="nf-indicador-nombre">
              Ocupación próximos 7 días · {{ g.upcomingOccupancy.confirmedBookings }} de
              {{ g.upcomingOccupancy.capacity }} cupos en {{ g.upcomingOccupancy.classes }} clases
            </span>
          </a>
        </div>
      } @else if (plataforma(); as p) {
        <div class="nf-indicadores">
          <div class="nf-indicador">
            <span class="nf-indicador-valor">{{ p.gyms | number }}</span>
            <span class="nf-indicador-nombre">Gimnasios activos</span>
          </div>
          <div class="nf-indicador">
            <span class="nf-indicador-valor">{{ p.branches | number }}</span>
            <span class="nf-indicador-nombre">Sedes activas</span>
          </div>
          <div class="nf-indicador">
            <span class="nf-indicador-valor">{{ p.clients | number }}</span>
            <span class="nf-indicador-nombre">Clientes activos</span>
          </div>
          <div class="nf-indicador">
            <span class="nf-indicador-valor">{{ p.currentMemberships | number }}</span>
            <span class="nf-indicador-nombre">Membresías vigentes</span>
          </div>
        </div>
      }
    </section>
  `
})
export class PanelComponent implements OnInit {
  private readonly api = inject(PanelService);
  private readonly sesion = inject(SessionService);

  protected readonly esMaster = computed(() => this.sesion.roles().includes('master'));
  protected readonly gimnasio = signal<PanelGimnasio | null>(null);
  protected readonly plataforma = signal<CifrasPlataforma | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  ngOnInit() {
    const alFallar = () => {
      this.error.set('No se pudieron cargar los indicadores.');
      this.cargando.set(false);
    };
    if (this.esMaster()) {
      this.api.plataforma().subscribe({
        next: (p) => {
          this.plataforma.set(p);
          this.cargando.set(false);
        },
        error: alFallar
      });
    } else {
      this.api.gimnasio().subscribe({
        next: (g) => {
          this.gimnasio.set(g);
          this.cargando.set(false);
        },
        error: alFallar
      });
    }
  }
}
