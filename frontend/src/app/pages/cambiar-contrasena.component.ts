import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators
} from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { SessionService } from '../core/session.service';
import { AuthShellComponent } from './auth-shell.component';

/** Mismo mínimo que valida el servidor (POST /api/auth/password). */
export const MIN_CONTRASENA = 10;

function coinciden(grupo: AbstractControl): ValidationErrors | null {
  const nueva = grupo.get('nueva')?.value;
  const confirmar = grupo.get('confirmar')?.value;
  return nueva && confirmar && nueva !== confirmar ? { noCoinciden: true } : null;
}

/** Los 400 del servidor traen un mensaje claro en español (actual incorrecta, muy corta, igual a la actual). */
export function mensajeDeCambio(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400 && typeof e.error?.message === 'string') {
    return e.error.message;
  }
  return 'No se pudo cambiar la contraseña. Inténtalo de nuevo en un momento.';
}

/** Cambio de contraseña: obligatorio con una temporal (US-02) y voluntario desde el menú. */
@Component({
  selector: 'app-cambiar-contrasena',
  imports: [ReactiveFormsModule, RouterLink, AuthShellComponent],
  templateUrl: './cambiar-contrasena.component.html',
  styleUrl: './cambiar-contrasena.component.css'
})
export class CambiarContrasenaComponent {
  private readonly auth = inject(AuthService);
  private readonly sesion = inject(SessionService);

  protected readonly minimo = MIN_CONTRASENA;
  protected readonly obligatorio = computed(() => this.sesion.estado() === 'cambiar-contrasena');
  protected readonly form = inject(NonNullableFormBuilder).group(
    {
      actual: ['', [Validators.required]],
      nueva: ['', [Validators.required, Validators.minLength(MIN_CONTRASENA)]],
      confirmar: ['', [Validators.required]]
    },
    { validators: coinciden }
  );
  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly verClave = signal(false);

  protected async guardar(): Promise<void> {
    if (this.enviando()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    const { actual, nueva } = this.form.getRawValue();
    try {
      await this.auth.cambiarContrasena(actual, nueva);
    } catch (e) {
      this.error.set(mensajeDeCambio(e));
    } finally {
      this.enviando.set(false);
    }
  }

  protected cerrarSesion(): void {
    this.auth.cerrarSesion();
  }
}
