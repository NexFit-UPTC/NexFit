import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorDeApi } from '../core/establecimientos.service';
import { DatosSede, Sede, SedesService } from '../core/sedes.service';

const PAIS_POR_DEFECTO = 'Colombia';

function vacia(): DatosSede {
  return {
    name: '',
    phone: '',
    schedule: '',
    addressLine1: '',
    addressLine2: '',
    city: '',
    department: '',
    postalCode: '',
    country: PAIS_POR_DEFECTO
  };
}

@Component({
  selector: 'app-sedes',
  imports: [FormsModule],
  template: `
    <h1>Sedes</h1>
    <button type="button" (click)="nueva()">Crear sede</button>

    @if (formulario(); as f) {
      <form (ngSubmit)="guardar()" novalidate>
        <h2>{{ editandoId() === null ? 'Nueva sede' : 'Editar sede' }}</h2>
        <label>
          Nombre
          <input name="name" [(ngModel)]="f.name" />
        </label>
        @if (errores()['name']) {
          <p role="alert">{{ errores()['name'] }}</p>
        }
        <label>
          Dirección
          <input name="addressLine1" [(ngModel)]="f.addressLine1" />
        </label>
        @if (errores()['addressLine1']) {
          <p role="alert">{{ errores()['addressLine1'] }}</p>
        }
        <label>
          Complemento de la dirección
          <input name="addressLine2" [(ngModel)]="f.addressLine2" />
        </label>
        @if (errores()['addressLine2']) {
          <p role="alert">{{ errores()['addressLine2'] }}</p>
        }
        <label>
          Ciudad
          <input name="city" [(ngModel)]="f.city" />
        </label>
        @if (errores()['city']) {
          <p role="alert">{{ errores()['city'] }}</p>
        }
        <label>
          Departamento
          <input name="department" [(ngModel)]="f.department" />
        </label>
        @if (errores()['department']) {
          <p role="alert">{{ errores()['department'] }}</p>
        }
        <label>
          País
          <input name="country" [(ngModel)]="f.country" />
        </label>
        @if (errores()['country']) {
          <p role="alert">{{ errores()['country'] }}</p>
        }
        <label>
          Código postal
          <input name="postalCode" [(ngModel)]="f.postalCode" />
        </label>
        @if (errores()['postalCode']) {
          <p role="alert">{{ errores()['postalCode'] }}</p>
        }
        <label>
          Teléfono
          <input name="phone" [(ngModel)]="f.phone" />
        </label>
        @if (errores()['phone']) {
          <p role="alert">{{ errores()['phone'] }}</p>
        }
        <label>
          Horario
          <textarea name="schedule" [(ngModel)]="f.schedule"></textarea>
        </label>
        @if (errores()['schedule']) {
          <p role="alert">{{ errores()['schedule'] }}</p>
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
      <p>No hay sedes.</p>
    } @else {
      <table>
        <thead>
          <tr>
            <th>Nombre</th>
            <th>Dirección</th>
            <th>Ciudad</th>
            <th>Teléfono</th>
            <th>Estado</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (s of lista(); track s.id) {
            <tr>
              <td>{{ s.name }}</td>
              <td>{{ s.addressLine1 }}</td>
              <td>{{ s.city }}, {{ s.department }}</td>
              <td>{{ s.phone }}</td>
              <td>{{ s.active ? 'Activa' : 'Inactiva' }}</td>
              <td>
                <button type="button" (click)="editar(s)">Editar</button>
                <button type="button" class="alternar" (click)="alternar(s)">
                  {{ s.active ? 'Desactivar' : 'Activar' }}
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `
})
export class SedesComponent implements OnInit {
  private readonly api = inject(SedesService);

  protected readonly lista = signal<Sede[]>([]);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly formulario = signal<DatosSede | null>(null);
  protected readonly editandoId = signal<number | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);

  ngOnInit() {
    this.recargar();
  }

  protected nueva() {
    this.abrir(null, vacia());
  }

  protected editar(s: Sede) {
    this.abrir(s.id, {
      name: s.name,
      phone: s.phone ?? '',
      schedule: s.schedule ?? '',
      addressLine1: s.addressLine1,
      addressLine2: s.addressLine2 ?? '',
      city: s.city,
      department: s.department,
      postalCode: s.postalCode ?? '',
      country: s.country
    });
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

  protected alternar(s: Sede) {
    this.error.set(null);
    this.api.activar(s.id, !s.active).subscribe({
      next: () => this.recargar(),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  private abrir(id: number | null, datos: DatosSede) {
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
