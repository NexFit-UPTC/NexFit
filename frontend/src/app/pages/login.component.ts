import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { EstadoSesion, SessionService } from '../core/session.service';
import { AuthShellComponent } from './auth-shell.component';

const DESTINOS: Partial<Record<EstadoSesion, string>> = {
  activa: '/',
  'sin-establecimiento': '/establecimiento',
  'cambiar-contrasena': '/cambiar-contrasena'
};

/** Mensaje de un login fallido. El 401 es genérico a propósito: no revela si el correo existe ni si la cuenta está bloqueada. */
export function mensajeDeLogin(e: unknown): string {
  const status = e instanceof HttpErrorResponse ? e.status : 0;
  if (status === 401) return 'Correo o contraseña incorrectos. Revisa los datos e inténtalo de nuevo.';
  if (status === 403) return 'No se pudo validar la solicitud. Recarga la página e inténtalo de nuevo.';
  return 'No se pudo conectar con el servidor. Inténtalo de nuevo en un momento.';
}

@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule, AuthShellComponent],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css'
})
export class LoginComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly sesion = inject(SessionService);

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]]
  });
  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly verClave = signal(false);

  ngOnInit(): void {
    // Quien ya tiene sesión no necesita esta pantalla.
    const destino = DESTINOS[this.sesion.estado()];
    if (destino) {
      this.router.navigateByUrl(destino);
    }
  }

  protected async ingresar(): Promise<void> {
    if (this.enviando()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    const { email, password } = this.form.getRawValue();
    try {
      await this.auth.iniciarSesion(email, password);
    } catch (e) {
      this.error.set(mensajeDeLogin(e));
      this.form.controls.password.reset();
    } finally {
      this.enviando.set(false);
    }
  }
}
