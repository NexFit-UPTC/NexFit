import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ClientesService,
  DatosCliente,
  DetalleCliente,
  EstadoMembresia,
  PaginaClientes,
  ResultadoCliente
} from '../core/clientes.service';
import { Eps, EpsService } from '../core/eps.service';
import { errorDeApi, TipoDocumento } from '../core/establecimientos.service';

const MEMBRESIA: Record<EstadoMembresia, string> = {
  active: 'Vigente',
  suspended: 'Suspendida',
  scheduled: 'Programada',
  expired: 'Vencida',
  cancelled: 'Cancelada'
};

interface Formulario {
  documentType: TipoDocumento;
  documentNumber: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  email: string;
  phone: string;
  epsId: number | null;
  contactoNombre: string;
  contactoParentesco: string;
  contactoTelefono: string;
  healthConsent: boolean;
  privacyAccepted: boolean;
}

function vacio(): Formulario {
  return {
    documentType: 'CC',
    documentNumber: '',
    firstName: '',
    lastName: '',
    birthDate: '',
    email: '',
    phone: '',
    epsId: null,
    contactoNombre: '',
    contactoParentesco: '',
    contactoTelefono: '',
    healthConsent: false,
    privacyAccepted: false
  };
}

const TAMANO_PAGINA = 20;

