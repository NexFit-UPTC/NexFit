import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ClientesService, ResumenCliente } from '../core/clientes.service';
import { errorDeApi } from '../core/establecimientos.service';
import {
  ETIQUETA_ESTADO_PAGO,
  ETIQUETA_METODO,
  EstadoPago,
  MetodoPago,
  Pago,
  PagosService
} from '../core/pagos.service';
import { SessionService } from '../core/session.service';

interface FormularioPago {
  clienteBusqueda: string;
  memberId: number | null;
  concept: string;
  amount: number | string | null;
  method: MetodoPago;
  status: 'completed' | 'pending';
  reference: string;
}

const TAMANO = 20;

/**
 * Pagos (US-26, US-27, US-28, US-50). El Staff registra, anula y completa; el Administrador consulta, anula y
 * completa; el Cliente solo ve los suyos. El historial no se borra: un pago solo cambia de estado.
 */
@Component({
  selector: 'app-pagos',
  imports: [FormsModule, CurrencyPipe, DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>{{ esCliente() ? 'Mis pagos' : 'Pagos' }}</h1>
        @if (esStaff()) {
          <button type="button" class="nf-boton" (click)="abrirRegistro()">Registrar pago</button>
        }
      </div>

      @if (formulario(); as f) {
        <form class="nf-tarjeta" (ngSubmit)="registrar()" novalidate>
          <h2>Registrar pago</h2>
          <div class="nf-rejilla">
            <label>
              Buscar cliente (nombre o documento)
              <input name="clienteBusqueda" [(ngModel)]="f.clienteBusqueda" (input)="buscarClientes(f.clienteBusqueda)" />
            </label>
            <label>
              Cliente
              <select name="memberId" [(ngModel)]="f.memberId">
                <option [ngValue]="null">Selecciona…</option>
                @for (c of clientes(); track c.memberId) {
                  <option [ngValue]="c.memberId">{{ c.firstName }} {{ c.lastName }} · {{ c.documentNumber }}</option>
                }
              </select>
            </label>
            <label>
              Concepto
              <input name="concept" [(ngModel)]="f.concept" />
            </label>
            <label>
              Valor (COP)
              <input name="amount" type="number" min="0" step="0.01" [(ngModel)]="f.amount" />
            </label>
            <label>
              Método
              <select name="method" [(ngModel)]="f.method">
                <option value="cash">Efectivo</option>
                <option value="card">Tarjeta</option>
                <option value="transfer">Transferencia</option>
                <option value="other">Otro</option>
              </select>
            </label>
            <label>
              Estado
              <select name="status" [(ngModel)]="f.status">
                <option value="completed">Completado</option>
                <option value="pending">Pendiente</option>
              </select>
            </label>
            <label>
              Referencia
              <input name="reference" [(ngModel)]="f.reference" />
            </label>
          </div>
          @for (campo of camposConError(); track campo) {
            <p class="nf-error-campo" role="alert">{{ errores()[campo] }}</p>
          }
          @if (errorGeneral()) {
            <p class="nf-alerta" role="alert">{{ errorGeneral() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Guardar pago</button>
            <button type="button" class="nf-boton-sec" (click)="formulario.set(null)">Cancelar</button>
          </div>
        </form>
      }

      <div class="nf-filtros">
        <label>
          Estado
          <select name="filtroEstado" [ngModel]="estado()" (ngModelChange)="filtrar({ estado: $event })">
            <option value="">Todos</option>
            <option value="completed">Completados</option>
            <option value="pending">Pendientes</option>
            <option value="voided">Anulados</option>
          </select>
        </label>
        <label>
          Desde
          <input name="desde" type="date" [ngModel]="desde()" (ngModelChange)="filtrar({ desde: $event })" />
        </label>
        <label>
          Hasta
          <input name="hasta" type="date" [ngModel]="hasta()" (ngModelChange)="filtrar({ hasta: $event })" />
        </label>
      </div>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (items().length === 0) {
        <p class="nf-vacio">No hay pagos para este filtro.</p>
      } @else {
        <p class="nf-totales">
          {{ total() }} pagos · total completado:
          <strong>{{ totalCompletado() | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</strong>
        </p>
        <table class="nf-tabla">
          <thead>
            <tr>
              <th>Fecha</th>
              @if (!esCliente()) {
                <th>Cliente</th>
              }
              <th>Concepto</th>
              <th>Método</th>
              <th>Valor</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (p of items(); track p.id) {
              <tr>
                <td>{{ p.paidAt | date: 'dd/MM/yyyy HH:mm' }}</td>
                @if (!esCliente()) {
                  <td>{{ p.memberName }}</td>
                }
                <td>
                  {{ p.concept }}
                  @if (p.voidReason) {
                    <span class="nf-ayuda"> · {{ p.voidReason }}</span>
                  }
                </td>
                <td>{{ metodo(p.method) }}</td>
                <td>{{ p.amount | currency: 'COP' : 'symbol-narrow' : '1.0-0' }}</td>
                <td>
                  <span class="nf-insignia" [class.ok]="p.status === 'completed'" [class.alerta]="p.status === 'voided'">
                    {{ estadoEtiqueta(p.status) }}
                  </span>
                </td>
                <td class="nf-acciones">
                  @if (!esCliente() && p.status === 'pending') {
                    <button type="button" class="nf-boton-sec" (click)="completar(p)">Completar</button>
                    <button type="button" class="nf-boton-sec" (click)="pedirAnulacion(p)">Anular</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
        @if (paginas() > 1) {
          <div class="nf-acciones">
            <button type="button" class="nf-boton-sec" [disabled]="pagina() === 0" (click)="irAPagina(pagina() - 1)">Anterior</button>
            <span class="nf-ayuda">Página {{ pagina() + 1 }} de {{ paginas() }}</span>
            <button type="button" class="nf-boton-sec" [disabled]="pagina() + 1 >= paginas()" (click)="irAPagina(pagina() + 1)">Siguiente</button>
          </div>
        }
      }

      @if (anulando(); as a) {
        <form class="nf-tarjeta" (ngSubmit)="anular()" novalidate>
          <h2>Anular pago · {{ a.pago.concept }}</h2>
          <label class="nf-rejilla">
            Motivo
            <textarea name="motivoAnulacion" rows="2" [(ngModel)]="a.motivo"></textarea>
          </label>
          @if (errorAnulacion()) {
            <p class="nf-error-campo" role="alert">{{ errorAnulacion() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Confirmar anulación</button>
            <button type="button" class="nf-boton-sec" (click)="anulando.set(null)">Volver</button>
          </div>
        </form>
      }
    </section>
  `
})
export class PagosComponent implements OnInit {
  private readonly api = inject(PagosService);
  private readonly clientesApi = inject(ClientesService);
  private readonly sesion = inject(SessionService);

  protected readonly esStaff = computed(() => this.sesion.roles().includes('staff'));
  protected readonly esCliente = computed(() => {
    const roles = this.sesion.roles();
    return roles.includes('client') && !roles.includes('staff') && !roles.includes('admin');
  });

  protected readonly items = signal<Pago[]>([]);
  protected readonly total = signal(0);
  protected readonly totalCompletado = signal(0);
  protected readonly pagina = signal(0);
  protected readonly paginas = computed(() => Math.ceil(this.total() / TAMANO));
  protected readonly estado = signal<EstadoPago | ''>('');
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly formulario = signal<FormularioPago | null>(null);
  protected readonly clientes = signal<ResumenCliente[]>([]);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly camposConError = computed(() => Object.keys(this.errores()));
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);

  protected readonly anulando = signal<{ pago: Pago; motivo: string } | null>(null);
  protected readonly errorAnulacion = signal<string | null>(null);

  ngOnInit() {
    this.recargar();
  }

  protected metodo(m: MetodoPago): string {
    return ETIQUETA_METODO[m];
  }

  protected estadoEtiqueta(e: EstadoPago): string {
    return ETIQUETA_ESTADO_PAGO[e];
  }

  protected filtrar(cambio: { estado?: EstadoPago | ''; desde?: string; hasta?: string }) {
    if (cambio.estado !== undefined) this.estado.set(cambio.estado);
    if (cambio.desde !== undefined) this.desde.set(cambio.desde);
    if (cambio.hasta !== undefined) this.hasta.set(cambio.hasta);
    this.pagina.set(0);
    this.recargar();
  }

  protected irAPagina(pagina: number) {
    this.pagina.set(pagina);
    this.recargar();
  }

  protected abrirRegistro() {
    this.errores.set({});
    this.errorGeneral.set(null);
    this.formulario.set({
      clienteBusqueda: '',
      memberId: null,
      concept: '',
      amount: null,
      method: 'cash',
      status: 'completed',
      reference: ''
    });
    this.buscarClientes('');
  }

  protected buscarClientes(q: string) {
    this.clientesApi.listar({ q, active: true, size: 20 }).subscribe((p) => this.clientes.set(p.content));
  }

  protected registrar() {
    const f = this.formulario();
    if (!f) return;
    if (f.memberId === null) {
      this.errores.set({ memberId: 'Selecciona un cliente' });
      return;
    }
    this.guardando.set(true);
    this.errores.set({});
    this.errorGeneral.set(null);
    this.api
      .registrar({
        memberId: f.memberId,
        concept: f.concept,
        amount: f.amount as number,
        method: f.method,
        status: f.status,
        ...(f.reference.trim() ? { reference: f.reference.trim() } : {})
      })
      .subscribe({
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

  protected completar(p: Pago) {
    this.error.set(null);
    this.api.completar(p.id).subscribe({
      next: () => this.recargar(),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  protected pedirAnulacion(pago: Pago) {
    this.errorAnulacion.set(null);
    this.anulando.set({ pago, motivo: '' });
  }

  protected anular() {
    const a = this.anulando();
    if (!a) return;
    this.guardando.set(true);
    this.api.anular(a.pago.id, a.motivo).subscribe({
      next: () => {
        this.guardando.set(false);
        this.anulando.set(null);
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorAnulacion.set(errorDeApi(e).mensaje);
      }
    });
  }

  private recargar() {
    this.cargando.set(true);
    this.api
      .listar({
        ...(this.estado() ? { status: this.estado() as EstadoPago } : {}),
        ...(this.desde() ? { from: this.desde() } : {}),
        ...(this.hasta() ? { to: this.hasta() } : {}),
        page: this.pagina(),
        size: TAMANO
      })
      .subscribe({
        next: (r) => {
          this.items.set(r.items);
          this.total.set(r.total);
          this.totalCompletado.set(r.completedTotal);
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
