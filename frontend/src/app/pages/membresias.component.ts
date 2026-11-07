import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ClientesService, ResumenCliente } from '../core/clientes.service';
import { errorDeApi } from '../core/establecimientos.service';
import {
  Contratacion,
  ETIQUETA_ESTADO,
  EstadoEfectivo,
  Membresia,
  MembresiasService
} from '../core/membresias.service';
import { DatosPagoContratacion, MetodoPago } from '../core/pagos.service';
import { Plan, PlanesService } from '../core/planes.service';
import { SessionService } from '../core/session.service';

type Vista = EstadoEfectivo | 'todas' | 'por-vencer';
type Accion = 'suspender' | 'reactivar' | 'cancelar';

interface FormularioContratacion {
  clienteBusqueda: string;
  memberId: number | null;
  planId: number | null;
  startDate: string;
  conPago: boolean;
  metodo: MetodoPago;
  estadoPago: 'completed' | 'pending';
  referencia: string;
}

const VISTAS: { valor: Vista; etiqueta: string }[] = [
  { valor: 'todas', etiqueta: 'Todas' },
  { valor: 'current', etiqueta: 'Vigentes' },
  { valor: 'por-vencer', etiqueta: 'Por vencer' },
  { valor: 'expired', etiqueta: 'Vencidas' },
  { valor: 'scheduled', etiqueta: 'Programadas' },
  { valor: 'suspended', etiqueta: 'Suspendidas' },
  { valor: 'cancelled', etiqueta: 'Canceladas' }
];

/**
 * Membresías del gimnasio (US-20 a US-25, FE-05). El Staff contrata y renueva; Staff y Administrador consultan,
 * suspenden, reactivan y cancelan. El servidor decide qué puede hacer cada rol: aquí solo se ocultan los botones.
 */
