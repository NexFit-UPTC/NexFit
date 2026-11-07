import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EntrenamientosService, RegistroPeso } from '../core/entrenamientos.service';
import { errorDeApi } from '../core/establecimientos.service';

const ANCHO = 320;
const ALTO = 140;
const MARGEN = 28;

/** «Mi peso» (US-55): el Cliente registra su peso corporal y ve su evolución. Es un dato de salud: solo suyo. */
@Component({
  selector: 'app-peso',
  imports: [FormsModule, DatePipe, DecimalPipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Mi peso</h1>
      </div>

      <form class="nf-tarjeta" (ngSubmit)="guardar()" novalidate>
        <h2>Registrar mi peso</h2>
        <div class="nf-rejilla">
          <label>
            Peso (kg)
            <input name="peso" type="number" step="0.1" min="20" max="500" [(ngModel)]="peso" />
          </label>
          <label>
            Fecha (hoy por defecto)
            <input name="fecha" type="date" [(ngModel)]="fecha" />
          </label>
        </div>
        @if (errorFormulario()) {
          <p class="nf-error-campo" role="alert">{{ errorFormulario() }}</p>
        }
        @if (exito()) {
          <p class="nf-ayuda" role="status">{{ exito() }}</p>
        }
        <p class="nf-ayuda">Si ya registraste un peso ese día, el nuevo lo reemplaza.</p>
        <div class="nf-acciones">
          <button type="submit" class="nf-boton" [disabled]="guardando()">Guardar</button>
        </div>
      </form>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (registros().length === 0) {
        <p class="nf-vacio">Aún no has registrado tu peso.</p>
      } @else {
        <div class="nf-indicadores">
          <div class="nf-indicador">
            <span class="nf-indicador-valor" data-testid="ultimo">{{ ultimo()!.weightKg | number: '1.0-2' }} kg</span>
            <span class="nf-indicador-nombre">peso más reciente ({{ ultimo()!.measuredOn | date: 'dd/MM/yyyy' }})</span>
          </div>
          @if (cambio() !== null) {
            <div class="nf-indicador">
              <span class="nf-indicador-valor" data-testid="cambio">
                {{ cambio()! > 0 ? '+' : '' }}{{ cambio()! | number: '1.0-2' }} kg
              </span>
              <span class="nf-indicador-nombre">desde tu primer registro</span>
            </div>
          }
        </div>

        @if (grafica(); as g) {
          <svg class="nf-grafica" [attr.viewBox]="'0 0 ' + ancho + ' ' + alto" role="img"
            [attr.aria-label]="'Evolución del peso, de ' + g.min + ' a ' + g.max + ' kg'">
            <polyline [attr.points]="g.linea" />
            @for (p of g.puntos; track p.x) {
              <circle [attr.cx]="p.x" [attr.cy]="p.y" r="3.5"><title>{{ p.etiqueta }}</title></circle>
            }
            <text x="4" [attr.y]="margen">{{ g.max }}</text>
            <text x="4" [attr.y]="alto - margen + 10">{{ g.min }}</text>
          </svg>
        }

        <table class="nf-tabla">
          <thead>
            <tr><th>Fecha</th><th>Peso</th></tr>
          </thead>
          <tbody>
            @for (r of recientes(); track r.id) {
              <tr>
                <td>{{ r.measuredOn | date: 'dd/MM/yyyy' }}</td>
                <td>{{ r.weightKg | number: '1.0-2' }} kg</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `
})
export class PesoComponent implements OnInit {
  private readonly api = inject(EntrenamientosService);

  protected readonly ancho = ANCHO;
  protected readonly alto = ALTO;
  protected readonly margen = MARGEN;
  protected readonly registros = signal<RegistroPeso[]>([]);
  protected readonly cargando = signal(true);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errorFormulario = signal<string | null>(null);
  protected readonly exito = signal<string | null>(null);

  protected readonly ultimo = computed(() => this.registros().at(-1) ?? null);
  protected readonly recientes = computed(() => [...this.registros()].reverse());
  protected readonly cambio = computed(() => {
    const r = this.registros();
    return r.length < 2 ? null : Math.round((r[r.length - 1].weightKg - r[0].weightKg) * 100) / 100;
  });
  protected readonly grafica = computed(() => {
    const r = this.registros();
    if (r.length < 2) return null;
    const pesos = r.map((x) => x.weightKg);
    const min = Math.min(...pesos);
    const max = Math.max(...pesos);
    const rango = max - min || 1;
    const dia = (f: string) => new Date(f + 'T00:00:00Z').getTime();
    const t0 = dia(r[0].measuredOn);
    const span = dia(r[r.length - 1].measuredOn) - t0 || 1;
    const puntos = r.map((x) => ({
      x: Math.round(MARGEN + ((dia(x.measuredOn) - t0) / span) * (ANCHO - 2 * MARGEN)),
      y: Math.round(ALTO - MARGEN - ((x.weightKg - min) / rango) * (ALTO - 2 * MARGEN)),
      etiqueta: `${x.measuredOn}: ${x.weightKg} kg`
    }));
    return { min, max, puntos, linea: puntos.map((p) => `${p.x},${p.y}`).join(' ') };
  });

  protected peso: number | null = null;
  protected fecha = '';

  ngOnInit() {
    this.recargar();
  }

  protected guardar() {
    this.errorFormulario.set(null);
    this.exito.set(null);
    if (this.peso === null || this.peso < 20 || this.peso > 500) {
      this.errorFormulario.set('El peso va de 20 a 500 kg.');
      return;
    }
    this.guardando.set(true);
    this.api.guardarPeso(this.peso, this.fecha || undefined).subscribe({
      next: () => {
        this.guardando.set(false);
        this.exito.set('Peso guardado.');
        this.peso = null;
        this.fecha = '';
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorFormulario.set(errorDeApi(e).mensaje);
      }
    });
  }

  private recargar() {
    this.api.pesos().subscribe({
      next: (r) => {
        this.registros.set(r);
        this.cargando.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(errorDeApi(e).mensaje);
        this.cargando.set(false);
      }
    });
  }
}
