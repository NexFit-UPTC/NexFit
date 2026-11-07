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
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Sedes</h1>
        <button type="button" class="nf-boton" (click)="nueva()">Crear sede</button>
      </div>

    @if (formulario(); as f) {
      <form class="nf-tarjeta nf-formulario" (ngSubmit)="guardar()" novalidate>
        <h2>{{ editandoId() === null ? 'Nueva sede' : 'Editar sede' }}</h2>
        <label class="nf-campo">
          Nombre
          <input class="nf-entrada" name="name" [(ngModel)]="f.name" />
        </label>
        @if (errores()['name']) {
          <p class="nf-error-campo" role="alert">{{ errores()['name'] }}</p>
        }
        <label class="nf-campo">
          Dirección
          <input class="nf-entrada" name="addressLine1" [(ngModel)]="f.addressLine1" />
        </label>
        @if (errores()['addressLine1']) {
          <p class="nf-error-campo" role="alert">{{ errores()['addressLine1'] }}</p>
        }
        <label class="nf-campo">
          Complemento de la dirección
          <input class="nf-entrada" name="addressLine2" [(ngModel)]="f.addressLine2" />
        </label>
        @if (errores()['addressLine2']) {
          <p class="nf-error-campo" role="alert">{{ errores()['addressLine2'] }}</p>
        }
        <label class="nf-campo">
          Ciudad
          <input class="nf-entrada" name="city" [(ngModel)]="f.city" />
        </label>
        @if (errores()['city']) {
          <p class="nf-error-campo" role="alert">{{ errores()['city'] }}</p>
        }
        <label class="nf-campo">
          Departamento
          <input class="nf-entrada" name="department" [(ngModel)]="f.department" />
        </label>
        @if (errores()['department']) {
          <p class="nf-error-campo" role="alert">{{ errores()['department'] }}</p>
        }
        <label class="nf-campo">
          País
          <input class="nf-entrada" name="country" [(ngModel)]="f.country" />
        </label>
        @if (errores()['country']) {
          <p class="nf-error-campo" role="alert">{{ errores()['country'] }}</p>
        }
        <label class="nf-campo">
          Código postal
          <input class="nf-entrada" name="postalCode" [(ngModel)]="f.postalCode" />
        </label>
        @if (errores()['postalCode']) {
          <p class="nf-error-campo" role="alert">{{ errores()['postalCode'] }}</p>
        }
        <label class="nf-campo">
          Teléfono
          <input class="nf-entrada" name="phone" [(ngModel)]="f.phone" />
        </label>
        @if (errores()['phone']) {
          <p class="nf-error-campo" role="alert">{{ errores()['phone'] }}</p>
        }
        <label class="nf-campo">
          Horario
          <textarea class="nf-entrada" name="schedule" [(ngModel)]="f.schedule"></textarea>
        </label>
        @if (errores()['schedule']) {
          <p class="nf-error-campo" role="alert">{{ errores()['schedule'] }}</p>
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
      <p class="nf-vacio">No hay sedes.</p>
    } @else {
      <div class="nf-tabla-envoltorio">
      <table class="nf-tabla">
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
              <td>
                <span class="nf-insignia" [class.ok]="s.active">{{ s.active ? 'Activa' : 'Inactiva' }}</span>
              </td>
              <td class="nf-acciones-celda">
                <button type="button" class="nf-boton-sec" (click)="editar(s)">Editar</button>
                <button type="button" class="alternar nf-boton-sec" (click)="alternar(s)">
                  {{ s.active ? 'Desactivar' : 'Activar' }}
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
      </div>
    }
    </section>
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
