import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ClientesService, ResumenCliente } from '../core/clientes.service';
import { Clase, ClasesService } from '../core/clases.service';
import { errorDeApi } from '../core/establecimientos.service';
import { Personal, PersonalService } from '../core/personal.service';
import { ReservasService } from '../core/reservas.service';
import { Sede, SedesService } from '../core/sedes.service';
import { SessionService } from '../core/session.service';

interface FormularioClase {
  name: string;
  description: string;
  branchId: number | null;
  trainerMemberId: number | null;
  /** Valor de <input type="datetime-local"> (hora local del navegador). */
  startsAt: string;
  durationMinutes: number | string | null;
  capacity: number | string | null;
}

const TAMANO = 12;

/** Instante ISO (UTC) de un valor «AAAA-MM-DDTHH:mm» o «AAAA-MM-DD» en la hora local del navegador. */
function aInstante(local: string, finDeDia = false): string {
  const fecha = local.length === 10 ? new Date(`${local}T${finDeDia ? '23:59:59' : '00:00:00'}`) : new Date(local);
  return fecha.toISOString();
}

/**
 * Clases por sede (US-29, US-30, US-31, US-32; FE-07). El Administrador las programa, edita y cancela; el Cliente
 * reserva para sí; el Staff reserva a nombre de un cliente; el Entrenador las consulta.
 */
