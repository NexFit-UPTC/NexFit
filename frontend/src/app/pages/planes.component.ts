import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorDeApi } from '../core/establecimientos.service';
import { Plan, PlanesService } from '../core/planes.service';

/** Datos del formulario: precio y duración son campos de texto/número que el backend valida. */
interface FormularioPlan {
  name: string;
  description: string;
  price: number | string | null;
  durationDays: number | string | null;
  benefits: string;
}

@Component({
  selector: 'app-planes',
  imports: [FormsModule],
  template: `
    <h1>Planes de membresía</h1>
    <button type="button" (click)="nuevo()">Crear plan</button>

    @if (formulario(); as f) {
      <form (ngSubmit)="guardar()" novalidate>
        <h2>{{ editandoId() === null ? 'Nuevo plan' : 'Editar plan' }}</h2>
        <label>
          Nombre
          <input name="name" [(ngModel)]="f.name" />
        </label>
        @if (errores()['name']) {
          <p role="alert">{{ errores()['name'] }}</p>
        }
        <label>
          Precio (COP)
          <input name="price" type="number" min="0" step="0.01" [(ngModel)]="f.price" />
        </label>
        @if (errores()['price']) {
          <p role="alert">{{ errores()['price'] }}</p>
        }
        <label>
          Duración (días)
          <input name="durationDays" type="number" min="1" step="1" [(ngModel)]="f.durationDays" />
        </label>
        @if (errores()['durationDays']) {
          <p role="alert">{{ errores()['durationDays'] }}</p>
        }
        <label>
          Descripción
          <textarea name="description" [(ngModel)]="f.description"></textarea>
        </label>
        @if (errores()['description']) {
          <p role="alert">{{ errores()['description'] }}</p>
        }
        <label>
          Beneficios
          <textarea name="benefits" [(ngModel)]="f.benefits"></textarea>
        </label>
        @if (errores()['benefits']) {
          <p role="alert">{{ errores()['benefits'] }}</p>
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
      <p>No hay planes.</p>
    } @else {
      <table>
        <thead>
          <tr>
            <th>Nombre</th>
            <th>Precio</th>
            <th>Duración</th>
            <th>Estado</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (p of lista(); track p.id) {
            <tr>
              <td>{{ p.name }}</td>
              <td>{{ p.price }}</td>
              <td>{{ p.durationDays }} días</td>
              <td>{{ p.active ? 'Activo' : 'Inactivo' }}</td>
              <td>
                <button type="button" (click)="editar(p)">Editar</button>
                <button type="button" class="alternar" (click)="alternar(p)">
                  {{ p.active ? 'Desactivar' : 'Activar' }}
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `
})
export class PlanesComponent implements OnInit {
  private readonly api = inject(PlanesService);

  protected readonly lista = signal<Plan[]>([]);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly formulario = signal<FormularioPlan | null>(null);
  protected readonly editandoId = signal<number | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);

  ngOnInit() {
    this.recargar();
  }

  protected nuevo() {
    this.abrir(null, { name: '', description: '', price: null, durationDays: null, benefits: '' });
  }

  protected editar(p: Plan) {
    this.abrir(p.id, {
      name: p.name,
      description: p.description ?? '',
      price: p.price,
      durationDays: p.durationDays,
      benefits: p.benefits ?? ''
    });
  }

  protected cancelar() {
    this.formulario.set(null);
  }

  protected guardar() {
    const f = this.formulario();
    if (!f) return;
    const id = this.editandoId();
    // El backend valida; se envía tal cual (vacío → null) para que el mensaje sea el de la API.
    const datos = {
      name: f.name,
      description: f.description,
      price: f.price as number,
      durationDays: f.durationDays as number,
      benefits: f.benefits
    };
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

  protected alternar(p: Plan) {
    this.error.set(null);
    this.api.activar(p.id, !p.active).subscribe({
      next: () => this.recargar(),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  private abrir(id: number | null, datos: FormularioPlan) {
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
