import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Eps, EpsService } from '../core/eps.service';
import { errorDeApi } from '../core/establecimientos.service';

/**
 * Catálogo de EPS (US-18, NEX-30), solo del Master. Una EPS desactivada deja de ofrecerse al registrar clientes
 * nuevos, pero los clientes que ya la tienen la conservan.
 */
@Component({
  selector: 'app-eps',
  imports: [FormsModule],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Catálogo de EPS</h1>
      </div>

      <form class="nf-tarjeta nf-formulario" (ngSubmit)="crear()" novalidate>
        <h2>Agregar una EPS</h2>
        <label class="nf-campo">
          Nombre
          <input class="nf-entrada" name="nueva" [(ngModel)]="nueva" />
        </label>
        @if (errorNueva()) {
          <p class="nf-error-campo" role="alert">{{ errorNueva() }}</p>
        }
        <div class="nf-acciones">
          <button type="submit" class="nf-boton" [disabled]="guardando()">Agregar</button>
        </div>
      </form>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (lista().length === 0) {
        <p class="nf-vacio">No hay EPS en el catálogo.</p>
      } @else {
        <div class="nf-tabla-envoltorio">
          <table class="nf-tabla">
            <thead>
              <tr>
                <th>Nombre</th>
                <th>Estado</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              @for (e of lista(); track e.id) {
                <tr>
                  <td>
                    @if (editandoId() === e.id) {
                      <input class="nf-entrada" name="edicion" aria-label="Nombre de la EPS" [(ngModel)]="nombreEdicion" />
                      @if (errorEdicion()) {
                        <p class="nf-error-campo" role="alert">{{ errorEdicion() }}</p>
                      }
                    } @else {
                      {{ e.name }}
                    }
                  </td>
                  <td>
                    <span class="nf-insignia" [class.ok]="e.active">{{ e.active ? 'Activa' : 'Inactiva' }}</span>
                  </td>
                  <td class="nf-acciones-celda">
                    @if (editandoId() === e.id) {
                      <button type="button" class="nf-boton" (click)="guardarEdicion(e)">Guardar</button>
                      <button type="button" class="nf-boton-sec" (click)="editandoId.set(null)">Cancelar</button>
                    } @else {
                      <button type="button" class="nf-boton-sec" (click)="editar(e)">Renombrar</button>
                      <button type="button" class="alternar nf-boton-sec" (click)="alternar(e)">
                        {{ e.active ? 'Desactivar' : 'Activar' }}
                      </button>
                    }
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
export class EpsComponent implements OnInit {
  private readonly api = inject(EpsService);

  protected readonly lista = signal<Eps[]>([]);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly guardando = signal(false);

  protected nueva = '';
  protected readonly errorNueva = signal<string | null>(null);

  protected readonly editandoId = signal<number | null>(null);
  protected nombreEdicion = '';
  protected readonly errorEdicion = signal<string | null>(null);

  ngOnInit() {
    this.recargar();
  }

  protected crear() {
    this.guardando.set(true);
    this.errorNueva.set(null);
    this.api.crear(this.nueva).subscribe({
      next: () => {
        this.guardando.set(false);
        this.nueva = '';
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorNueva.set(errorDeApi(e).mensaje);
      }
    });
  }

  protected editar(e: Eps) {
    this.errorEdicion.set(null);
    this.nombreEdicion = e.name;
    this.editandoId.set(e.id);
  }

  protected guardarEdicion(e: Eps) {
    this.errorEdicion.set(null);
    this.api.renombrar(e.id, this.nombreEdicion).subscribe({
      next: () => {
        this.editandoId.set(null);
        this.recargar();
      },
      error: (err: HttpErrorResponse) => this.errorEdicion.set(errorDeApi(err).mensaje)
    });
  }

  protected alternar(e: Eps) {
    this.error.set(null);
    this.api.activar(e.id, !e.active).subscribe({
      next: () => this.recargar(),
      error: (err: HttpErrorResponse) => this.error.set(errorDeApi(err).mensaje)
    });
  }

  private recargar() {
    this.cargando.set(true);
    this.api.listar(true).subscribe({
      next: (datos) => {
        this.lista.set(datos);
        this.error.set(null);
        this.cargando.set(false);
      },
      error: () => {
        this.error.set('No se pudo cargar el catálogo.');
        this.cargando.set(false);
      }
    });
  }
}
