import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { AbstractControl, NonNullableFormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { leerGimnasio } from '../core/gimnasio-preseleccionado';
import { EstadoSesion, SessionService } from '../core/session.service';
import { AuthShellComponent } from './auth-shell.component';
import { MIN_CONTRASENA } from './cambiar-contrasena.component';

const DESTINOS: Partial<Record<EstadoSesion, string>> = {
  activa: '/',
  'sin-establecimiento': '/establecimiento',
  'cambiar-contrasena': '/cambiar-contrasena'
};

export const TIPOS_DOCUMENTO = [
  { valor: 'CC', texto: 'Cédula de ciudadanía' },
  { valor: 'CE', texto: 'Cédula de extranjería' },
  { valor: 'PPT', texto: 'Permiso por protección temporal' },
  { valor: 'PA', texto: 'Pasaporte' }
];

function coinciden(grupo: AbstractControl): ValidationErrors | null {
  const clave = grupo.get('password')?.value;
  const confirmar = grupo.get('confirmar')?.value;
  return clave && confirmar && clave !== confirmar ? { noCoinciden: true } : null;
}

type Campo = 'documentType' | 'documentNumber' | 'firstName' | 'lastName' | 'birthDate' | 'email' | 'password';
const CAMPOS: Campo[] = ['documentType', 'documentNumber', 'firstName', 'lastName', 'birthDate', 'email', 'password'];

/** Registro público de una cuenta de Cliente (POST /api/auth/register). */
@Component({
  selector: 'app-registro',
  imports: [ReactiveFormsModule, RouterLink, AuthShellComponent],
  templateUrl: './registro.component.html',
  styleUrl: './registro.component.css'
})
export class RegistroComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly sesion = inject(SessionService);

  protected readonly minimo = MIN_CONTRASENA;
  protected readonly tipos = TIPOS_DOCUMENTO;
  protected readonly form = inject(NonNullableFormBuilder).group(
    {
      firstName: ['', [Validators.required]],
      lastName: ['', [Validators.required]],
      documentType: ['CC', [Validators.required]],
      documentNumber: ['', [Validators.required]],
      birthDate: ['', [Validators.required]],
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(MIN_CONTRASENA)]],
      confirmar: ['', [Validators.required]]
    },
    { validators: coinciden }
  );
  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly erroresServidor = signal<Partial<Record<Campo, string>>>({});
  protected readonly verClave = signal(false);

  constructor() {
    // Quien ya tiene sesión no necesita esta pantalla; reacciona también cuando /api/me responde después de pintar.
    effect(() => {
      const destino = DESTINOS[this.sesion.estado()];
      if (destino && !untracked(() => this.enviando())) {
        this.router.navigateByUrl(destino);
      }
    });
  }

  protected invalido(nombre: keyof RegistroComponent['form']['controls']): boolean {
    const c = this.form.controls[nombre];
    return c.touched && c.invalid;
  }

  protected async registrar(): Promise<void> {
    if (this.enviando()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    this.erroresServidor.set({});
    const v = this.form.getRawValue();
    try {
      await this.auth.registrar({
        documentType: v.documentType,
        documentNumber: v.documentNumber.trim(),
        firstName: v.firstName.trim(),
        lastName: v.lastName.trim(),
        birthDate: v.birthDate,
        email: v.email.trim(),
        password: v.password
      });
      const gimnasio = leerGimnasio();
      await this.router.navigate(['/login'], {
        queryParams: gimnasio === null ? { registrado: 1 } : { registrado: 1, gimnasio }
      });
    } catch (e) {
      this.mostrarError(e);
    } finally {
      this.enviando.set(false);
    }
  }

  private mostrarError(e: unknown): void {
    const http = e instanceof HttpErrorResponse ? e : null;
    const mensaje = typeof http?.error?.message === 'string' ? http.error.message : null;
    if (http?.status === 400) {
      const campo = http.error?.field as Campo | undefined;
      if (campo && CAMPOS.includes(campo) && mensaje) {
        this.erroresServidor.set({ [campo]: mensaje });
        return;
      }
      this.error.set(mensaje ?? 'Revisa los datos e inténtalo de nuevo.');
    } else if (http?.status === 409) {
      this.error.set(mensaje ?? 'Ya existe una cuenta con esos datos.');
    } else if (http?.status === 403) {
      this.error.set('No se pudo validar la solicitud. Recarga la página e inténtalo de nuevo.');
    } else {
      this.error.set('No se pudo conectar con el servidor. Inténtalo de nuevo en un momento.');
    }
  }
}
