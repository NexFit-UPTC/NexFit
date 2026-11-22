import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EntrenamientosService, Progreso, ResumenEntrenamiento } from '../core/entrenamientos.service';
import { errorDeApi } from '../core/establecimientos.service';
import { imagenEjercicio } from '../core/imagenes';
import { ResumenRutina, Rutina, RutinasService } from '../core/rutinas.service';

const METAS = [1, 2, 3, 4, 5, 6, 7];

/** «Mi progreso» (US-53, US-54): racha, meta semanal, registro de entrenamientos e historial del Cliente. */
@Component({
  selector: 'app-progreso',
  imports: [FormsModule, DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Mi progreso</h1>
      </div>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }

      @if (progreso(); as p) {
        <div class="nf-indicadores">
          <div class="nf-indicador">
            <span class="nf-indicador-valor" data-testid="racha">{{ p.streak }}</span>
            <span class="nf-indicador-nombre">{{ p.streak === 1 ? 'día de racha' : 'días de racha' }}</span>
            <span class="nf-ayuda">
              {{ p.streak === 0 ? 'Entrena hoy para empezar una racha.' : 'Sigue así: entrena hoy o mañana para mantenerla.' }}
            </span>
          </div>
          <div class="nf-indicador">
            <span class="nf-indicador-valor" data-testid="semana">
              {{ p.weekCount }}{{ p.weeklyGoal ? ' / ' + p.weeklyGoal : '' }}
            </span>
            <span class="nf-indicador-nombre">entrenamientos esta semana</span>
            <span class="nf-ayuda">
              Del {{ p.weekStart | date: 'dd/MM' }} al {{ p.weekEnd | date: 'dd/MM' }}
            </span>
            @if (p.weeklyGoal) {
              <progress [value]="avance()" max="100" [attr.aria-label]="'Avance de la meta semanal'"></progress>
            }
          </div>
          <div class="nf-indicador">
            <span class="nf-indicador-valor">{{ p.totalWorkouts }}</span>
            <span class="nf-indicador-nombre">días entrenados en total</span>
          </div>
        </div>

        <div class="nf-tarjeta">
          <h2>Mi meta semanal</h2>
          <p class="nf-ayuda">¿Cuántos días a la semana quieres entrenar?</p>
          <div class="nf-acciones" role="group" aria-label="Meta semanal">
            @for (n of metas; track n) {
              <button type="button" [class]="p.weeklyGoal === n ? 'nf-boton' : 'nf-boton-sec'"
                [attr.aria-pressed]="p.weeklyGoal === n" (click)="fijarMeta(n)">{{ n }}</button>
            }
            @if (p.weeklyGoal) {
              <button type="button" class="nf-boton-sec" (click)="fijarMeta(null)">Quitar meta</button>
            }
          </div>
        </div>
      }

      <form class="nf-tarjeta" (ngSubmit)="registrar()" novalidate>
        <h2>Registrar un entrenamiento</h2>
        @if (rutinas().length === 0) {
          <p class="nf-ayuda">Cuando tu entrenador te asigne una rutina podrás registrar tus entrenamientos aquí.</p>
        } @else {
          <div class="nf-rejilla">
            <label>
              Rutina
              <select name="rutina" [ngModel]="rutinaElegida()" (ngModelChange)="elegirRutina($event)">
                <option [ngValue]="null">Selecciona…</option>
                @for (r of rutinas(); track r.id) {
                  <option [ngValue]="r.id">{{ r.name }}</option>
                }
              </select>
            </label>
            <label>
              Fecha
              <input name="fecha" type="date" [max]="progreso()?.today ?? ''" [(ngModel)]="fecha" />
            </label>
          </div>
          @if (detalle(); as d) {
            <ul class="nf-checklist">
              @for (e of d.exercises; track e.id) {
                <li>
                  <label class="nf-casilla">
                    <input type="checkbox" [name]="'ej' + e.id" [checked]="marcados().has(e.id)"
                      (change)="marcar(e.id, $any($event.target).checked)" />
                    <img [src]="imagen(e)" [alt]="''" loading="lazy" />
                    <span>
                      <strong>{{ e.exerciseName }}</strong>
                      <span class="nf-ayuda">{{ e.series }} × {{ e.repetitions }} · descanso {{ e.restSeconds }} s</span>
                    </span>
                  </label>
                </li>
              }
            </ul>
            <div class="nf-acciones">
              <button type="button" class="nf-boton-sec" (click)="marcarTodos()">Marcar todos</button>
            </div>
          }
          @if (errorFormulario()) {
            <p class="nf-error-campo" role="alert">{{ errorFormulario() }}</p>
          }
          @if (exito()) {
            <p class="nf-ayuda" role="status">{{ exito() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Guardar entrenamiento</button>
          </div>
        }
      </form>

      <div class="nf-tarjeta">
        <h2>Historial</h2>
        @if (historial().length === 0) {
          <p class="nf-vacio">Todavía no has registrado entrenamientos.</p>
        } @else {
          <table class="nf-tabla">
            <thead>
              <tr><th>Fecha</th><th>Rutina</th><th>Ejercicios</th></tr>
            </thead>
            <tbody>
              @for (h of historial(); track h.id) {
                <tr>
                  <td>{{ h.performedOn | date: 'dd/MM/yyyy' }}</td>
                  <td>{{ h.routineName }}</td>
                  <td>{{ h.completedCount }} de {{ h.totalCount }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>
    </section>
  `
})
export class ProgresoComponent implements OnInit {
  private readonly api = inject(EntrenamientosService);
  private readonly rutinasApi = inject(RutinasService);

  protected readonly metas = METAS;
  protected readonly progreso = signal<Progreso | null>(null);
  protected readonly rutinas = signal<ResumenRutina[]>([]);
  protected readonly detalle = signal<Rutina | null>(null);
  protected readonly historial = signal<ResumenEntrenamiento[]>([]);
  protected readonly rutinaElegida = signal<number | null>(null);
  protected readonly marcados = signal<Set<number>>(new Set());
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errorFormulario = signal<string | null>(null);
  protected readonly exito = signal<string | null>(null);
  protected readonly avance = computed(() => {
    const p = this.progreso();
    return p?.weeklyGoal ? Math.min(100, Math.round((p.weekCount / p.weeklyGoal) * 100)) : 0;
  });

  protected fecha = '';
  protected imagen = imagenEjercicio;

  ngOnInit() {
    this.cargarProgreso();
    this.cargarHistorial();
    this.rutinasApi.listar({ active: true }).subscribe({
      next: (r) => this.rutinas.set(r),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  protected fijarMeta(meta: number | null) {
    this.error.set(null);
    this.api.fijarMeta(meta).subscribe({
      next: (p) => this.progreso.set(p),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  protected elegirRutina(id: number | null) {
    this.rutinaElegida.set(id);
    this.marcados.set(new Set());
    this.detalle.set(null);
    this.exito.set(null);
    if (id === null) return;
    this.rutinasApi.detalle(id).subscribe({
      next: (d) => this.detalle.set(d),
      error: (e: HttpErrorResponse) => this.errorFormulario.set(errorDeApi(e).mensaje)
    });
  }

  protected marcar(id: number, marcado: boolean) {
    const nuevo = new Set(this.marcados());
    if (marcado) nuevo.add(id);
    else nuevo.delete(id);
    this.marcados.set(nuevo);
  }

  protected marcarTodos() {
    this.marcados.set(new Set((this.detalle()?.exercises ?? []).map((e) => e.id)));
  }

  protected registrar() {
    this.errorFormulario.set(null);
    this.exito.set(null);
    const rutina = this.rutinaElegida();
    if (rutina === null) {
      this.errorFormulario.set('Selecciona la rutina.');
      return;
    }
    if (this.marcados().size === 0) {
      this.errorFormulario.set('Marca al menos un ejercicio completado.');
      return;
    }
    const hoy = this.progreso()?.today;
    if (this.fecha && hoy && this.fecha > hoy) {
      this.errorFormulario.set('No puedes registrar un entrenamiento en una fecha futura.');
      return;
    }
    this.guardando.set(true);
    this.api.registrar(rutina, [...this.marcados()], this.fecha || undefined).subscribe({
      next: () => {
        this.guardando.set(false);
        this.exito.set('¡Entrenamiento registrado!');
        this.marcados.set(new Set());
        this.fecha = '';
        this.cargarProgreso();
        this.cargarHistorial();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorFormulario.set(errorDeApi(e).mensaje);
      }
    });
  }

  private cargarProgreso() {
    this.api.progreso().subscribe({
      next: (p) => this.progreso.set(p),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  private cargarHistorial() {
    this.api.historial().subscribe({
      next: (h) => this.historial.set(h),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }
}
