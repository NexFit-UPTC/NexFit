import { HttpErrorResponse } from '@angular/common/http';
import { CurrencyPipe } from '@angular/common';
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
  imports: [CurrencyPipe, FormsModule],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Planes de membresía</h1>
        <button type="button" class="nf-boton" (click)="nuevo()">Crear plan</button>
      </div>

    @if (formulario(); as f) {
      <form class="nf-tarjeta nf-formulario" (ngSubmit)="guardar()" novalidate>
        <h2>{{ editandoId() === null ? 'Nuevo plan' : 'Editar plan' }}</h2>
        <label class="nf-campo">
          Nombre
          <input class="nf-entrada" name="name" [(ngModel)]="f.name" />
        </label>
        @if (errores()['name']) {
          <p class="nf-error-campo" role="alert">{{ errores()['name'] }}</p>
        }
        <label class="nf-campo">
          Precio (COP)
          <input class="nf-entrada" name="price" type="number" min="0" step="0.01" [(ngModel)]="f.price" />
        </label>
        @if (errores()['price']) {
          <p class="nf-error-campo" role="alert">{{ errores()['price'] }}</p>
        }
        <label class="nf-campo">
          Duración (días)
          <input class="nf-entrada" name="durationDays" type="number" min="1" step="1" [(ngModel)]="f.durationDays" />
        </label>
        @if (errores()['durationDays']) {
          <p class="nf-error-campo" role="alert">{{ errores()['durationDays'] }}</p>
        }
        <label class="nf-campo">
          Descripción
          <textarea class="nf-entrada" name="description" [(ngModel)]="f.description"></textarea>
        </label>
        @if (errores()['description']) {
          <p class="nf-error-campo" role="alert">{{ errores()['description'] }}</p>
        }
        <label class="nf-campo">
          Beneficios
          <textarea class="nf-entrada" name="benefits" [(ngModel)]="f.benefits"></textarea>
        </label>
        @if (errores()['benefits']) {
          <p class="nf-error-campo" role="alert">{{ errores()['benefits'] }}</p>
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
      <p class="nf-vacio">No hay planes.</p>
    } @else {
      <div class="nf-tabla-envoltorio">
      <table class="nf-tabla">
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
              <td>{{ p.price | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</td>
              <td>{{ p.durationDays }} {{ p.durationDays === 1 ? 'día' : 'días' }}</td>
              <td>
                <span class="nf-insignia" [class.ok]="p.active">{{ p.active ? 'Activo' : 'Inactivo' }}</span>
              </td>
              <td class="nf-acciones-celda">
                <button type="button" class="nf-boton-sec" (click)="editar(p)">Editar</button>
                <button type="button" class="alternar nf-boton-sec" (click)="alternar(p)">
                  {{ p.active ? 'Desactivar' : 'Activar' }}
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
