import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { errorDeApi, TipoDocumento } from '../core/establecimientos.service';
import { Pagina } from '../core/pagina';
import { PaginacionComponent } from '../layout/paginacion.component';
import {
  CambiosPersonal,
  DatosPersonal,
  Personal,
  PersonalService,
  ResultadoPersonal
} from '../core/personal.service';

const ETIQUETAS: Record<string, string> = {
  admin: 'Administrador',
  staff: 'Staff',
  trainer: 'Entrenador',
  client: 'Cliente'
};

interface Formulario {
  documentType: TipoDocumento;
  documentNumber: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  email: string;
  staff: boolean;
  trainer: boolean;
  specialty: string;
  certification: string;
  hireDate: string;
}

function vacio(): Formulario {
  return {
    documentType: 'CC',
    documentNumber: '',
    firstName: '',
    lastName: '',
    birthDate: '',
    email: '',
    staff: true,
    trainer: false,
    specialty: '',
    certification: '',
    hireDate: ''
  };
}

@Component({
  selector: 'app-personal',
  imports: [FormsModule, PaginacionComponent],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Personal</h1>
        <button type="button" class="nf-boton" (click)="nuevo()">Agregar personal</button>
      </div>

    @if (resultado(); as r) {
      @if (r.temporaryPassword) {
        <section class="nf-tarjeta nf-aviso" role="status">
          <p>Cuenta creada. Contraseña temporal (se muestra una sola vez):</p>
          <p>
            <code>{{ r.temporaryPassword }}</code>
          </p>
          <button type="button" class="nf-boton-sec" (click)="cerrarResultado()">Ya la copié</button>
        </section>
      } @else {
        <section class="nf-tarjeta nf-aviso" role="status">
          <p>{{ r.firstName }} {{ r.lastName }} ya existía y quedó afiliado al gimnasio.</p>
          <button type="button" class="nf-boton-sec" (click)="cerrarResultado()">Cerrar</button>
        </section>
      }
    }

    @if (formulario(); as f) {
      <form class="nf-tarjeta nf-formulario" (ngSubmit)="guardar()" novalidate>
        <h2>{{ editandoId() === null ? 'Nuevo miembro del personal' : 'Editar roles' }}</h2>
        @if (editandoId() === null) {
          <label class="nf-campo">
            Tipo de documento
            <select class="nf-entrada" name="documentType" [(ngModel)]="f.documentType">
              <option value="CC">CC</option>
              <option value="CE">CE</option>
              <option value="PPT">PPT</option>
              <option value="PA">PA</option>
            </select>
          </label>
          @if (errores()['documentType']) {
            <p class="nf-error-campo" role="alert">{{ errores()['documentType'] }}</p>
          }
          <label class="nf-campo">
            Número de documento
            <input class="nf-entrada" name="documentNumber" [(ngModel)]="f.documentNumber" />
          </label>
          @if (errores()['documentNumber']) {
            <p class="nf-error-campo" role="alert">{{ errores()['documentNumber'] }}</p>
          }
          <label class="nf-campo">
            Nombre
            <input class="nf-entrada" name="firstName" [(ngModel)]="f.firstName" />
          </label>
          @if (errores()['firstName']) {
            <p class="nf-error-campo" role="alert">{{ errores()['firstName'] }}</p>
          }
          <label class="nf-campo">
            Apellido
            <input class="nf-entrada" name="lastName" [(ngModel)]="f.lastName" />
          </label>
          @if (errores()['lastName']) {
            <p class="nf-error-campo" role="alert">{{ errores()['lastName'] }}</p>
          }
          <label class="nf-campo">
            Fecha de nacimiento
            <input class="nf-entrada" name="birthDate" type="date" [(ngModel)]="f.birthDate" />
          </label>
          @if (errores()['birthDate']) {
            <p class="nf-error-campo" role="alert">{{ errores()['birthDate'] }}</p>
          }
          <label class="nf-campo">
            Correo
            <input class="nf-entrada" name="email" type="email" [(ngModel)]="f.email" />
          </label>
          @if (errores()['email']) {
            <p class="nf-error-campo" role="alert">{{ errores()['email'] }}</p>
          }
        }
        <fieldset class="nf-conjunto">
          <legend>Roles</legend>
          <label class="nf-casilla">
            <input name="staff" type="checkbox" [(ngModel)]="f.staff" />
            Staff
          </label>
          <label class="nf-casilla">
            <input name="trainer" type="checkbox" [(ngModel)]="f.trainer" />
            Entrenador
          </label>
        </fieldset>
        @if (errores()['roles']) {
          <p class="nf-error-campo" role="alert">{{ errores()['roles'] }}</p>
        }
        @if (f.trainer) {
          <label class="nf-campo">
            Especialidad
            <input class="nf-entrada" name="specialty" [(ngModel)]="f.specialty" />
          </label>
          @if (errores()['specialty']) {
            <p class="nf-error-campo" role="alert">{{ errores()['specialty'] }}</p>
          }
          <label class="nf-campo">
            Certificación
            <input class="nf-entrada" name="certification" [(ngModel)]="f.certification" />
          </label>
          @if (errores()['certification']) {
            <p class="nf-error-campo" role="alert">{{ errores()['certification'] }}</p>
          }
          <label class="nf-campo">
            Fecha de contratación
            <input class="nf-entrada" name="hireDate" type="date" [(ngModel)]="f.hireDate" />
          </label>
          @if (errores()['hireDate']) {
            <p class="nf-error-campo" role="alert">{{ errores()['hireDate'] }}</p>
          }
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
      <p class="nf-vacio">No hay personal registrado.</p>
    } @else {
      <div class="nf-tabla-envoltorio">
      <table class="nf-tabla">
        <thead>
          <tr>
            <th>Nombre</th>
            <th>Documento</th>
            <th>Correo</th>
            <th>Roles</th>
            <th>Estado</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          @for (p of lista(); track p.memberId) {
            <tr>
              <td>{{ p.firstName }} {{ p.lastName }}</td>
              <td>{{ p.documentType }} {{ p.documentNumber }}</td>
              <td>{{ p.email }}</td>
              <td>{{ etiquetas(p.roles) }}</td>
              <td>
                <span class="nf-insignia" [class.ok]="p.active">{{ p.active ? 'Activo' : 'Inactivo' }}</span>
              </td>
              <td class="nf-acciones-celda">
                <button type="button" class="editar nf-boton-sec" (click)="editar(p)">Editar roles</button>
                <button type="button" class="alternar nf-boton-sec" (click)="alternar(p)">
                  {{ p.active ? 'Desactivar' : 'Activar' }}
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
      </div>
      @if (pagina(); as p) {
        <app-paginacion
          [pagina]="p.page"
          [paginas]="p.totalPages"
          [total]="p.totalElements"
          etiqueta="personas"
          (cambiar)="irA($event)"
        />
      }
    }
    </section>
  `
})
export class PersonalComponent implements OnInit {
  private readonly api = inject(PersonalService);

  protected readonly pagina = signal<Pagina<Personal> | null>(null);
  protected readonly lista = computed(() => this.pagina()?.content ?? []);
  private paginaActual = 0;
  protected readonly cargando = signal(true);
  protected readonly error = signal<string | null>(null);

  protected readonly formulario = signal<Formulario | null>(null);
  protected readonly editandoId = signal<number | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly guardando = signal(false);
  // La contraseña temporal vive solo en este signal: no se guarda en storage ni se escribe en logs.
  protected readonly resultado = signal<ResultadoPersonal | null>(null);

  ngOnInit() {
    this.recargar();
  }

  protected etiquetas(roles: string[]): string {
    return roles.map((r) => ETIQUETAS[r] ?? r).join(', ');
  }

  protected nuevo() {
    this.abrir(null, vacio());
  }

  protected editar(p: Personal) {
    this.abrir(p.memberId, {
      ...vacio(),
      staff: p.roles.includes('staff'),
      trainer: p.roles.includes('trainer'),
      specialty: p.trainerProfile?.specialty ?? '',
      certification: p.trainerProfile?.certification ?? '',
      hireDate: p.trainerProfile?.hireDate ?? ''
    });
  }

  protected cancelar() {
    this.formulario.set(null);
  }

  protected cerrarResultado() {
    this.resultado.set(null);
  }

  protected guardar() {
    const f = this.formulario();
    if (!f) return;
    const id = this.editandoId();
    this.guardando.set(true);
    this.errores.set({});
    this.errorGeneral.set(null);
    const roles = [...(f.staff ? ['staff' as const] : []), ...(f.trainer ? ['trainer' as const] : [])];
    const entrenador: CambiosPersonal = { roles };
    if (f.trainer) {
      if (f.specialty.trim()) entrenador.specialty = f.specialty.trim();
      if (f.certification.trim()) entrenador.certification = f.certification.trim();
      if (f.hireDate) entrenador.hireDate = f.hireDate;
    }
    if (id === null) {
      const datos: DatosPersonal = {
        documentType: f.documentType,
        documentNumber: f.documentNumber.trim(),
        firstName: f.firstName.trim(),
        lastName: f.lastName.trim(),
        birthDate: f.birthDate,
        email: f.email.trim(),
        ...entrenador
      };
      this.api.crear(datos).subscribe({
        next: (r) => {
          this.guardando.set(false);
          this.formulario.set(null);
          this.resultado.set(r);
          this.recargar();
        },
        error: (e: HttpErrorResponse) => this.fallo(e)
      });
    } else {
      this.api.editar(id, entrenador).subscribe({
        next: () => {
          this.guardando.set(false);
          this.formulario.set(null);
          this.recargar();
        },
        error: (e: HttpErrorResponse) => this.fallo(e)
      });
    }
  }

  protected alternar(p: Personal) {
    this.error.set(null);
    const peticion = p.active ? this.api.desactivar(p.memberId) : this.api.activar(p.memberId);
    peticion.subscribe({
      next: () => this.recargar(),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  private fallo(e: HttpErrorResponse) {
    this.guardando.set(false);
    const { campo, mensaje } = errorDeApi(e);
    if (campo) this.errores.set({ [campo]: mensaje });
    else this.errorGeneral.set(mensaje);
  }

  private abrir(id: number | null, datos: Formulario) {
    this.editandoId.set(id);
    this.errores.set({});
    this.errorGeneral.set(null);
    this.formulario.set(datos);
  }

  protected irA(pagina: number) {
    this.paginaActual = Math.max(pagina, 0);
    this.recargar();
  }

  private recargar() {
    this.cargando.set(true);
    this.api.listar(this.paginaActual).subscribe({
      next: (datos) => {
        // Si la página actual quedó vacía (p. ej. desapareció el último de la página), se retrocede una.
        if (datos.content.length === 0 && datos.page > 0 && datos.totalPages > 0) {
          this.paginaActual = datos.totalPages - 1;
          this.recargar();
          return;
        }
        this.pagina.set(datos);
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
