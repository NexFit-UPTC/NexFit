import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Asignacion, AsignacionesService, Entrenador } from '../core/asignaciones.service';
import { ClientesService, ResumenCliente } from '../core/clientes.service';
import { errorDeApi } from '../core/establecimientos.service';

/** Asignación de alumnos a entrenadores (US-39): el Administrador y el Staff asignan y retiran. */
@Component({
  selector: 'app-asignaciones',
  imports: [FormsModule, DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Alumnos de los entrenadores</h1>
      </div>

      <form class="nf-tarjeta" (ngSubmit)="asignar()" novalidate>
        <h2>Asignar un alumno</h2>
        <div class="nf-rejilla">
          <label>
            Entrenador
            <select name="entrenador" [(ngModel)]="entrenadorElegido">
              <option [ngValue]="null">Selecciona…</option>
              @for (t of entrenadores(); track t.memberId) {
                <option [ngValue]="t.memberId">
                  {{ t.name }}{{ t.specialty ? ' · ' + t.specialty : '' }} ({{ t.activeStudents }} alumnos)
                </option>
              }
            </select>
          </label>
          <label>
            Buscar cliente (nombre o documento)
            <input name="busqueda" [(ngModel)]="busqueda" (ngModelChange)="buscar()" autocomplete="off" />
          </label>
          <label>
            Cliente
            <select name="cliente" [(ngModel)]="clienteElegido">
              <option [ngValue]="null">Selecciona…</option>
              @for (c of candidatos(); track c.memberId) {
                <option [ngValue]="c.memberId">{{ c.firstName }} {{ c.lastName }} · {{ c.documentNumber }}</option>
              }
            </select>
          </label>
        </div>
        @if (errorFormulario()) {
          <p class="nf-error-campo" role="alert">{{ errorFormulario() }}</p>
        }
        @if (exito()) {
          <p class="nf-ayuda" role="status">{{ exito() }}</p>
        }
        <div class="nf-acciones">
          <button type="submit" class="nf-boton" [disabled]="guardando()">Asignar</button>
        </div>
      </form>

      <div class="nf-filtros">
        <label>
          Entrenador
          <select name="filtroEntrenador" [ngModel]="filtroEntrenador()" (ngModelChange)="filtrar('entrenador', $event)">
            <option [ngValue]="null">Todos</option>
            @for (t of entrenadores(); track t.memberId) {
              <option [ngValue]="t.memberId">{{ t.name }}</option>
            }
          </select>
        </label>
        <label>
          Estado
          <select name="filtroEstado" [ngModel]="filtroEstado()" (ngModelChange)="filtrar('estado', $event)">
            <option value="activos">Activos</option>
            <option value="retirados">Retirados</option>
            <option value="todos">Todos</option>
          </select>
        </label>
      </div>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (asignaciones().length === 0) {
        <p class="nf-vacio">No hay alumnos asignados con esos filtros.</p>
      } @else {
        <table class="nf-tabla">
          <thead>
            <tr>
              <th>Entrenador</th>
              <th>Alumno</th>
              <th>Desde</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (a of asignaciones(); track a.trainerMemberId + '-' + a.clientMemberId) {
              <tr>
                <td>{{ a.trainerName }}</td>
                <td>{{ a.clientName }}</td>
                <td>{{ a.assignedAt | date: 'dd/MM/yyyy' }}</td>
                <td>
                  <span class="nf-insignia" [class.ok]="a.active">{{ a.active ? 'Activo' : 'Retirado' }}</span>
                </td>
                <td>
                  <button type="button" class="nf-boton-sec" (click)="cambiar(a)">
                    {{ a.active ? 'Retirar' : 'Asignar de nuevo' }}
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `
})
export class AsignacionesComponent implements OnInit {
  private readonly api = inject(AsignacionesService);
  private readonly clientesApi = inject(ClientesService);

  protected readonly entrenadores = signal<Entrenador[]>([]);
  protected readonly asignaciones = signal<Asignacion[]>([]);
  protected readonly candidatos = signal<ResumenCliente[]>([]);
  protected readonly filtroEntrenador = signal<number | null>(null);
  protected readonly filtroEstado = signal<'activos' | 'retirados' | 'todos'>('activos');
  protected readonly cargando = signal(true);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errorFormulario = signal<string | null>(null);
  protected readonly exito = signal<string | null>(null);

  protected entrenadorElegido: number | null = null;
  protected clienteElegido: number | null = null;
  protected busqueda = '';

  ngOnInit() {
    this.api.entrenadores().subscribe((t) => this.entrenadores.set(t));
    this.buscar();
    this.recargar();
  }

  protected buscar() {
    this.clientesApi.listar({ q: this.busqueda, active: true, size: 20 }).subscribe({
      next: (p) => this.candidatos.set(p.content),
      error: () => this.candidatos.set([])
    });
  }

  protected filtrar(cual: 'entrenador' | 'estado', valor: unknown) {
    if (cual === 'entrenador') this.filtroEntrenador.set(valor as number | null);
    else this.filtroEstado.set(valor as 'activos' | 'retirados' | 'todos');
    this.recargar();
  }

  protected asignar() {
    this.errorFormulario.set(null);
    this.exito.set(null);
    if (this.entrenadorElegido === null || this.clienteElegido === null) {
      this.errorFormulario.set('Selecciona el entrenador y el cliente.');
      return;
    }
    this.guardando.set(true);
    this.api.asignar(this.entrenadorElegido, this.clienteElegido).subscribe({
      next: (a) => {
        this.guardando.set(false);
        this.exito.set(`${a.clientName} ahora es alumno de ${a.trainerName}.`);
        this.clienteElegido = null;
        this.refrescarEntrenadores();
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorFormulario.set(errorDeApi(e).mensaje);
      }
    });
  }

  protected cambiar(a: Asignacion) {
    this.error.set(null);
    this.api.cambiarEstado(a.trainerMemberId, a.clientMemberId, !a.active).subscribe({
      next: () => {
        this.refrescarEntrenadores();
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(errorDeApi(e).mensaje);
        this.recargar();
      }
    });
  }

  private refrescarEntrenadores() {
    this.api.entrenadores().subscribe((t) => this.entrenadores.set(t));
  }

  private recargar() {
    this.cargando.set(true);
    const estado = this.filtroEstado();
    this.api
      .listar({
        trainerMemberId: this.filtroEntrenador() ?? undefined,
        active: estado === 'todos' ? undefined : estado === 'activos'
      })
      .subscribe({
        next: (lista) => {
          this.asignaciones.set(lista);
          this.cargando.set(false);
        },
        error: (e: HttpErrorResponse) => {
          this.error.set(errorDeApi(e).mensaje);
          this.cargando.set(false);
        }
      });
  }
}
