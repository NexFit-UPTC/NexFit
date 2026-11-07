import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { Alumno, AsignacionesService } from '../core/asignaciones.service';
import { errorDeApi } from '../core/establecimientos.service';

/** «Mis alumnos» (US-40): el Entrenador ve a quienes tiene asignados y su contacto de emergencia. */
@Component({
  selector: 'app-mis-alumnos',
  imports: [DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Mis alumnos</h1>
      </div>
      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (alumnos().length === 0) {
        <p class="nf-vacio">Aún no tienes alumnos asignados. Pídele al Administrador o al Staff que te asigne.</p>
      } @else {
        <ul class="nf-galeria">
          @for (a of alumnos(); track a.memberId) {
            <li class="nf-tarjeta">
              <h2>{{ a.name }}</h2>
              <p class="nf-ayuda">{{ a.age !== null ? a.age + ' años' : 'Edad no registrada' }}</p>
              <p>
                <span class="nf-insignia" [class.ok]="a.activeRoutines > 0">
                  {{ a.activeRoutines }} {{ a.activeRoutines === 1 ? 'rutina activa' : 'rutinas activas' }}
                </span>
              </p>
              <p class="nf-ayuda">
                Última asistencia:
                {{ a.lastCheckIn ? (a.lastCheckIn | date: 'dd/MM/yyyy') : 'sin asistencias' }}
              </p>
              @if (a.emergencyContact; as c) {
                <p>
                  <strong>Emergencia:</strong> {{ c.fullName }} ({{ c.relationship }})
                  <a [href]="'tel:' + c.phone">{{ c.phone }}</a>
                </p>
              } @else {
                <p class="nf-ayuda">Sin contacto de emergencia.</p>
              }
            </li>
          }
        </ul>
      }
    </section>
  `
})
export class MisAlumnosComponent implements OnInit {
  private readonly api = inject(AsignacionesService);

  protected readonly alumnos = signal<Alumno[]>([]);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  ngOnInit() {
    this.api.misAlumnos().subscribe({
      next: (lista) => {
        this.alumnos.set(lista);
        this.cargando.set(false);
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(errorDeApi(e).mensaje);
        this.cargando.set(false);
      }
    });
  }
}