@Component({
  selector: 'app-clases',
  imports: [FormsModule, DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Clases</h1>
        @if (esAdmin()) {
          <button type="button" class="nf-boton" (click)="nueva()">Programar clase</button>
        }
      </div>

      @if (formulario(); as f) {
        <form class="nf-tarjeta" (ngSubmit)="guardar()" novalidate>
          <h2>{{ editandoId() === null ? 'Programar clase' : 'Editar clase' }}</h2>
          <div class="nf-rejilla">
            <label>
              Nombre
              <input name="name" [(ngModel)]="f.name" />
            </label>
            <label>
              Sede
              <select name="branchId" [(ngModel)]="f.branchId">
                <option [ngValue]="null">Selecciona…</option>
                @for (s of sedesActivas(); track s.id) {
                  <option [ngValue]="s.id">{{ s.name }}</option>
                }
              </select>
            </label>
            <label>
              Entrenador
              <select name="trainerMemberId" [(ngModel)]="f.trainerMemberId">
                <option [ngValue]="null">Sin asignar</option>
                @for (t of entrenadores(); track t.memberId) {
                  <option [ngValue]="t.memberId">{{ t.firstName }} {{ t.lastName }}</option>
                }
              </select>
            </label>
            <label>
              Inicio
              <input name="startsAt" type="datetime-local" [(ngModel)]="f.startsAt" />
            </label>
            <label>
              Duración (min)
              <input name="durationMinutes" type="number" min="1" [(ngModel)]="f.durationMinutes" />
            </label>
            <label>
              Cupos
              <input name="capacity" type="number" min="1" [(ngModel)]="f.capacity" />
            </label>
          </div>
          <label class="nf-rejilla">
            Descripción
            <textarea name="description" rows="2" [(ngModel)]="f.description"></textarea>
          </label>
          @for (campo of camposConError(); track campo) {
            <p class="nf-error-campo" role="alert">{{ errores()[campo] }}</p>
          }
          @if (errorGeneral()) {
            <p class="nf-alerta" role="alert">{{ errorGeneral() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Guardar</button>
            <button type="button" class="nf-boton-sec" (click)="formulario.set(null)">Cancelar</button>
          </div>
        </form>
      }

      <div class="nf-filtros">
        <label>
          Sede
          <select name="filtroSede" [ngModel]="sedeFiltro()" (ngModelChange)="filtrar({ sede: $event })">
            <option [ngValue]="null">Todas</option>
            @for (s of sedes(); track s.id) {
              <option [ngValue]="s.id">{{ s.name }}</option>
            }
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
        @if (esAdmin()) {
          <label>
            Estado
            <select name="filtroEstado" [ngModel]="estado()" (ngModelChange)="filtrar({ estado: $event })">
              <option value="">Programadas</option>
              <option value="cancelled">Canceladas</option>
            </select>
          </label>
        }
      </div>

      @if (aviso()) {
        <p class="nf-ayuda" role="status">{{ aviso() }}</p>
      }
      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }

      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (clases().length === 0) {
        <p class="nf-vacio">No hay clases para este filtro.</p>
      } @else {
        <table class="nf-tabla">
          <thead>
            <tr>
              <th>Clase</th>
              <th>Sede</th>
              <th>Entrenador</th>
              <th>Cuándo</th>
              <th>Cupos</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            @for (c of clases(); track c.id) {
              <tr>
                <td>
                  <strong>{{ c.name }}</strong>
                  @if (c.status === 'cancelled') {
                    <span class="nf-insignia alerta">Cancelada</span>
                  }
                </td>
                <td>{{ c.branchName }}</td>
                <td>{{ c.trainerName ?? '—' }}</td>
                <td>{{ c.startsAt | date: 'EEE dd/MM HH:mm' }} · {{ c.durationMinutes }} min</td>
                <td>{{ c.confirmedBookings }}/{{ c.capacity }}</td>
                <td class="nf-acciones">
                  @if (c.status === 'scheduled') {
                    @if (esCliente()) {
                      <button type="button" class="nf-boton-sec" [disabled]="c.freeSlots === 0" (click)="reservarPropia(c)">
                        {{ c.freeSlots === 0 ? 'Sin cupos' : 'Reservar' }}
                      </button>
                    }
                    @if (esStaff()) {
                      <button type="button" class="nf-boton-sec" (click)="abrirReservaStaff(c)">Reservar a un cliente</button>
                    }
                    @if (esAdmin()) {
                      <button type="button" class="nf-boton-sec" (click)="editar(c)">Editar</button>
                      <button type="button" class="nf-boton-sec" (click)="pedirCancelacion(c)">Cancelar clase</button>
                    }
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

      @if (reservaStaff(); as r) {
        <form class="nf-tarjeta" (ngSubmit)="reservarParaCliente()" novalidate>
          <h2>Reservar «{{ r.clase.name }}»</h2>
          <div class="nf-rejilla">
            <label>
              Buscar cliente (nombre o documento)
              <input name="busquedaCliente" [(ngModel)]="r.busqueda" (input)="buscarClientes(r.busqueda)" />
            </label>
            <label>
              Cliente
              <select name="clienteReserva" [(ngModel)]="r.memberId">
                <option [ngValue]="null">Selecciona…</option>
                @for (c of clientes(); track c.memberId) {
                  <option [ngValue]="c.memberId">{{ c.firstName }} {{ c.lastName }} · {{ c.documentNumber }}</option>
                }
              </select>
            </label>
          </div>
          @if (errorReserva()) {
            <p class="nf-error-campo" role="alert">{{ errorReserva() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Confirmar reserva</button>
            <button type="button" class="nf-boton-sec" (click)="reservaStaff.set(null)">Volver</button>
          </div>
        </form>
      }

      @if (cancelando(); as c) {
        <form class="nf-tarjeta" (ngSubmit)="cancelarClase()" novalidate>
          <h2>Cancelar «{{ c.clase.name }}»</h2>
          <p class="nf-ayuda">
            Se cancelan también las {{ c.clase.confirmedBookings }} reservas confirmadas. Avisa a los clientes: el sistema no envía notificaciones.
          </p>
          <label class="nf-rejilla">
            Motivo (opcional)
            <textarea name="motivoClase" rows="2" [(ngModel)]="c.motivo"></textarea>
          </label>
          @if (errorCancelacion()) {
            <p class="nf-error-campo" role="alert">{{ errorCancelacion() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Confirmar cancelación</button>
            <button type="button" class="nf-boton-sec" (click)="cancelando.set(null)">Volver</button>
          </div>
        </form>
      }
    </section>
  `
})
export class ClasesComponent implements OnInit {
  private readonly api = inject(ClasesService);
  private readonly reservasApi = inject(ReservasService);
  private readonly sedesApi = inject(SedesService);
  private readonly personalApi = inject(PersonalService);
  private readonly clientesApi = inject(ClientesService);
  private readonly sesion = inject(SessionService);

  protected readonly esAdmin = computed(() => this.sesion.roles().includes('admin'));
  protected readonly esStaff = computed(() => this.sesion.roles().includes('staff'));
  protected readonly esCliente = computed(() => this.sesion.roles().includes('client'));

  protected readonly clases = signal<Clase[]>([]);
  protected readonly sedes = signal<Sede[]>([]);
  protected readonly sedesActivas = computed(() => this.sedes().filter((s) => s.active));
  protected readonly entrenadores = signal<Personal[]>([]);
  protected readonly clientes = signal<ResumenCliente[]>([]);
  protected readonly total = signal(0);
  protected readonly pagina = signal(0);
  protected readonly paginas = computed(() => Math.ceil(this.total() / TAMANO));
  protected readonly sedeFiltro = signal<number | null>(null);
  protected readonly desde = signal(new Date().toLocaleDateString('en-CA'));
  protected readonly hasta = signal('');
  protected readonly estado = signal<'' | 'cancelled'>('');
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);

  protected readonly formulario = signal<FormularioClase | null>(null);
  protected readonly editandoId = signal<number | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly camposConError = computed(() => Object.keys(this.errores()));
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);

  protected readonly reservaStaff = signal<{ clase: Clase; busqueda: string; memberId: number | null } | null>(null);
  protected readonly errorReserva = signal<string | null>(null);
  protected readonly cancelando = signal<{ clase: Clase; motivo: string } | null>(null);
  protected readonly errorCancelacion = signal<string | null>(null);

  ngOnInit() {
    this.sedesApi.listar().subscribe((s) => this.sedes.set(s));
    this.recargar();
  }

  protected filtrar(cambio: { sede?: number | null; desde?: string; hasta?: string; estado?: '' | 'cancelled' }) {
    if (cambio.sede !== undefined) this.sedeFiltro.set(cambio.sede);
    if (cambio.desde !== undefined) this.desde.set(cambio.desde);
    if (cambio.hasta !== undefined) this.hasta.set(cambio.hasta);
    if (cambio.estado !== undefined) this.estado.set(cambio.estado);
    this.pagina.set(0);
    this.recargar();
  }

  protected irAPagina(pagina: number) {
    this.pagina.set(pagina);
    this.recargar();
  }

  // ---- Administrador: programar, editar y cancelar -------------------------------------------

  protected nueva() {
    this.abrir(null, { name: '', description: '', branchId: null, trainerMemberId: null, startsAt: '', durationMinutes: 60, capacity: 20 });
  }

  protected editar(c: Clase) {
    const inicio = new Date(c.startsAt);
    const local = new Date(inicio.getTime() - inicio.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    this.abrir(c.id, {
      name: c.name,
      description: c.description ?? '',
      branchId: c.branchId,
      trainerMemberId: c.trainerMemberId,
      startsAt: local,
      durationMinutes: c.durationMinutes,
      capacity: c.capacity
    });
  }

  private abrir(id: number | null, datos: FormularioClase) {
    this.editandoId.set(id);
    this.errores.set({});
    this.errorGeneral.set(null);
    this.formulario.set(datos);
    this.personalApi.entrenadoresActivos().subscribe((p) => this.entrenadores.set(p));
  }

  protected guardar() {
    const f = this.formulario();
    if (!f) return;
    if (f.branchId === null || !f.startsAt) {
      this.errores.set({
        ...(f.branchId === null ? { branchId: 'Selecciona una sede' } : {}),
        ...(!f.startsAt ? { startsAt: 'La fecha y hora de inicio son obligatorias' } : {})
      });
      return;
    }
    const datos = {
      name: f.name,
      description: f.description,
      branchId: f.branchId,
      trainerMemberId: f.trainerMemberId,
      startsAt: aInstante(f.startsAt),
      durationMinutes: f.durationMinutes as number,
      capacity: f.capacity as number
    };
    const id = this.editandoId();
    this.guardando.set(true);
    this.errores.set({});
    this.errorGeneral.set(null);
    (id === null ? this.api.crear(datos) : this.api.editar(id, datos)).subscribe({
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

  protected pedirCancelacion(clase: Clase) {
    this.errorCancelacion.set(null);
    this.cancelando.set({ clase, motivo: '' });
  }

  protected cancelarClase() {
    const c = this.cancelando();
    if (!c) return;
    this.guardando.set(true);
    this.api.cancelar(c.clase.id, c.motivo).subscribe({
      next: () => {
        this.guardando.set(false);
        this.cancelando.set(null);
        this.aviso.set(`Clase «${c.clase.name}» cancelada.`);
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorCancelacion.set(errorDeApi(e).mensaje);
      }
    });
  }

  // ---- Reservas ----------------------------------------------------------------------------

  protected reservarPropia(c: Clase) {
    this.error.set(null);
    this.aviso.set(null);
    this.reservasApi.reservar(c.id).subscribe({
      next: () => {
        this.aviso.set(`Reservaste «${c.name}».`);
        this.recargar();
      },
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  protected abrirReservaStaff(clase: Clase) {
    this.errorReserva.set(null);
    this.aviso.set(null);
    this.reservaStaff.set({ clase, busqueda: '', memberId: null });
    this.buscarClientes('');
  }

  protected buscarClientes(q: string) {
    this.clientesApi.listar({ q, active: true, size: 20 }).subscribe((p) => this.clientes.set(p.content));
  }

  protected reservarParaCliente() {
    const r = this.reservaStaff();
    if (!r) return;
    if (r.memberId === null) {
      this.errorReserva.set('Selecciona un cliente');
      return;
    }
    this.guardando.set(true);
    this.reservasApi.reservar(r.clase.id, r.memberId).subscribe({
      next: (reserva) => {
        this.guardando.set(false);
        this.reservaStaff.set(null);
        this.aviso.set(`Reserva confirmada para ${reserva.memberName} en «${r.clase.name}».`);
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorReserva.set(errorDeApi(e).mensaje);
      }
    });
  }

  private recargar() {
    this.cargando.set(true);
    this.api
      .listar({
        ...(this.sedeFiltro() !== null ? { branchId: this.sedeFiltro() as number } : {}),
        ...(this.desde() ? { from: aInstante(this.desde()) } : {}),
        ...(this.hasta() ? { to: aInstante(this.hasta(), true) } : {}),
        ...(this.estado() ? { status: this.estado() as 'cancelled' } : {}),
        page: this.pagina(),
        size: TAMANO
      })
      .subscribe({
        next: (r) => {
          this.clases.set(r.content);
          this.total.set(r.totalElements);
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
