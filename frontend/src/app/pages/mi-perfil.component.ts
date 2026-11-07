import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Eps, EpsService } from '../core/eps.service';
import { ETIQUETA_ESTADO, Membresia, MembresiasService } from '../core/membresias.service';
import { ContactoPerfil, PerfilCompleto, PerfilService } from '../core/perfil.service';
import { SessionService } from '../core/session.service';

interface FormularioPerfil {
  phone: string;
  epsId: number | null;
  healthConsent: boolean;
  contactos: ContactoPerfil[];
}

/**
 * Mi perfil (US-17, FE-06): la identidad es de solo lectura; el titular edita su teléfono y, si es cliente,
 * su EPS y sus contactos de emergencia. El Cliente ve además el estado de sus membresías.
 */
@Component({
  selector: 'app-mi-perfil',
  imports: [FormsModule, DatePipe, RouterLink],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Mi perfil</h1>
        @if (perfil() && !formulario()) {
          <button type="button" class="nf-boton" (click)="editar()">Editar mis datos</button>
        }
      </div>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (aviso()) {
        <p class="nf-ayuda" role="status">{{ aviso() }}</p>
      }

      @if (perfil(); as p) {
        <div class="nf-tarjeta">
          <h2>Identidad</h2>
          <dl class="nf-datos">
            <div><dt>Nombre</dt><dd>{{ p.identity.firstName }} {{ p.identity.lastName }}</dd></div>
            <div><dt>Documento</dt><dd>{{ p.identity.documentType }} {{ p.identity.documentNumber }}</dd></div>
            <div><dt>Nacimiento</dt><dd>{{ p.identity.birthDate | date: 'dd/MM/yyyy' }}</dd></div>
            <div><dt>Correo</dt><dd>{{ p.identity.email }}</dd></div>
          </dl>
          <p class="nf-ayuda">La identidad solo la corrige el personal autorizado.</p>
        </div>

        @if (p.affiliation; as a) {
          <div class="nf-tarjeta">
            <h2>{{ a.organizationName }}</h2>
            <dl class="nf-datos">
              <div><dt>Roles</dt><dd>{{ etiquetaRoles() }}</dd></div>
              <div><dt>Teléfono</dt><dd>{{ a.phone ?? '—' }}</dd></div>
              @if (esCliente()) {
                <div><dt>EPS</dt><dd>{{ a.epsName ?? '—' }}</dd></div>
              }
            </dl>
            @if (esCliente()) {
              <h3>Contactos de emergencia</h3>
              @for (c of a.emergencyContacts; track c.id) {
                <p>
                  {{ c.fullName }} · {{ c.relationship }} · {{ c.phone }}
                  @if (c.primary) {
                    <span class="nf-insignia ok">Principal</span>
                  }
                </p>
              }
            }
          </div>
        }

        @if (formulario(); as f) {
          <form class="nf-tarjeta" (ngSubmit)="guardar()" novalidate>
            <h2>Editar mis datos</h2>
            <div class="nf-rejilla">
              <label>
                Teléfono
                <input name="phone" [(ngModel)]="f.phone" />
              </label>
              @if (esCliente()) {
                <label>
                  EPS
                  <select name="epsId" [(ngModel)]="f.epsId">
                    @for (e of epsLista(); track e.id) {
                      <option [ngValue]="e.id">{{ e.name }}</option>
                    }
                  </select>
                </label>
              }
            </div>
            @if (esCliente()) {
              <label class="nf-ayuda">
                <input name="healthConsent" type="checkbox" [(ngModel)]="f.healthConsent" />
                Autorizo el tratamiento de mis datos de salud (necesario si cambias la EPS)
              </label>
              <h3>Contactos de emergencia</h3>
              @for (c of f.contactos; track $index) {
                <div class="nf-rejilla">
                  <label>Nombre<input [name]="'cn' + $index" [(ngModel)]="c.fullName" /></label>
                  <label>Parentesco<input [name]="'cr' + $index" [(ngModel)]="c.relationship" /></label>
                  <label>Teléfono<input [name]="'ct' + $index" [(ngModel)]="c.phone" /></label>
                  <label class="nf-ayuda">
                    <input [name]="'cp' + $index" type="checkbox" [(ngModel)]="c.primary" (ngModelChange)="marcarPrincipal($index)" />
                    Principal
                  </label>
                  @if (f.contactos.length > 1) {
                    <button type="button" class="nf-boton-sec" (click)="quitarContacto($index)">Quitar</button>
                  }
                </div>
              }
              <button type="button" class="nf-boton-sec" (click)="agregarContacto()">Agregar contacto</button>
            }
            @if (errorFormulario()) {
              <p class="nf-alerta" role="alert">{{ errorFormulario() }}</p>
            }
            <div class="nf-acciones">
              <button type="submit" class="nf-boton" [disabled]="guardando()">Guardar</button>
              <button type="button" class="nf-boton-sec" (click)="formulario.set(null)">Cancelar</button>
            </div>
          </form>
        }

        @if (esCliente()) {
          <div class="nf-tarjeta">
            <h2>Mi membresía</h2>
            @if (membresias().length === 0) {
              <p class="nf-vacio">Aún no tienes membresías. Pídele al personal del gimnasio que contrate un plan.</p>
            } @else {
              <table class="nf-tabla">
                <thead>
                  <tr><th>Plan</th><th>Inicio</th><th>Vence</th><th>Estado</th></tr>
                </thead>
                <tbody>
                  @for (m of membresias(); track m.id) {
                    <tr>
                      <td>{{ m.planName }}</td>
                      <td>{{ m.startDate | date: 'dd/MM/yyyy' }}</td>
                      <td>{{ m.endDate | date: 'dd/MM/yyyy' }}</td>
                      <td>
                        <span class="nf-insignia" [class.ok]="m.effectiveStatus === 'current'"
                              [class.alerta]="m.effectiveStatus === 'cancelled' || m.effectiveStatus === 'expired'">
                          {{ etiqueta(m) }}
                        </span>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
            <p class="nf-ayuda"><a routerLink="/pagos">Ver mis pagos</a></p>
          </div>
        }
      } @else if (!error()) {
        <p class="nf-ayuda">Cargando…</p>
      }
    </section>
  `
})
export class MiPerfilComponent implements OnInit {
  private readonly api = inject(PerfilService);
  private readonly epsApi = inject(EpsService);
  private readonly membresiasApi = inject(MembresiasService);
  private readonly sesion = inject(SessionService);

  protected readonly perfil = signal<PerfilCompleto | null>(null);
  protected readonly membresias = signal<Membresia[]>([]);
  protected readonly epsLista = signal<Eps[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly aviso = signal<string | null>(null);
  protected readonly formulario = signal<FormularioPerfil | null>(null);
  protected readonly errorFormulario = signal<string | null>(null);
  protected readonly guardando = signal(false);

  protected readonly esCliente = computed(() => this.sesion.roles().includes('client'));
  protected readonly etiquetaRoles = computed(() => {
    const nombres: Record<string, string> = {
      master: 'Master',
      admin: 'Administrador',
      staff: 'Staff',
      trainer: 'Entrenador',
      client: 'Cliente'
    };
    return this.sesion
      .roles()
      .map((r) => nombres[r] ?? r)
      .join(', ');
  });

  ngOnInit() {
    this.cargar();
  }

  protected etiqueta(m: Membresia): string {
    return ETIQUETA_ESTADO[m.effectiveStatus];
  }

  protected editar() {
    const a = this.perfil()?.affiliation;
    if (!a) return;
    this.errorFormulario.set(null);
    this.aviso.set(null);
    if (this.esCliente()) this.epsApi.listar().subscribe((l) => this.epsLista.set(l));
    this.formulario.set({
      phone: a.phone ?? '',
      epsId: a.epsId,
      healthConsent: false,
      contactos: a.emergencyContacts.map((c) => ({ ...c }))
    });
  }

  protected agregarContacto() {
    this.formulario.update((f) =>
      f ? { ...f, contactos: [...f.contactos, { fullName: '', relationship: '', phone: '', primary: false }] } : f
    );
  }

  protected quitarContacto(i: number) {
    this.formulario.update((f) => (f ? { ...f, contactos: f.contactos.filter((_, j) => j !== i) } : f));
  }

  /** Solo un contacto puede ser el principal: al marcar uno se desmarcan los demás. */
  protected marcarPrincipal(i: number) {
    const f = this.formulario();
    if (!f?.contactos[i]?.primary) return;
    f.contactos.forEach((c, j) => (c.primary = j === i));
  }

  protected guardar() {
    const f = this.formulario();
    if (!f) return;
    this.guardando.set(true);
    this.errorFormulario.set(null);
    const cambios = this.esCliente()
      ? {
          phone: f.phone,
          ...(f.epsId !== null ? { epsId: f.epsId, healthConsent: f.healthConsent } : {}),
          emergencyContacts: f.contactos.map(({ fullName, relationship, phone, primary }) => ({
            fullName,
            relationship,
            phone,
            primary
          }))
        }
      : { phone: f.phone };
    this.api.actualizar(cambios).subscribe({
      next: (p) => {
        this.guardando.set(false);
        this.perfil.set(p);
        this.formulario.set(null);
        this.aviso.set('Tus datos se actualizaron.');
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        this.errorFormulario.set((e.error as { message?: string } | null)?.message ?? 'No se pudieron guardar los cambios.');
      }
    });
  }

  private cargar() {
    this.api.obtener().subscribe({
      next: (p) => {
        this.perfil.set(p);
        if (this.esCliente() && p.affiliation) {
          this.membresiasApi.listar().subscribe((l) => this.membresias.set(l));
        }
      },
      error: () => this.error.set('No se pudo cargar tu perfil.')
    });
  }
}
