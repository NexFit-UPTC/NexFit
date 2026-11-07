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
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Establecimientos</h1>
        <button type="button" class="nf-boton" (click)="nuevo()">Crear establecimiento</button>
      </div>

    @if (formulario(); as f) {
      <form class="nf-tarjeta nf-formulario" (ngSubmit)="guardar()" novalidate>
        <h2>{{ editandoId() === null ? 'Nuevo establecimiento' : 'Editar establecimiento' }}</h2>
        <label class="nf-campo">
          Nombre
          <input class="nf-entrada" name="name" [(ngModel)]="f.name" />
        </label>
        @if (errores()['name']) {
          <p class="nf-error-campo" role="alert">{{ errores()['name'] }}</p>
        }
        <label class="nf-campo">
          Correo de contacto
          <input class="nf-entrada" name="contactEmail" type="email" [(ngModel)]="f.contactEmail" />
        </label>
        @if (errores()['contactEmail']) {
          <p class="nf-error-campo" role="alert">{{ errores()['contactEmail'] }}</p>
        }
        <label class="nf-campo">
          Zona horaria
          <input class="nf-entrada" name="timezone" [(ngModel)]="f.timezone" />
        </label>
        @if (errores()['timezone']) {
          <p class="nf-error-campo" role="alert">{{ errores()['timezone'] }}</p>
        }
        @if (errorGeneral()) {
          <p class="nf-alerta" role="alert">{{ errorGeneral() }}</p>
        }
        <div class="nf-acciones">
          <button type="submit" class="nf-boton" [disabled]="guardando()">Guardar</button>
          <button type="button" class="nf-boton-sec" (click)="cancelar()">Cancelar</button>
        </div>
      </form>
    }

    @if (error()) {
      <p class="nf-alerta" role="alert">{{ error() }}</p>
    }
    @if (cargando()) {
      <p class="nf-ayuda">Cargando…</p>
    } @else if (lista().length === 0) {
      <p class="nf-vacio">No hay establecimientos.</p>
    } @else {
      <div class="nf-tabla-envoltorio">
      <table class="nf-tabla">
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
              <td>
                <span class="nf-insignia" [class.ok]="e.active">{{ e.active ? 'Activo' : 'Inactivo' }}</span>
              </td>
              <td>{{ e.branchCount }}</td>
              <td>{{ e.clientCount }}</td>
              <td class="nf-acciones-celda"><button type="button" class="nf-boton-sec" (click)="editar(e)">Editar</button></td>
            </tr>
          }
        </tbody>
      </table>
      </div>
    }
    </section>
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
