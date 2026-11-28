import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService, RegistroGooglePendiente } from '../core/auth.service';
import { EstadoSesion, SessionService } from '../core/session.service';
import { TIPOS_DOCUMENTO } from './registro.component';

const DESTINOS: Partial<Record<EstadoSesion, string>> = {
  activa: '/',
  'sin-establecimiento': '/establecimiento',
  'cambiar-contrasena': '/cambiar-contrasena'
};

type Campo = 'documentType' | 'documentNumber' | 'firstName' | 'lastName' | 'birthDate' | 'acceptPolicy';
const CAMPOS: Campo[] = ['documentType', 'documentNumber', 'firstName', 'lastName', 'birthDate', 'acceptPolicy'];

/** Dónde está la pantalla: leyendo el pendiente, con el formulario, sin pendiente (venció) o sin poder leerlo. */
type Fase = 'cargando' | 'formulario' | 'vencido' | 'error';

/**
 * Registro con Google (NEX-149): el servidor deja un pendiente en la sesión al volver de Google con un correo
 * verificado sin cuenta; aquí se completan los datos que Google no entrega y se acepta la política
 * (GET y POST /api/auth/google-registration). Nombre y apellido llegan precargados y son editables; el correo
 * es de solo lectura. Un documento repetido no consume el pendiente: se corrige y se reenvía sin volver a Google.
 */
@Component({
  selector: 'app-completar-registro',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './completar-registro.component.html',
  styleUrl: './completar-registro.component.css'
})
export class CompletarRegistroComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  protected readonly sesion = inject(SessionService);

  protected readonly tipos = TIPOS_DOCUMENTO;
  protected readonly form = inject(NonNullableFormBuilder).group({
    firstName: ['', [Validators.required]],
    lastName: ['', [Validators.required]],
    documentType: ['CC', [Validators.required]],
    documentNumber: ['', [Validators.required]],
    birthDate: ['', [Validators.required]],
    acepto: [false, [Validators.requiredTrue]]
  });
  protected readonly fase = signal<Fase>('cargando');
  /** Correo verificado por Google, nombre y apellido sugeridos, y la política vigente. */
  protected readonly google = signal<RegistroGooglePendiente | null>(null);
  protected readonly enviando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly erroresServidor = signal<Partial<Record<Campo, string>>>({});
  /** 409 EMAIL_TAKEN: la cuenta de ese correo se creó mientras tanto; se ofrece entrar con Google. */
  protected readonly correoRepetido = signal<string | null>(null);

  constructor() {
    // Quien ya tiene sesión no necesita esta pantalla; reacciona también cuando /api/me responde después de pintar.
    effect(() => {
      const destino = DESTINOS[this.sesion.estado()];
      // Mientras se envía, AuthService ya navega: evita la doble navegación.
      if (destino && !untracked(() => this.enviando())) {
        this.router.navigateByUrl(destino);
      }
    });
    void this.cargar();
  }

  protected invalido(nombre: keyof CompletarRegistroComponent['form']['controls']): boolean {
    const c = this.form.controls[nombre];
    return c.touched && c.invalid;
  }

  /** Lee el pendiente de la sesión: 404 = no hay o venció; cualquier otro fallo permite reintentar. */
  protected async cargar(): Promise<void> {
    this.fase.set('cargando');
    try {
      const pendiente = await this.auth.leerRegistroGoogle();
      this.google.set(pendiente);
      this.form.patchValue({ firstName: pendiente.firstName, lastName: pendiente.lastName });
      this.fase.set('formulario');
    } catch (e) {
      this.fase.set(e instanceof HttpErrorResponse && e.status === 404 ? 'vencido' : 'error');
    }
  }

  protected async crear(): Promise<void> {
    if (this.enviando()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.enviando.set(true);
    this.error.set(null);
    this.erroresServidor.set({});
    this.correoRepetido.set(null);
    const v = this.form.getRawValue();
    try {
      // El correo no viaja: el servidor usa el que guardó. AuthService.registrarConGoogle ya sigue al destino.
      await this.auth.registrarConGoogle({
        documentType: v.documentType,
        documentNumber: v.documentNumber.trim(),
        firstName: v.firstName.trim(),
        lastName: v.lastName.trim(),
        birthDate: v.birthDate,
        acceptPolicy: v.acepto
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
    const campoServidor: unknown = http?.error?.field;
    const campo = CAMPOS.find((c) => c === campoServidor);
    if (http?.status === 400) {
      if (campo && mensaje) {
        this.erroresServidor.set({ [campo]: mensaje });
        return;
      }
      this.error.set(mensaje ?? 'Revisa los datos e inténtalo de nuevo.');
    } else if (http?.status === 404) {
      // El pendiente venció mientras se llenaba el formulario.
      this.fase.set('vencido');
    } else if (http?.status === 409) {
      if (campoServidor === 'email' && mensaje) {
        this.correoRepetido.set(mensaje);
      } else if (campo && mensaje) {
        this.erroresServidor.set({ [campo]: mensaje });
      } else {
        this.error.set(mensaje ?? 'Ya existe una cuenta con esos datos.');
      }
    } else if (http?.status === 403) {
      this.error.set('No se pudo validar la solicitud. Recarga la página e inténtalo de nuevo.');
    } else {
      this.error.set('No se pudo conectar con el servidor. Inténtalo de nuevo en un momento.');
    }
  }
}