@Component({
  selector: 'app-membresias',
  imports: [FormsModule, CurrencyPipe, DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Membresías</h1>
        @if (esStaff()) {
          <button type="button" class="nf-boton" (click)="abrirContratacion()">Contratar membresía</button>
        }
      </div>

      @if (contratacion(); as f) {
        <form class="nf-tarjeta" (ngSubmit)="contratar()" novalidate>
          <h2>Contratar membresía</h2>
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
              Plan
              <select name="planId" [(ngModel)]="f.planId">
                <option [ngValue]="null">Selecciona…</option>
                @for (p of planes(); track p.id) {
                  <option [ngValue]="p.id">{{ p.name }} · {{ p.price | currency: 'COP' : 'symbol-narrow' : '1.0-0' }} · {{ p.durationDays }} días</option>
                }
              </select>
            </label>
            <label>
              Inicio (vacío = hoy)
              <input name="startDate" type="date" [(ngModel)]="f.startDate" />
            </label>
          </div>
          @if (errores()['memberId']) {
            <p class="nf-error-campo" role="alert">{{ errores()['memberId'] }}</p>
          }
          @if (errores()['planId']) {
            <p class="nf-error-campo" role="alert">{{ errores()['planId'] }}</p>
          }
          @if (errores()['startDate']) {
            <p class="nf-error-campo" role="alert">{{ errores()['startDate'] }}</p>
          }
          <label class="nf-ayuda">
            <input name="conPago" type="checkbox" [(ngModel)]="f.conPago" /> Registrar el pago ahora (por el precio del plan)
          </label>
          @if (f.conPago) {
            <div class="nf-rejilla">
              <label>
                Método
                <select name="metodo" [(ngModel)]="f.metodo">
                  <option value="cash">Efectivo</option>
                  <option value="card">Tarjeta</option>
                  <option value="transfer">Transferencia</option>
                  <option value="other">Otro</option>
                </select>
              </label>
              <label>
                Estado del pago
                <select name="estadoPago" [(ngModel)]="f.estadoPago">
                  <option value="completed">Completado</option>
                  <option value="pending">Pendiente</option>
                </select>
              </label>
              <label>
                Referencia
                <input name="referencia" [(ngModel)]="f.referencia" />
              </label>
            </div>
            @if (errores()['payment.method'] || errores()['payment']) {
              <p class="nf-error-campo" role="alert">{{ errores()['payment.method'] ?? errores()['payment'] }}</p>
            }
          }
          @if (errorGeneral()) {
            <p class="nf-alerta" role="alert">{{ errorGeneral() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Confirmar contratación</button>
            <button type="button" class="nf-boton-sec" (click)="contratacion.set(null)">Cancelar</button>
          </div>
        </form>
      }

      @if (aviso()) {
        <p class="nf-ayuda" role="status">{{ aviso() }}</p>
      }
      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }

      <div class="nf-filtros">
        <label>
          Mostrar
          <select name="vista" [ngModel]="vista()" (ngModelChange)="cambiarVista($event)">
            @for (v of vistas; track v.valor) {
              <option [value]="v.valor">{{ v.etiqueta }}</option>
            }
          </select>
        </label>
        @if (vista() === 'por-vencer') {
          <label>
            Vencen en los próximos (días)
            <input name="dias" type="number" min="0" max="365" [ngModel]="dias()" (ngModelChange)="cambiarDias($event)" />
          </label>
        }
      </div>

      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (lista().length === 0) {
        <p class="nf-vacio">No hay membresías para este filtro.</p>
      } @else {
        <table class="nf-tabla">
          <thead>
            <tr>
              <th>Cliente</th>
              <th>Plan</th>
              <th>Inicio</th>
              <th>Vence</th>
              <th>Estado</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (m of lista(); track m.id) {
              <tr>
                <td>{{ m.memberName }}</td>
                <td>{{ m.planName }}</td>
                <td>{{ m.startDate | date: 'dd/MM/yyyy' }}</td>
                <td>{{ m.endDate | date: 'dd/MM/yyyy' }}</td>
                <td>
                  <span class="nf-insignia" [class.ok]="m.effectiveStatus === 'current'"
                        [class.alerta]="m.effectiveStatus === 'cancelled' || m.effectiveStatus === 'expired'">
                    {{ etiqueta(m.effectiveStatus) }}
                  </span>
                  @if (m.statusReason) {
                    <span class="nf-ayuda"> · {{ m.statusReason }}</span>
                  }
                </td>
                <td class="nf-acciones">
                  @if (esStaff() && m.effectiveStatus === 'current') {
                    <button type="button" class="nf-boton-sec" (click)="renovar(m)">Renovar</button>
                  }
                  @if (m.status === 'active') {
                    <button type="button" class="nf-boton-sec" (click)="pedirMotivo(m, 'suspender')">Suspender</button>
                  }
                  @if (m.status === 'suspended') {
                    <button type="button" class="nf-boton-sec" (click)="pedirMotivo(m, 'reactivar')">Reactivar</button>
                  }
                  @if (m.status !== 'cancelled') {
                    <button type="button" class="nf-boton-sec" (click)="pedirMotivo(m, 'cancelar')">Cancelar</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }

      @if (renovacion(); as r) {
        <form class="nf-tarjeta" (ngSubmit)="confirmarRenovacion()" novalidate>
          <h2>Renovar · {{ r.membresia.memberName }}</h2>
          <p class="nf-ayuda">
            La nueva membresía inicia el día siguiente al vencimiento ({{ r.membresia.endDate | date: 'dd/MM/yyyy' }}) con el plan
            {{ r.membresia.planName }}.
          </p>
          <label class="nf-ayuda">
            <input name="renovarConPago" type="checkbox" [(ngModel)]="r.conPago" /> Registrar el pago ahora (por el precio del plan)
          </label>
          @if (r.conPago) {
            <div class="nf-rejilla">
              <label>
                Método
                <select name="renovarMetodo" [(ngModel)]="r.metodo">
                  <option value="cash">Efectivo</option>
                  <option value="card">Tarjeta</option>
                  <option value="transfer">Transferencia</option>
                  <option value="other">Otro</option>
                </select>
              </label>
              <label>
                Estado del pago
                <select name="renovarEstado" [(ngModel)]="r.estadoPago">
                  <option value="completed">Completado</option>
                  <option value="pending">Pendiente</option>
                </select>
              </label>
            </div>
          }
          @if (errorRenovacion()) {
            <p class="nf-error-campo" role="alert">{{ errorRenovacion() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Confirmar renovación</button>
            <button type="button" class="nf-boton-sec" (click)="renovacion.set(null)">Volver</button>
          </div>
        </form>
      }

      @if (motivo(); as mo) {
        <form class="nf-tarjeta" (ngSubmit)="confirmarMotivo()" novalidate>
          <h2>{{ tituloAccion(mo.accion) }} · {{ mo.membresia.memberName }}</h2>
          <label class="nf-rejilla">
            Motivo
            <textarea name="motivo" rows="2" [(ngModel)]="mo.texto"></textarea>
          </label>
          @if (errorMotivo()) {
            <p class="nf-error-campo" role="alert">{{ errorMotivo() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Confirmar</button>
            <button type="button" class="nf-boton-sec" (click)="motivo.set(null)">Volver</button>
          </div>
        </form>
      }
    </section>
  `
})
export class MembresiasComponent implements OnInit {
  private readonly api = inject(MembresiasService);
  private readonly clientesApi = inject(ClientesService);
  private readonly planesApi = inject(PlanesService);
  private readonly sesion = inject(SessionService);

  protected readonly vistas = VISTAS;
  protected readonly esStaff = computed(() => this.sesion.roles().includes('staff'));
  protected readonly vista = signal<Vista>('todas');
  protected readonly dias = signal(7);
  protected readonly lista = signal<Membresia[]>([]);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);

  protected readonly contratacion = signal<FormularioContratacion | null>(null);
  protected readonly clientes = signal<ResumenCliente[]>([]);
  protected readonly planes = signal<Plan[]>([]);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);

  protected readonly renovacion = signal<{
    membresia: Membresia;
    conPago: boolean;
    metodo: MetodoPago;
    estadoPago: 'completed' | 'pending';
  } | null>(null);
  protected readonly errorRenovacion = signal<string | null>(null);

  protected readonly motivo = signal<{ membresia: Membresia; accion: Accion; texto: string } | null>(null);
  protected readonly errorMotivo = signal<string | null>(null);

  ngOnInit() {
    this.recargar();
  }

  protected etiqueta(estado: EstadoEfectivo): string {
    return ETIQUETA_ESTADO[estado];
  }

  protected tituloAccion(accion: Accion): string {
    return { suspender: 'Suspender membresía', reactivar: 'Reactivar membresía', cancelar: 'Cancelar membresía' }[accion];
  }

  protected cambiarVista(vista: Vista) {
    this.vista.set(vista);
    this.recargar();
  }

  protected cambiarDias(dias: number) {
    this.dias.set(dias);
    this.recargar();
  }

  protected abrirContratacion() {
    this.errores.set({});
    this.errorGeneral.set(null);
    this.aviso.set(null);
    this.contratacion.set({
      clienteBusqueda: '',
      memberId: null,
      planId: null,
      startDate: '',
      conPago: true,
      metodo: 'cash',
      estadoPago: 'completed',
      referencia: ''
    });
    this.clientesApi.listar({ active: true, size: 20 }).subscribe((p) => this.clientes.set(p.content));
    this.planesApi.listar().subscribe((ps) => this.planes.set(ps.filter((p) => p.active)));
  }

  protected buscarClientes(q: string) {
    this.clientesApi.listar({ q, active: true, size: 20 }).subscribe((p) => this.clientes.set(p.content));
  }

  protected contratar() {
    const f = this.contratacion();
    if (!f) return;
    if (f.memberId === null || f.planId === null) {
      this.errores.set({
        ...(f.memberId === null ? { memberId: 'Selecciona un cliente' } : {}),
        ...(f.planId === null ? { planId: 'Selecciona un plan' } : {})
      });
      return;
    }
    this.guardando.set(true);
    this.errores.set({});
    this.errorGeneral.set(null);
    this.api
      .contratar({
        memberId: f.memberId,
        planId: f.planId,
        ...(f.startDate ? { startDate: f.startDate } : {}),
        ...(f.conPago ? { payment: this.pago(f) } : {})
      })
      .subscribe({
        next: (c) => {
          this.guardando.set(false);
          this.contratacion.set(null);
          this.aviso.set(this.resumen('Membresía contratada', c));
          this.recargar();
        },
        error: (e: HttpErrorResponse) => {
          this.guardando.set(false);
          const { campo, mensaje } = errorDeApi(e);
          if (campo && e.status === 400) this.errores.set({ [campo]: mensaje });
          else this.errorGeneral.set(mensaje);
        }
      });
  }

  protected renovar(m: Membresia) {
    this.error.set(null);
    this.aviso.set(null);
    this.errorRenovacion.set(null);
    this.renovacion.set({ membresia: m, conPago: true, metodo: 'cash', estadoPago: 'completed' });
  }

  /** Renueva con el mismo plan; el pago (por el precio del plan) es opcional. */
  protected confirmarRenovacion() {
    const r = this.renovacion();
    if (!r) return;
    this.guardando.set(true);
    this.api
      .renovar(r.membresia.id, null, r.conPago ? { method: r.metodo, status: r.estadoPago } : undefined)
      .subscribe({
        next: (c) => {
          this.guardando.set(false);
          this.renovacion.set(null);
          this.aviso.set(this.resumen('Membresía renovada', c));
          this.recargar();
        },
        error: (e: HttpErrorResponse) => {
          this.guardando.set(false);
          this.errorRenovacion.set(errorDeApi(e).mensaje);
        }
      });
  }

  protected pedirMotivo(membresia: Membresia, accion: Accion) {
    this.errorMotivo.set(null);
    this.motivo.set({ membresia, accion, texto: '' });
  }

  protected confirmarMotivo() {
    const mo = this.motivo();
    if (!mo) return;
    this.guardando.set(true);
    const peticion = {
      suspender: () => this.api.suspender(mo.membresia.id, mo.texto),
      reactivar: () => this.api.reactivar(mo.membresia.id, mo.texto),
      cancelar: () => this.api.cancelar(mo.membresia.id, mo.texto)
    }[mo.accion]();
    peticion.subscribe({
      next: () => {
        this.guardando.set(false);
        this.motivo.set(null);
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorMotivo.set(errorDeApi(e).mensaje);
      }
    });
  }

  private pago(f: FormularioContratacion): DatosPagoContratacion {
    return {
      method: f.metodo,
      status: f.estadoPago,
      ...(f.referencia.trim() ? { reference: f.referencia.trim() } : {})
    };
  }

  private resumen(titulo: string, c: Contratacion): string {
    const pago = c.payment ? ` · pago ${c.payment.status === 'completed' ? 'completado' : 'pendiente'}` : ' · sin pago';
    return `${titulo}: ${c.membership.memberName}, ${c.membership.planName}${pago}.`;
  }

  private recargar() {
    this.cargando.set(true);
    const vista = this.vista();
    const filtro =
      vista === 'todas'
        ? {}
        : vista === 'por-vencer'
          ? { expiringInDays: this.dias() }
          : { status: vista };
    this.api.listar(filtro).subscribe({
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
