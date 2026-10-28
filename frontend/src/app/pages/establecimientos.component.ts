import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  DatosEstablecimiento,
  errorDeApi,
  EstablecimientosService,
  ResumenEstablecimiento
} from '../core/establecimientos.service';

const ZONA_POR_DEFECTO = 'America/Bogota';

@Component({
  selector: 'app-establecimientos',
  imports: [FormsModule],
  template: `
    <h1>Establecimientos</h1>
    <button type="button" (click)="nuevo()">Crear establecimiento</button>

    @if (formulario(); as f) {
      <form (ngSubmit)="guardar()" novalidate>
        <h2>{{ editandoId() === null ? 'Nuevo establecimiento' : 'Editar establecimiento' }}</h2>
        <label>
          Nombre
          <input name="name" [(ngModel)]="f.name" />
        </label>
        @if (errores()['name']) {
          <p role="alert">{{ errores()['name'] }}</p>
        }
        <label>
          Correo de contacto
          <input name="contactEmail" type="email" [(ngModel)]="f.contactEmail" />
        </label>
        @if (errores()['contactEmail']) {
          <p role="alert">{{ errores()['contactEmail'] }}</p>
        }
        <label>
          Zona horaria
          <input name="timezone" [(ngModel)]="f.timezone" />
        </label>
        @if (errores()['timezone']) {
          <p role="alert">{{ errores()['timezone'] }}</p>
        }
        @if (errorGeneral()) {
          <p role="alert">{{ errorGeneral() }}</p>
        }
        <button type="submit" [disabled]="guardando()">Guardar</button>
        <button type="button" (click)="cancelar()">Cancelar</button>
      </form>
    }

    @if (error()) {
      <p role="alert">{{ error() }}</p>
    }
    @if (cargando()) {
      <p>Cargando…</p>
    } @else if (lista().length === 0) {
      <p>No hay establecimientos.</p>
    } @else {
      <table>
        <thead>
          <tr>
            <th>Nombre</th>
            <th>Correo de contacto</th>
            <th>Zona horaria</th>
            <th>Estado</th>
            <th>Sedes</th>
            <th>Clientes</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (e of lista(); track e.id) {
            <tr>
              <td>{{ e.name }}</td>
              <td>{{ e.contactEmail }}</td>
              <td>{{ e.timezone }}</td>
              <td>{{ e.active ? 'Activo' : 'Inactivo' }}</td>
              <td>{{ e.branchCount }}</td>
              <td>{{ e.clientCount }}</td>
              <td><button type="button" (click)="editar(e)">Editar</button></td>
            </tr>
          }
        </tbody>
      </table>
    }
  `
})
export class EstablecimientosComponent implements OnInit {
  private readonly api = inject(EstablecimientosService);

  protected readonly lista = signal<ResumenEstablecimiento[]>([]);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly formulario = signal<DatosEstablecimiento | null>(null);
  protected readonly editandoId = signal<number | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);

  ngOnInit() {
    this.recargar();
  }

  protected nuevo() {
    this.abrir(null, { name: '', contactEmail: '', timezone: ZONA_POR_DEFECTO });
  }

  protected editar(e: ResumenEstablecimiento) {
    this.abrir(e.id, { name: e.name, contactEmail: e.contactEmail, timezone: e.timezone });
  }

  protected cancelar() {
    this.formulario.set(null);
  }

  protected guardar() {
    const datos = this.formulario();
    if (!datos) return;
    const id = this.editandoId();
    this.guardando.set(true);
    this.errores.set({});
    this.errorGeneral.set(null);
    const peticion = id === null ? this.api.crear(datos) : this.api.editar(id, datos);
    peticion.subscribe({
      next: () => {
        this.guardando.set(false);
        this.formulario.set(null);
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        const { campo, mensaje } = errorDeApi(e);
        if (campo) this.errores.set({ [campo]: mensaje });
        else this.errorGeneral.set(mensaje);
      }
    });
  }

  private abrir(id: number | null, datos: DatosEstablecimiento) {
    this.editandoId.set(id);
    this.errores.set({});
    this.errorGeneral.set(null);
    this.formulario.set(datos);
  }

  private recargar() {
    this.cargando.set(true);
    this.api.listar().subscribe({
      next: (datos) => {
        this.lista.set(datos);
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