@Component({
  selector: 'app-clientes',
  imports: [FormsModule],
  styles: `
    :host {
      display: block;
      max-width: 1100px;
    }
    .cabecera {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--espacio-3);
      flex-wrap: wrap;
    }
    h1 {
      margin: 0;
    }
    .panel {
      margin-top: var(--espacio-4);
      padding: var(--espacio-4);
      background: var(--superficie);
      border: 1px solid var(--borde);
      border-radius: var(--radio);
    }
    .panel h2 {
      margin: 0 0 var(--espacio-3);
      font-size: 1.125rem;
    }
    .rejilla {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: var(--espacio-3);
    }
    .busqueda {
      display: flex;
      gap: var(--espacio-2);
      align-items: end;
      flex-wrap: wrap;
    }
    .busqueda .nf-campo:first-child {
      flex: 1 1 260px;
    }
    .acciones {
      display: flex;
      gap: var(--espacio-3);
      align-items: center;
      flex-wrap: wrap;
      margin-top: var(--espacio-3);
    }
    .verificacion {
      margin-top: var(--espacio-3);
      padding: var(--espacio-3);
      background: var(--fondo);
      border: 1px dashed var(--borde);
      border-radius: 10px;
      display: grid;
      gap: var(--espacio-2);
    }
    .casilla {
      display: flex;
      gap: var(--espacio-2);
      align-items: flex-start;
      font-size: 0.9rem;
    }
    .casilla input {
      margin-top: 4px;
    }
    fieldset {
      margin: var(--espacio-3) 0 0;
      padding: var(--espacio-3);
      border: 1px solid var(--borde);
      border-radius: 10px;
    }
    legend {
      padding: 0 var(--espacio-2);
      font-weight: 600;
      font-size: 0.875rem;
      color: var(--texto-suave);
    }
    .contrasena {
      display: inline-block;
      padding: var(--espacio-2) var(--espacio-3);
      font-size: 1.125rem;
      background: var(--fondo);
      border: 1px solid var(--borde);
      border-radius: 10px;
    }
    .tabla {
      width: 100%;
      border-collapse: collapse;
    }
    .tabla caption {
      position: absolute;
      left: -9999px;
    }
    .tabla th,
    .tabla td {
      padding: 12px 10px;
      text-align: left;
      border-bottom: 1px solid var(--borde);
      vertical-align: middle;
    }
    .tabla th {
      font-size: 0.8125rem;
      color: var(--texto-suave);
      font-weight: 600;
    }
    .etiqueta {
      display: inline-block;
      padding: 2px 10px;
      font-size: 0.8125rem;
      font-weight: 600;
      border-radius: 999px;
      border: 1px solid var(--borde);
      background: var(--fondo);
    }
    .etiqueta.ok {
      background: var(--acento);
      color: var(--sobre-acento);
      border-color: var(--acento);
    }
    .etiqueta.alerta {
      color: var(--error);
      border-color: var(--error);
      background: var(--error-fondo);
    }
    .paginacion {
      display: flex;
      gap: var(--espacio-3);
      align-items: center;
      justify-content: flex-end;
      margin-top: var(--espacio-3);
    }
    .boton-secundario {
      min-height: 40px;
      padding: 8px 16px;
      font: inherit;
      font-weight: 600;
      color: var(--texto);
      background: transparent;
      border: 1px solid var(--borde);
      border-radius: 10px;
      cursor: pointer;
    }
    .boton-secundario:hover:not(:disabled) {
      border-color: var(--texto-suave);
    }
    .boton-secundario:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    dl {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: var(--espacio-3);
      margin: 0;
    }
    dt {
      font-size: 0.8125rem;
      color: var(--texto-suave);
      font-weight: 600;
    }
    dd {
      margin: 2px 0 0;
    }
    ul.contactos {
      margin: var(--espacio-2) 0 0;
      padding-left: var(--espacio-4);
    }
  `,
  template: `
    <div class="cabecera">
      <h1>Clientes</h1>
      <button type="button" class="nf-boton" (click)="nuevo()">Registrar cliente</button>
    </div>

    @if (resultado(); as r) {
      <section class="panel" role="status" aria-label="Resultado del registro">
        @if (r.temporaryPassword) {
          <h2>Cliente registrado</h2>
          <p>Entrega esta contraseña temporal al cliente. Se muestra una sola vez:</p>
          <p>
            <code class="contrasena">{{ r.temporaryPassword }}</code>
          </p>
          <button type="button" class="nf-boton" (click)="cerrarResultado()">Ya la copié</button>
        } @else {
          <h2>Cliente afiliado</h2>
          <p>{{ r.firstName }} {{ r.lastName }} ya tenía cuenta y quedó afiliado a este gimnasio.</p>
          <button type="button" class="nf-boton" (click)="cerrarResultado()">Cerrar</button>
        }
      </section>
    }

    @if (formulario(); as f) {
      <section class="panel" aria-labelledby="titulo-registro">
        <h2 id="titulo-registro">Registrar cliente</h2>
        <form (ngSubmit)="guardar()" novalidate>
          <div class="rejilla">
            <div class="nf-campo">
              <label for="documentType">Tipo de documento</label>
              <select id="documentType" class="nf-entrada" name="documentType" [(ngModel)]="f.documentType">
                <option value="CC">Cédula de ciudadanía (CC)</option>
                <option value="CE">Cédula de extranjería (CE)</option>
                <option value="PPT">Permiso por protección temporal (PPT)</option>
                <option value="PA">Pasaporte (PA)</option>
              </select>
              @if (errores()['documentType']) {
                <p class="nf-error-campo" role="alert">{{ errores()['documentType'] }}</p>
              }
            </div>
            <div class="nf-campo">
              <label for="documentNumber">Número de documento</label>
              <input id="documentNumber" class="nf-entrada" name="documentNumber" [(ngModel)]="f.documentNumber" />
              @if (errores()['documentNumber']) {
                <p class="nf-error-campo" role="alert">{{ errores()['documentNumber'] }}</p>
              }
            </div>
          </div>

          <div class="verificacion">
            <strong>¿La persona ya existe?</strong>
            <p class="nf-ayuda">
              Si ya tiene cuenta, se afilia a este gimnasio con su correo actual. Confirma antes de buscar.
            </p>
            <label class="casilla">
              <input type="checkbox" name="documentoVerificado" [(ngModel)]="documentoVerificado" />
              Verifiqué el documento de identidad
            </label>
            <label class="casilla">
              <input type="checkbox" name="personaPresente" [(ngModel)]="personaPresente" />
              La persona está presente
            </label>
            <div class="acciones">
              <button
                type="button"
                class="boton-secundario"
                [disabled]="buscandoPersona() || !documentoVerificado || !personaPresente || !f.documentNumber.trim()"
                (click)="buscarPersona()"
              >
                Buscar por documento
              </button>
              @if (personaEncontrada(); as p) {
                <span role="status"
                  >Ya existe: {{ p.firstName }} {{ p.lastName }}. Se afiliará a este gimnasio (el correo debe
                  coincidir).</span
                >
              }
              @if (personaNueva()) {
                <span role="status">No existe: se creará una cuenta nueva.</span>
              }
              @if (errorBusqueda()) {
                <span class="nf-error-campo" role="alert">{{ errorBusqueda() }}</span>
              }
            </div>
          </div>

          <div class="rejilla" style="margin-top: var(--espacio-3)">
            <div class="nf-campo">
              <label for="firstName">Nombre</label>
              <input id="firstName" class="nf-entrada" name="firstName" [(ngModel)]="f.firstName" />
              @if (errores()['firstName']) {
                <p class="nf-error-campo" role="alert">{{ errores()['firstName'] }}</p>
              }
            </div>
            <div class="nf-campo">
              <label for="lastName">Apellido</label>
              <input id="lastName" class="nf-entrada" name="lastName" [(ngModel)]="f.lastName" />
              @if (errores()['lastName']) {
                <p class="nf-error-campo" role="alert">{{ errores()['lastName'] }}</p>
              }
            </div>
            <div class="nf-campo">
              <label for="birthDate">Fecha de nacimiento</label>
              <input id="birthDate" class="nf-entrada" name="birthDate" type="date" [(ngModel)]="f.birthDate" />
              @if (errores()['birthDate']) {
                <p class="nf-error-campo" role="alert">{{ errores()['birthDate'] }}</p>
              }
            </div>
            <div class="nf-campo">
              <label for="email">Correo</label>
              <input id="email" class="nf-entrada" name="email" type="email" [(ngModel)]="f.email" />
              @if (errores()['email']) {
                <p class="nf-error-campo" role="alert">{{ errores()['email'] }}</p>
              }
            </div>
            <div class="nf-campo">
              <label for="phone">Teléfono</label>
              <input id="phone" class="nf-entrada" name="phone" type="tel" [(ngModel)]="f.phone" />
              @if (errores()['phone']) {
                <p class="nf-error-campo" role="alert">{{ errores()['phone'] }}</p>
              }
            </div>
            <div class="nf-campo">
              <label for="epsId">EPS</label>
              <select id="epsId" class="nf-entrada" name="epsId" [(ngModel)]="f.epsId">
                <option [ngValue]="null">Selecciona una EPS</option>
                @for (e of eps(); track e.id) {
                  <option [ngValue]="e.id">{{ e.name }}</option>
                }
              </select>
              @if (errores()['epsId']) {
                <p class="nf-error-campo" role="alert">{{ errores()['epsId'] }}</p>
              }
            </div>
          </div>

          <fieldset>
            <legend>Contacto de emergencia (obligatorio)</legend>
            <div class="rejilla">
              <div class="nf-campo">
                <label for="contactoNombre">Nombre completo</label>
                <input id="contactoNombre" class="nf-entrada" name="contactoNombre" [(ngModel)]="f.contactoNombre" />
              </div>
              <div class="nf-campo">
                <label for="contactoParentesco">Parentesco</label>
                <input
                  id="contactoParentesco"
                  class="nf-entrada"
                  name="contactoParentesco"
                  [(ngModel)]="f.contactoParentesco"
                />
              </div>
              <div class="nf-campo">
                <label for="contactoTelefono">Teléfono</label>
                <input
                  id="contactoTelefono"
                  class="nf-entrada"
                  name="contactoTelefono"
                  type="tel"
                  [(ngModel)]="f.contactoTelefono"
                />
              </div>
            </div>
            @if (errores()['emergencyContact']) {
              <p class="nf-error-campo" role="alert">{{ errores()['emergencyContact'] }}</p>
            }
          </fieldset>

          <fieldset>
            <legend>Autorizaciones</legend>
            <label class="casilla">
              <input type="checkbox" name="healthConsent" [(ngModel)]="f.healthConsent" />
              El cliente autoriza expresamente el tratamiento de sus datos de salud (EPS y contactos de emergencia).
            </label>
            @if (errores()['healthConsent']) {
              <p class="nf-error-campo" role="alert">{{ errores()['healthConsent'] }}</p>
            }
            <label class="casilla">
              <input type="checkbox" name="privacyAccepted" [(ngModel)]="f.privacyAccepted" />
              El cliente aceptó la política de tratamiento de datos personales.
            </label>
            @if (errores()['privacyAccepted']) {
              <p class="nf-error-campo" role="alert">{{ errores()['privacyAccepted'] }}</p>
            }
          </fieldset>

          @if (errorGeneral()) {
            <p class="nf-alerta" role="alert" style="margin-top: var(--espacio-3)">{{ errorGeneral() }}</p>
          }
          <div class="acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Registrar</button>
            <button type="button" class="boton-secundario" (click)="cancelar()">Cancelar</button>
          </div>
        </form>
      </section>
    }

    @if (detalle(); as d) {
      <section class="panel" aria-labelledby="titulo-detalle">
        <div class="cabecera">
          <h2 id="titulo-detalle">{{ d.firstName }} {{ d.lastName }}</h2>
          <button type="button" class="boton-secundario" (click)="cerrarDetalle()">Cerrar</button>
        </div>
        <dl>
          <div>
            <dt>Documento</dt>
            <dd>{{ d.documentType }} {{ d.documentNumber }}</dd>
          </div>
          <div>
            <dt>Correo</dt>
            <dd>{{ d.email }}</dd>
          </div>
          <div>
            <dt>Teléfono</dt>
            <dd>{{ d.phone ?? '—' }}</dd>
          </div>
          <div>
            <dt>Fecha de nacimiento</dt>
            <dd>{{ fecha(d.birthDate) }}</dd>
          </div>
          <div>
            <dt>Afiliación</dt>
            <dd>{{ d.active ? 'Activa' : 'Inactiva' }} · desde {{ fecha(d.joinedAt) }}</dd>
          </div>
          <div>
            <dt>Membresía</dt>
            <dd>
              @if (d.membership; as m) {
                {{ etiquetaMembresia(m.status) }}{{ m.planName ? ' · ' + m.planName : '' }} ·
                {{ fecha(m.startDate) }} a {{ fecha(m.endDate) }}
              } @else {
                Sin membresía
              }
            </dd>
          </div>
          <div>
            <dt>EPS</dt>
            <dd>{{ d.epsName ?? '—' }}</dd>
          </div>
          <div>
            <dt>Contactos de emergencia</dt>
            <dd>
              <ul class="contactos">
                @for (c of d.emergencyContacts; track c.id) {
                  <li>{{ c.fullName }} ({{ c.relationship }}) · {{ c.phone }}{{ c.primary ? ' · principal' : '' }}</li>
                }
              </ul>
            </dd>
          </div>
        </dl>
        <p class="nf-ayuda" style="margin-top: var(--espacio-3)">
          Ver la EPS y los contactos de emergencia queda registrado en auditoría.
        </p>
      </section>
    }
    @if (errorDetalle()) {
      <p class="nf-alerta" role="alert" style="margin-top: var(--espacio-3)">{{ errorDetalle() }}</p>
    }

    <section class="panel" aria-label="Listado de clientes">
      <form class="busqueda" (ngSubmit)="buscar()" role="search">
        <div class="nf-campo">
          <label for="q">Buscar por nombre o documento</label>
          <input id="q" class="nf-entrada" name="q" type="search" [(ngModel)]="q" />
        </div>
        <div class="nf-campo">
          <label for="estado">Afiliación</label>
          <select id="estado" class="nf-entrada" name="estado" [(ngModel)]="estado">
            <option value="todos">Todas</option>
            <option value="activos">Activas</option>
            <option value="inactivos">Inactivas</option>
          </select>
        </div>
        <button type="submit" class="nf-boton">Buscar</button>
      </form>

      @if (error()) {
        <p class="nf-alerta" role="alert" style="margin-top: var(--espacio-3)">{{ error() }}</p>
      }
      @if (cargando()) {
        <p>Cargando…</p>
      } @else if (pagina(); as p) {
        @if (p.content.length === 0) {
          <p>No hay clientes que coincidan con la búsqueda.</p>
        } @else {
          <table class="tabla">
            <caption>
              Clientes del gimnasio
            </caption>
            <thead>
              <tr>
                <th scope="col">Nombre</th>
                <th scope="col">Documento</th>
                <th scope="col">Afiliación</th>
                <th scope="col">Membresía</th>
                <th scope="col"><span class="nf-ayuda">Acciones</span></th>
              </tr>
            </thead>
            <tbody>
              @for (c of p.content; track c.memberId) {
                <tr>
                  <td>{{ c.firstName }} {{ c.lastName }}</td>
                  <td>{{ c.documentType }} {{ c.documentNumber }}</td>
                  <td>
                    <span class="etiqueta" [class.ok]="c.active" [class.alerta]="!c.active">{{
                      c.active ? 'Activa' : 'Inactiva'
                    }}</span>
                  </td>
                  <td>
                    @if (c.membershipStatus; as s) {
                      <span class="etiqueta" [class.ok]="s === 'active'" [class.alerta]="s === 'expired'">{{
                        etiquetaMembresia(s)
                      }}</span>
                      @if (c.membershipEndDate) {
                        <span class="nf-ayuda"> hasta {{ fecha(c.membershipEndDate) }}</span>
                      }
                    } @else {
                      <span class="nf-ayuda">Sin membresía</span>
                    }
                  </td>
                  <td>
                    <button type="button" class="boton-secundario ver" (click)="ver(c.memberId)">Ver</button>
                  </td>
                </tr>
              }
            </tbody>
          </table>
          <div class="paginacion">
            <span class="nf-ayuda"
              >{{ p.totalElements }} clientes · página {{ p.page + 1 }} de {{ p.totalPages }}</span
            >
            <button type="button" class="boton-secundario anterior" [disabled]="p.page === 0" (click)="irA(p.page - 1)">
              Anterior
            </button>
            <button
              type="button"
              class="boton-secundario siguiente"
              [disabled]="p.page + 1 >= p.totalPages"
              (click)="irA(p.page + 1)"
            >
              Siguiente
            </button>
          </div>
        }
      }
    </section>
  `
})
export class ClientesComponent implements OnInit {
  private readonly api = inject(ClientesService);
  private readonly epsApi = inject(EpsService);

