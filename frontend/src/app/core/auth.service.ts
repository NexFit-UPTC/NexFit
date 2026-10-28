import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { SessionService } from './session.service';

export interface Establecimiento {
  organizationId: number;
  name: string;
  roles: string[];
}

interface RespuestaLogin {
  userId: number;
  mustChangePassword: boolean;
  establishments: Establecimiento[];
}

/**
 * Operaciones de /api/auth (US-01, US-02, US-05) y la navegación que sigue a cada una.
 * Los errores HTTP se propagan a quien llama para que cada pantalla muestre su mensaje;
 * el interceptor de errores ya se encarga de los 401/403 de las demás rutas.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly sesion = inject(SessionService);

  /** POST /api/auth/login. La cookie XSRF la pide el csrfInterceptor si todavía no existe. */
  async iniciarSesion(email: string, password: string): Promise<void> {
    const r = await firstValueFrom(
      this.http.post<RespuestaLogin>('/api/auth/login', { email: email.trim(), password })
    );
    this.sesion.mensaje.set(null);
    if (r.mustChangePassword) {
      // Mientras la temporal siga pendiente, el servidor solo deja cambiarla o cerrar sesión.
      this.sesion.estado.set('cambiar-contrasena');
      await this.router.navigateByUrl('/cambiar-contrasena');
    } else if (r.establishments.length > 1) {
      // Con varios establecimientos el servidor no elige ninguno: lo elige la persona.
      this.sesion.estado.set('sin-establecimiento');
      await this.router.navigateByUrl('/establecimiento');
    } else {
      // Con uno solo el servidor ya lo dejó activo; el Master entra sin establecimiento (D-13).
      await this.entrar();
    }
  }

  /** POST /api/auth/password: contraseña temporal o voluntaria (mínimo 10 caracteres en el servidor). */
  async cambiarContrasena(actual: string, nueva: string): Promise<void> {
    await firstValueFrom(
      this.http.post<void>('/api/auth/password', { currentPassword: actual, newPassword: nueva })
    );
    // El servidor ya quitó la marca de cambio pendiente: /me decide el siguiente paso
    // (entrar, o elegir establecimiento si hay varios).
    await this.entrar();
  }

  /** GET /api/auth/establishments: dónde puede operar la persona, con sus roles. */
  listarEstablecimientos(): Promise<Establecimiento[]> {
    return firstValueFrom(this.http.get<Establecimiento[]>('/api/auth/establishments'));
  }

  /** POST /api/auth/establishment. Devuelve false si, aun elegido, el establecimiento no deja operar. */
  async elegirEstablecimiento(organizationId: number): Promise<boolean> {
    await firstValueFrom(this.http.post<void>('/api/auth/establishment', { organizationId }));
    await this.sesion.cargar();
    if (this.sesion.estado() !== 'activa') {
      return false;
    }
    await this.router.navigateByUrl('/');
    return true;
  }

  /** POST /api/auth/logout. Aunque falle, la sesión local se descarta y se vuelve al login. */
  async cerrarSesion(): Promise<void> {
    try {
      await firstValueFrom(this.http.post<void>('/api/auth/logout', {}));
    } catch {
      // 401: la sesión ya había terminado en el servidor.
    }
    this.sesion.limpiar();
    this.sesion.mensaje.set(null);
    this.sesion.estado.set('anonimo');
    await this.router.navigateByUrl('/login');
  }

  /** Recarga /me y deja que los guards lleven a la persona a donde corresponda. */
  private async entrar(): Promise<void> {
    await this.sesion.cargar();
    await this.router.navigateByUrl('/');
  }
}
