import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AsistenciaService, Ingreso } from '../core/asistencia.service';
import { ClientesService, ResumenCliente } from '../core/clientes.service';
import { errorDeApi } from '../core/establecimientos.service';
import { Sede, SedesService } from '../core/sedes.service';
import { SessionService } from '../core/session.service';

/**
 * Asistencia (US-36, US-37, US-38): el Cliente registra su ingreso y su salida; el Staff, los de cualquier cliente;
 * Administrador, Staff y Entrenador consultan el historial (el Entrenador, solo el de sus alumnos).
 */
@Component({
  selector: 'app-asistencia',
  imports: [FormsModule, DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>{{ esCliente() ? 'Mi asistencia' : 'Asistencia' }}</h1>
      </div>

      @if (puedeRegistrar()) {
        <form class="nf-tarjeta" (ngSubmit)="ingresar()" novalidate>
          <h2>{{ esCliente() ? 'Registrar mi ingreso' : 'Registrar el ingreso de un cliente' }}</h2>
          <div class="nf-rejilla">
            @if (!esCliente()) {
              <label>
                Buscar cliente (nombre o documento)
                <input name="busquedaCliente" [ngModel]="busqueda()" (ngModelChange)="buscarClientes($event)" />
              </label>
              <label>
                Cliente
                <select name="cliente" [(ngModel)]="clienteId">
                  <option [ngValue]="null">Selecciona…</option>
                  @for (c of clientes(); track c.memberId) {
                    <option [ngValue]="c.memberId">{{ c.firstName }} {{ c.lastName }} · {{ c.documentNumber }}</option>
                  }
                </select>
              </label>
            }
            <label>
              Sede
              <select name="sede" [(ngModel)]="sedeId">
                <option [ngValue]="null">Selecciona…</option>
                @for (s of sedesActivas(); track s.id) {
                  <option [ngValue]="s.id">{{ s.name }}</option>
                }
              </select>
            </label>
          </div>
          @if (errorRegistro()) {
            <p class="nf-error-campo" role="alert">{{ errorRegistro() }}</p>
          }
          @if (aviso()) {
            <p class="nf-ayuda" role="status">{{ aviso() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Registrar ingreso</button>
          </div>
        </form>
      }

      <div class="nf-filtros">
        <label>
          Desde
          <input type="date" name="desde" [ngModel]="desde()" (ngModelChange)="cambiarFiltro('desde', $event)" />
        </label>
        <label>
          Hasta
          <input type="date" name="hasta" [ngModel]="hasta()" (ngModelChange)="cambiarFiltro('hasta', $event)" />
        </label>
        <label class="nf-casilla">
          <input type="checkbox" name="soloDentro" [ngModel]="soloDentro()" (ngModelChange)="cambiarFiltro('dentro', $event)" />
          Solo quienes siguen dentro
        </label>
      </div>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (ingresos().length === 0) {
        <p class="nf-vacio">No hay ingresos registrados.</p>
      } @else {
        <table class="nf-tabla">
          <thead>
            <tr>
              @if (!esCliente()) {
                <th>Cliente</th>
              }
              <th>Sede</th>
              <th>Ingreso</th>
              <th>Salida</th>
              @if (!esCliente()) {
                <th>Registró</th>
              }
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (i of ingresos(); track i.id) {
              <tr>
                @if (!esCliente()) {
                  <td>{{ i.memberName }}</td>
                }
                <td>{{ i.branchName }}</td>
                <td>{{ i.checkInAt | date: 'EEE dd/MM HH:mm' }}</td>
                <td>
                  @if (i.checkOutAt) {
                    {{ i.checkOutAt | date: 'EEE dd/MM HH:mm' }}
                    @if (i.autoClosed) {
                      <span class="nf-insignia alerta" title="No registró salida; se cerró al final de ese día">Cierre automático</span>
                    }
                  } @else {
                    <span class="nf-insignia ok">Dentro</span>
                  }
                </td>
                @if (!esCliente()) {
                  <td>{{ i.registeredByName ?? 'El propio cliente' }}</td>
                }
                <td class="nf-acciones">
                  @if (puedeRegistrar() && !i.checkOutAt) {
                    <button type="button" class="nf-boton-sec" (click)="salir(i)">Registrar salida</button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `
})
export class AsistenciaComponent implements OnInit {
  private readonly api = inject(AsistenciaService);
  private readonly sedesApi = inject(SedesService);
  private readonly clientesApi = inject(ClientesService);
  private readonly sesion = inject(SessionService);

  protected readonly esCliente = computed(() => {
    const roles = this.sesion.roles();
    return roles.includes('client') && !roles.includes('staff') && !roles.includes('admin');
  });
  protected readonly puedeRegistrar = computed(() => {
    const roles = this.sesion.roles();
    return roles.includes('client') || roles.includes('staff');
  });

  protected readonly ingresos = signal<Ingreso[]>([]);
  protected readonly sedes = signal<Sede[]>([]);
  protected readonly sedesActivas = computed(() => this.sedes().filter((s) => s.active));
  protected readonly clientes = signal<ResumenCliente[]>([]);
  protected readonly busqueda = signal('');
  protected readonly desde = signal('');
  protected readonly hasta = signal('');
  protected readonly soloDentro = signal(false);
  protected readonly cargando = signal(true);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errorRegistro = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  protected sedeId: number | null = null;
  protected clienteId: number | null = null;

  ngOnInit() {
    if (this.puedeRegistrar()) {
      this.sedesApi.listar().subscribe((s) => {
        this.sedes.set(s);
        const activas = s.filter((x) => x.active);
        // Con una sola sede no hay nada que elegir.
        if (activas.length === 1) this.sedeId = activas[0].id;
      });
      if (!this.esCliente()) this.buscarClientes('');
    }
    this.recargar();
  }

  protected buscarClientes(q: string) {
    this.busqueda.set(q);
    this.clientesApi.listar({ q, active: true, size: 20 }).subscribe((p) => this.clientes.set(p.content));
  }

  protected cambiarFiltro(cual: 'desde' | 'hasta' | 'dentro', valor: string | boolean) {
    if (cual === 'desde') this.desde.set(valor as string);
    else if (cual === 'hasta') this.hasta.set(valor as string);
    else this.soloDentro.set(valor as boolean);
    this.recargar();
  }

  protected ingresar() {
    this.errorRegistro.set(null);
    this.aviso.set(null);
    if (this.sedeId === null) {
      this.errorRegistro.set('Selecciona la sede.');
      return;
    }
    if (!this.esCliente() && this.clienteId === null) {
      this.errorRegistro.set('Selecciona el cliente.');
      return;
    }
    this.guardando.set(true);
    this.api.ingresar(this.sedeId, this.esCliente() ? undefined : (this.clienteId as number)).subscribe({
      next: (i) => {
        this.guardando.set(false);
        this.aviso.set(`Ingreso registrado: ${i.memberName}, ${i.branchName}.`);
        this.clienteId = null;
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorRegistro.set(errorDeApi(e).mensaje);
      }
    });
  }

  protected salir(i: Ingreso) {
    this.error.set(null);
    this.aviso.set(null);
    this.api.salir(i.id).subscribe({
      next: () => this.recargar(),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  private recargar() {
    this.cargando.set(true);
    this.api.listar({ from: this.desde(), to: this.hasta(), open: this.soloDentro() }).subscribe({
      next: (datos) => {
        this.ingresos.set(datos);
        this.error.set(null);
        this.cargando.set(false);
      },
      error: () => {
        this.error.set('No se pudo cargar el historial.');
        this.cargando.set(false);
      }
    });
  }
}
