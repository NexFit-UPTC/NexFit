import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EntrenamientosService, Progreso, RegistroPeso, ResumenEntrenamiento } from '../core/entrenamientos.service';
import { Reserva, ReservasService } from '../core/reservas.service';
import { Rutina, RutinasService } from '../core/rutinas.service';
import { SessionService } from '../core/session.service';

interface DiaCalendario {
  numero: number;
  entrenado: boolean;
  futuro: boolean;
  etiqueta: string;
}

const MESES = ['enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio', 'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre'];

/**
 * Inicio del Cliente (mockup «Cliente · Inicio»): racha, meta semanal, peso, rutina de hoy, próximas clases y los
 * días entrenados del mes. Solo reúne datos que ya expone la API; si una consulta falla, esa tarjeta se omite.
 */
@Component({
  selector: 'app-inicio',
  imports: [DecimalPipe, RouterLink],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <div>
          <p class="nf-fecha">{{ fechaLarga() }}</p>
          <h1>Hola, {{ nombre() }}</h1>
        </div>
        <a class="nf-boton" routerLink="/clases">Reservar clase</a>
      </div>

      @if (progreso(); as p) {
        <div class="nf-indicadores">
          <div class="nf-indicador">
            <span class="nf-indicador-nombre">Racha</span>
            <span class="nf-indicador-valor">{{ p.streak | number }} {{ p.streak === 1 ? 'día' : 'días' }}</span>
            <span class="nf-indicador-nombre">{{ p.totalWorkouts | number }} entrenamientos en total</span>
          </div>
          <div class="nf-indicador">
            <span class="nf-indicador-nombre">Meta semanal</span>
            @if (p.weeklyGoal; as meta) {
              <span class="nf-indicador-valor">{{ p.weekCount }} <small>/ {{ meta }} sesiones</small></span>
              <progress [value]="p.weekCount" [max]="meta" [attr.aria-label]="'Avance de la meta semanal'"></progress>
            } @else {
              <span class="nf-indicador-valor">{{ p.weekCount }} <small>esta semana</small></span>
              <a routerLink="/progreso">Definir una meta</a>
            }
          </div>
          <div class="nf-indicador">
            <span class="nf-indicador-nombre">Peso</span>
            @if (pesoActual(); as kg) {
              <span class="nf-indicador-valor">{{ kg | number: '1.1-1' }} <small>kg</small></span>
              @if (cambioPeso(); as c) {
                <span class="nf-indicador-nombre">{{ c > 0 ? '+' : '' }}{{ c | number: '1.1-1' }} kg desde el primer registro</span>
              }
            } @else {
              <span class="nf-indicador-valor">—</span>
              <a routerLink="/peso">Registrar mi peso</a>
            }
          </div>
        </div>
      }

      <div class="nf-dos-columnas">
        <section class="nf-tarjeta" aria-labelledby="t-rutina">
          <p class="nf-eyebrow">Rutina de hoy</p>
          @if (rutina(); as r) {
            <div class="nf-cabecera">
              <h2 id="t-rutina">{{ r.name }}</h2>
              <a class="nf-boton" routerLink="/progreso">Empezar</a>
            </div>
            <p class="nf-chips">
              @if (r.category) {
                <span class="nf-chip">{{ etiquetaCategoria(r.category) }}</span>
              }
              @if (r.estimatedMinutes) {
                <span class="nf-chip">{{ r.estimatedMinutes }} min</span>
              }
              <span class="nf-chip">Asignada por {{ r.trainerName }}</span>
            </p>
            <ol class="nf-lista-numerada">
              @for (e of r.exercises; track e.id) {
                <li>
                  <span class="nf-numero">{{ e.order }}</span>
                  <span>{{ e.exerciseName }}</span>
                  <span class="nf-ayuda">{{ e.series }} × {{ e.repetitions }}</span>
                </li>
              }
            </ol>
          } @else {
            <h2 id="t-rutina">Aún no tienes una rutina activa</h2>
            <p class="nf-ayuda">Cuando tu entrenador te asigne una, aparecerá aquí.</p>
          }
        </section>

        <section class="nf-tarjeta" aria-labelledby="t-clases">
          <div class="nf-cabecera">
            <h2 id="t-clases">Próximas clases</h2>
            <a routerLink="/reservas">Ver todas</a>
          </div>
          @if (proximas().length === 0) {
            <p class="nf-ayuda">No tienes clases reservadas. Reserva un cupo desde Clases.</p>
          } @else {
            <ul class="nf-proximas">
              @for (c of proximas(); track c.id) {
                <li>
                  <span class="nf-hora">
                    <small>{{ diaCorto(c.classStartsAt) }}</small>
                    {{ hora(c.classStartsAt) }}
                  </span>
                  <span>
                    <strong>{{ c.className }}</strong>
                    <span class="nf-ayuda">{{ c.branchName }}</span>
                  </span>
                </li>
              }
            </ul>
          }
        </section>
      </div>

      @if (calendario().length > 0) {
        <section class="nf-tarjeta" aria-labelledby="t-mes">
          <div class="nf-cabecera">
            <h2 id="t-mes">Entrenamientos de {{ mes() }}</h2>
            <span class="nf-ayuda">{{ diasEntrenados() }} {{ diasEntrenados() === 1 ? 'día' : 'días' }} entrenados</span>
          </div>
          <ol class="nf-calendario">
            @for (d of calendario(); track d.numero) {
              <li [class.hecho]="d.entrenado" [class.futuro]="d.futuro" [attr.aria-label]="d.etiqueta">
                <span class="nf-dia" aria-hidden="true"></span>
                <small aria-hidden="true">{{ d.numero }}</small>
              </li>
            }
          </ol>
        </section>
      }
    </section>
  `
})
export class InicioComponent implements OnInit {
  private readonly entrenamientos = inject(EntrenamientosService);
  private readonly rutinas = inject(RutinasService);
  private readonly reservas = inject(ReservasService);
  private readonly sesion = inject(SessionService);

  protected readonly progreso = signal<Progreso | null>(null);
  protected readonly pesos = signal<RegistroPeso[]>([]);
  protected readonly historial = signal<ResumenEntrenamiento[]>([]);
  protected readonly rutina = signal<Rutina | null>(null);
  protected readonly reservasConfirmadas = signal<Reserva[]>([]);

  protected readonly nombre = computed(() => this.sesion.usuario()?.firstName ?? '');
  /** «Hoy» según el gimnasio (lo informa la API); si aún no llegó, la fecha del navegador. */
  private readonly hoy = computed(() => this.progreso()?.today ?? new Date().toISOString().slice(0, 10));

  protected readonly fechaLarga = computed(() => {
    const [a, m, d] = this.hoy().split('-').map(Number);
    const dia = new Date(a, m - 1, d).toLocaleDateString('es-CO', { weekday: 'long' });
    return `${dia.charAt(0).toUpperCase()}${dia.slice(1)} ${d} de ${MESES[m - 1]}`;
  });
  protected readonly mes = computed(() => MESES[Number(this.hoy().split('-')[1]) - 1]);

  protected readonly pesoActual = computed(() => {
    const lista = this.pesos();
    return lista.length > 0 ? lista[lista.length - 1].weightKg : null;
  });
  protected readonly cambioPeso = computed(() => {
    const lista = this.pesos();
    if (lista.length < 2) return null;
    return Math.round((lista[lista.length - 1].weightKg - lista[0].weightKg) * 10) / 10;
  });

  protected readonly proximas = computed(() => {
    const ahora = Date.now();
    return this.reservasConfirmadas()
      .filter((r) => new Date(r.classStartsAt).getTime() >= ahora)
      .sort((a, b) => a.classStartsAt.localeCompare(b.classStartsAt))
      .slice(0, 3);
  });

  protected readonly calendario = computed<DiaCalendario[]>(() => {
    const [anio, mes, hoyDia] = this.hoy().split('-').map(Number);
    const prefijo = `${anio}-${String(mes).padStart(2, '0')}-`;
    const entrenados = new Set(this.historial().map((h) => h.performedOn).filter((f) => f.startsWith(prefijo)));
    const total = new Date(anio, mes, 0).getDate();
    return Array.from({ length: total }, (_, i) => {
      const numero = i + 1;
      const entrenado = entrenados.has(prefijo + String(numero).padStart(2, '0'));
      return {
        numero,
        entrenado,
        futuro: numero > hoyDia,
        etiqueta: `${numero} de ${MESES[mes - 1]}: ${entrenado ? 'entrenaste' : 'sin entrenamiento'}`
      };
    });
  });
  protected readonly diasEntrenados = computed(() => this.calendario().filter((d) => d.entrenado).length);

  ngOnInit() {
    this.entrenamientos.progreso().subscribe({ next: (p) => this.progreso.set(p), error: () => undefined });
    this.entrenamientos.pesos().subscribe({ next: (p) => this.pesos.set(p), error: () => undefined });
    this.entrenamientos.historial().subscribe({ next: (h) => this.historial.set(h), error: () => undefined });
    this.reservas.listar({ status: 'confirmed' }).subscribe({
      next: (r) => this.reservasConfirmadas.set(r),
      error: () => undefined
    });
    this.rutinas.listar({ active: true }).subscribe({
      next: (lista) => {
        if (lista.length === 0) return;
        const ultima = [...lista].sort((a, b) => b.createdAt.localeCompare(a.createdAt))[0];
        this.rutinas.detalle(ultima.id).subscribe({ next: (r) => this.rutina.set(r), error: () => undefined });
      },
      error: () => undefined
    });
  }

  protected etiquetaCategoria(c: string): string {
    return c === 'strength' ? 'Fuerza' : c === 'cardio' ? 'Cardio' : 'Movilidad';
  }

  protected diaCorto(iso: string): string {
    return new Date(iso).toLocaleDateString('es-CO', { weekday: 'short', timeZone: 'America/Bogota' }).toUpperCase();
  }

  protected hora(iso: string): string {
    return new Date(iso).toLocaleTimeString('es-CO', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'America/Bogota' });
  }
}
