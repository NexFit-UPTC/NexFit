import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  DatosAdministrador,
  errorDeApi,
  EstablecimientosService,
  ResultadoAsignacion,
  ResumenEstablecimiento
} from '../core/establecimientos.service';

function vacio(): DatosAdministrador {
  return { documentType: 'CC', documentNumber: '', firstName: '', lastName: '', birthDate: '', email: '' };
}

@Component({
  selector: 'app-administrador',
  imports: [FormsModule],
  template: `
    <h1>Administrador</h1>
    <label>
      Establecimiento
      <select name="org" [ngModel]="orgId()" (ngModelChange)="elegir($event)">
        <option [ngValue]="null">Selecciona…</option>
        @for (e of establecimientos(); track e.id) {
          <option [ngValue]="e.id">{{ e.name }}</option>
        }
      </select>
    </label>

    @if (orgId() !== null) {
      <form (ngSubmit)="asignar()" novalidate>
        <label>
          Tipo de documento
          <select name="documentType" [(ngModel)]="datos.documentType">
            <option value="CC">CC</option>
            <option value="CE">CE</option>
            <option value="PPT">PPT</option>
            <option value="PA">PA</option>
          </select>
        </label>
        <label>
          Número de documento
          <input name="documentNumber" [(ngModel)]="datos.documentNumber" />
        </label>
        <button type="button" (click)="buscar()">Buscar persona</button>
        @if (existente()) {
          <p>{{ existente() }}</p>
        }
        @if (errores()['documentType']) {
          <p role="alert">{{ errores()['documentType'] }}</p>
        }
        @if (errores()['documentNumber']) {
          <p role="alert">{{ errores()['documentNumber'] }}</p>
        }
        <label>
          Nombre
          <input name="firstName" [(ngModel)]="datos.firstName" />
        </label>
        @if (errores()['firstName']) {
          <p role="alert">{{ errores()['firstName'] }}</p>
        }
        <label>
          Apellido
          <input name="lastName" [(ngModel)]="datos.lastName" />
        </label>
        @if (errores()['lastName']) {
          <p role="alert">{{ errores()['lastName'] }}</p>
        }
        <label>
          Fecha de nacimiento
          <input name="birthDate" type="date" [(ngModel)]="datos.birthDate" />
        </label>
        @if (errores()['birthDate']) {
          <p role="alert">{{ errores()['birthDate'] }}</p>
        }
        <label>
          Correo
          <input name="email" type="email" [(ngModel)]="datos.email" />
        </label>
        @if (errores()['email']) {
          <p role="alert">{{ errores()['email'] }}</p>
        }
        @if (errorGeneral()) {
          <p role="alert">{{ errorGeneral() }}</p>
        }
        <button type="submit" [disabled]="guardando()">Crear / asignar Administrador</button>
      </form>
    }

    @if (resultado(); as r) {
      @if (r.temporaryPassword) {
        <section role="status">
          <p>Administrador creado. Contraseña temporal (se muestra una sola vez):</p>
          <p>
            <code>{{ r.temporaryPassword }}</code>
          </p>
          <button type="button" (click)="cerrarResultado()">Ya la copié</button>
        </section>
      } @else {
        <section role="status">
          <p>{{ r.firstName }} {{ r.lastName }} ya existía y quedó asignado como Administrador.</p>
          <button type="button" (click)="cerrarResultado()">Cerrar</button>
        </section>
      }
    }
  `
})
export class AdministradorComponent implements OnInit {
  private readonly api = inject(EstablecimientosService);

  protected readonly establecimientos = signal<ResumenEstablecimiento[]>([]);
  protected readonly orgId = signal<number | null>(null);
  protected datos = vacio();
  protected readonly existente = signal<string | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);
  // La contraseña temporal vive solo en este signal: no se guarda en storage ni se escribe en logs.
  protected readonly resultado = signal<ResultadoAsignacion | null>(null);

  ngOnInit() {
    this.api.listar().subscribe({
      next: (lista) => this.establecimientos.set(lista),
      error: () => this.errorGeneral.set('No se pudo cargar el listado de establecimientos.')
    });
  }

  protected elegir(id: number | null) {
    this.orgId.set(id);
    this.datos = vacio();
    this.limpiarMensajes();
    this.resultado.set(null);
  }

  protected buscar() {
    const id = this.orgId();
    if (id === null) return;
    this.limpiarMensajes();
    this.api.buscarAdministrador(id, this.datos.documentType, this.datos.documentNumber.trim()).subscribe({
      next: (r) => {
        if (!r.exists) {
          this.existente.set('No hay una persona con ese documento: se creará una cuenta nueva.');
          return;
        }
        this.datos.firstName = r.firstName ?? '';
        this.datos.lastName = r.lastName ?? '';
        this.existente.set(`Ya existe: ${r.firstName} ${r.lastName}. Se afiliará como Administrador.`);
      },
      error: (e: HttpErrorResponse) => this.mostrarError(e)
    });
  }

  protected asignar() {
    const id = this.orgId();
    if (id === null) return;
    this.limpiarMensajes();
    this.guardando.set(true);
    this.api.asignarAdministrador(id, this.datos).subscribe({
      next: (r) => {
        this.guardando.set(false);
        this.resultado.set(r);
        this.datos = vacio();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.mostrarError(e);
      }
    });
  }

  protected cerrarResultado() {
    this.resultado.set(null);
  }

  private mostrarError(e: HttpErrorResponse) {
    const { campo, mensaje } = errorDeApi(e);
    if (campo) this.errores.set({ [campo]: mensaje });
    else this.errorGeneral.set(mensaje);
  }

  private limpiarMensajes() {
    this.errores.set({});
    this.errorGeneral.set(null);
    this.existente.set(null);
  }
}
