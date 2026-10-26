import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';

export type EstadoSesion =
  | 'cargando'
  | 'anonimo'
  | 'sin-establecimiento'
  | 'cambiar-contrasena'
  | 'activa'
  | 'error';

interface Perfil {
  identity: { firstName: string; lastName: string; email: string };
  affiliation: { organizationId: number; organizationName: string; roles: string[] } | null;
}

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);

  readonly estado = signal<EstadoSesion>('cargando');
  readonly usuario = signal<Perfil['identity'] | null>(null);
  readonly establecimiento = signal<{ organizationId: number; name: string } | null>(null);
  readonly roles = signal<string[]>([]);
  readonly esMaster = computed(() => this.roles().includes('master'));
  // Mensaje de error de la API para mostrar en el layout.
  readonly mensaje = signal<string | null>(null);

  /** Consulta /api/me y deriva el estado. Se vuelve a llamar tras login, selección de establecimiento o reintento. */
  cargar(): Promise<void> {
    this.estado.set('cargando');
    return new Promise((resolve) => {
      this.http.get<Perfil>('/api/me').subscribe({
        next: (perfil) => {
          this.usuario.set(perfil.identity);
          // affiliation nula solo ocurre para el Master (sin establecimiento, D-13).
          const afiliacion = perfil.affiliation;
          this.establecimiento.set(
            afiliacion ? { organizationId: afiliacion.organizationId, name: afiliacion.organizationName } : null
          );
          this.roles.set(afiliacion ? afiliacion.roles : ['master']);
          this.estado.set('activa');
          resolve();
        },
        error: (e: HttpErrorResponse) => {
          this.limpiar();
          this.estado.set(estadoDeError(e));
          resolve();
        }
      });
    });
  }

  /** Marca la sesión como terminada (401) sin llamar al servidor. */
  limpiar() {
    this.usuario.set(null);
    this.establecimiento.set(null);
    this.roles.set([]);
  }
}

function estadoDeError(e: HttpErrorResponse): EstadoSesion {
  if (e.status === 401) return 'anonimo';
  if (e.status === 403) {
    switch (e.error?.code) {
      case 'PASSWORD_CHANGE_REQUIRED':
        return 'cambiar-contrasena';
      case 'ESTABLISHMENT_REQUIRED':
      case 'ESTABLISHMENT_WITHOUT_ADMIN':
        return 'sin-establecimiento';
    }
  }
  return 'error';
}