  protected q = '';
  protected estado: 'todos' | 'activos' | 'inactivos' = 'todos';
  protected documentoVerificado = false;
  protected personaPresente = false;

  protected readonly pagina = signal<PaginaClientes | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly detalle = signal<DetalleCliente | null>(null);
  protected readonly errorDetalle = signal<string | null>(null);

  protected readonly eps = signal<Eps[]>([]);
  protected readonly formulario = signal<Formulario | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);
  // La contraseña temporal vive solo en este signal: no se guarda en storage ni se escribe en logs.
  protected readonly resultado = signal<ResultadoCliente | null>(null);

  protected readonly buscandoPersona = signal(false);
  protected readonly personaEncontrada = signal<{ firstName: string; lastName: string } | null>(null);
  protected readonly personaNueva = signal(false);
  protected readonly errorBusqueda = signal<string | null>(null);

  private paginaActual = 0;
  // Filtros de la última búsqueda: la paginación no debe depender de lo que se esté escribiendo.
  private filtro: { q: string; active?: boolean } = { q: '' };

  ngOnInit() {
    this.cargar();
  }

  protected etiquetaMembresia(estado: EstadoMembresia): string {
    return MEMBRESIA[estado] ?? estado;
  }

  /** "2026-11-04" o "2026-11-04T12:00:00Z" → "04/11/2026" (sin pasar por la zona horaria del navegador). */
  protected fecha(valor: string): string {
    const [anio, mes, dia] = valor.slice(0, 10).split('-');
    return anio && mes && dia ? `${dia}/${mes}/${anio}` : valor;
  }

  protected buscar() {
    this.filtro = {
      q: this.q,
      active: this.estado === 'todos' ? undefined : this.estado === 'activos'
    };
    this.paginaActual = 0;
    this.cargar();
  }

  protected irA(pagina: number) {
    this.paginaActual = Math.max(pagina, 0);
    this.cargar();
  }

  protected ver(memberId: number) {
    this.errorDetalle.set(null);
    this.api.detalle(memberId).subscribe({
      next: (d) => this.detalle.set(d),
      error: (e: HttpErrorResponse) => {
        this.detalle.set(null);
        this.errorDetalle.set(errorDeApi(e).mensaje);
      }
    });
  }

  protected cerrarDetalle() {
    this.detalle.set(null);
  }

  protected nuevo() {
    this.errores.set({});
    this.errorGeneral.set(null);
    this.limpiarVerificacion();
    this.formulario.set(vacio());
    this.epsApi.listar().subscribe({
      next: (lista) => this.eps.set(lista),
      error: () => this.errorGeneral.set('No se pudo cargar el catálogo de EPS.')
    });
  }

  protected cancelar() {
    this.formulario.set(null);
  }

  protected cerrarResultado() {
    this.resultado.set(null);
  }

  protected buscarPersona() {
    const f = this.formulario();
    if (!f) return;
    this.limpiarVerificacion(false);
    this.buscandoPersona.set(true);
    this.api.buscarPersona(f.documentType, f.documentNumber.trim()).subscribe({
      next: (persona) => {
        this.buscandoPersona.set(false);
        this.personaEncontrada.set(persona);
      },
      error: (e: HttpErrorResponse) => {
        this.buscandoPersona.set(false);
        if (e.status === 404) this.personaNueva.set(true);
        else this.errorBusqueda.set(errorDeApi(e).mensaje);
      }
    });
  }

  protected guardar() {
    const f = this.formulario();
    if (!f) return;
    this.guardando.set(true);
    this.errores.set({});
    this.errorGeneral.set(null);
    const datos: DatosCliente = {
      documentType: f.documentType,
      documentNumber: f.documentNumber.trim(),
      firstName: f.firstName.trim(),
      lastName: f.lastName.trim(),
      birthDate: f.birthDate,
      email: f.email.trim(),
      phone: f.phone.trim(),
      epsId: f.epsId,
      emergencyContact: {
        fullName: f.contactoNombre.trim(),
        relationship: f.contactoParentesco.trim(),
        phone: f.contactoTelefono.trim()
      },
      healthConsent: f.healthConsent,
      privacyAccepted: f.privacyAccepted
    };
    this.api.crear(datos).subscribe({
      next: (r) => {
        this.guardando.set(false);
        this.formulario.set(null);
        this.resultado.set(r);
        this.cargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        const { campo, mensaje } = errorDeApi(e);
        if (campo) this.errores.set({ [campo]: mensaje });
        else this.errorGeneral.set(mensaje);
      }
    });
  }

  private limpiarVerificacion(reiniciarCasillas = true) {
    if (reiniciarCasillas) {
      this.documentoVerificado = false;
      this.personaPresente = false;
    }
    this.personaEncontrada.set(null);
    this.personaNueva.set(false);
    this.errorBusqueda.set(null);
  }

  private cargar() {
    this.cargando.set(true);
    this.api
      .listar({ q: this.filtro.q, active: this.filtro.active, page: this.paginaActual, size: TAMANO_PAGINA })
      .subscribe({
        next: (pagina) => {
          this.pagina.set(pagina);
          this.error.set(null);
          this.cargando.set(false);
        },
        error: (e: HttpErrorResponse) => {
          this.error.set(errorDeApi(e).mensaje);
          this.cargando.set(false);
        }
      });
  }
}
