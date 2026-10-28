import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { AuthService, Establecimiento } from '../core/auth.service';
import { AuthShellComponent } from './auth-shell.component';

const ROLES: Record<string, string> = {
  admin: 'Administrador',
  staff: 'Staff',
  trainer: 'Entrenador',
  client: 'Cliente'
};

export function etiquetaDeRol(rol: string): string {
  return ROLES[rol] ?? rol;
}

/** Elección del establecimiento activo (US-05): se llega con 403 ESTABLISHMENT_REQUIRED o tras un login con varios. */
@Component({
  selector: 'app-establecimiento',
  imports: [AuthShellComponent],
  templateUrl: './establecimiento.component.html',
  styleUrl: './establecimiento.component.css'
})
export class EstablecimientoComponent implements OnInit {
  private readonly auth = inject(AuthService);

  protected readonly lista = signal<Establecimiento[] | null>(null);
  protected readonly cargando = signal(true);
  protected readonly eligiendo = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly etiqueta = etiquetaDeRol;

  async ngOnInit(): Promise<void> {
    await this.cargar();
  }

  protected async cargar(): Promise<void> {
    this.cargando.set(true);
    this.error.set(null);
    try {
      this.lista.set(await this.auth.listarEstablecimientos());
    } catch {
      // El 401 ya lo atendió el interceptor (vuelve al login); aquí quedan las fallas de red o del servidor.
      this.error.set('No se pudieron cargar tus establecimientos. Inténtalo de nuevo.');
    } finally {
      this.cargando.set(false);
    }
  }

  protected async elegir(e: Establecimiento): Promise<void> {
    if (this.eligiendo() !== null) return;
    this.eligiendo.set(e.organizationId);
    this.error.set(null);
    try {
      const entro = await this.auth.elegirEstablecimiento(e.organizationId);
      if (!entro) {
        this.error.set(
          `No se pudo entrar a «${e.name}». Puede que todavía no tenga un Administrador asignado; prueba con otro o avisa al Master.`
        );
      }
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 400
          ? 'Ese establecimiento ya no está disponible para ti.'
          : 'No se pudo elegir el establecimiento. Inténtalo de nuevo.'
      );
    } finally {
      this.eligiendo.set(null);
    }
  }

  protected cerrarSesion(): void {
    this.auth.cerrarSesion();
  }
}
