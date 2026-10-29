import { Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { SessionService } from '../core/session.service';

interface Seccion {
  ruta: string;
  titulo: string;
  roles: string[] | 'todos';
}

const SECCIONES: Seccion[] = [
  { ruta: '/establecimientos', titulo: 'Establecimientos', roles: ['master'] },
  { ruta: '/administrador', titulo: 'Administrador', roles: ['master'] },
  { ruta: '/auditoria', titulo: 'Auditoría', roles: ['master', 'admin'] },
  { ruta: '/sedes', titulo: 'Sedes', roles: ['admin'] },
  { ruta: '/personal', titulo: 'Personal', roles: ['admin'] },
  { ruta: '/planes', titulo: 'Planes', roles: ['admin'] },
  { ruta: '/mi-perfil', titulo: 'Mi perfil', roles: 'todos' },
  { ruta: '/apariencia', titulo: 'Apariencia', roles: 'todos' },
  { ruta: '/cambiar-contrasena', titulo: 'Cambiar contraseña', roles: 'todos' }
];

const ETIQUETAS_ROL: Record<string, string> = {
  master: 'Master',
  admin: 'Administrador',
  staff: 'Staff',
  trainer: 'Entrenador',
  client: 'Cliente'
};

@Component({
  selector: 'app-layout',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  templateUrl: './layout.component.html',
  styleUrl: './layout.component.css'
})
export class LayoutComponent {
  private readonly auth = inject(AuthService);
  protected readonly sesion = inject(SessionService);
  protected readonly secciones = computed(() => {
    const roles = this.sesion.roles();
    return SECCIONES.filter((s) => s.roles === 'todos' || s.roles.some((r) => roles.includes(r)));
  });

  protected readonly etiquetaRol = computed(() => {
    const rol = this.sesion.roles()[0];
    return rol ? (ETIQUETAS_ROL[rol] ?? rol) : '';
  });
  protected readonly iniciales = computed(() => {
    const u = this.sesion.usuario();
    return u ? `${u.firstName.charAt(0)}${u.lastName.charAt(0)}`.toUpperCase() : '';
  });

  protected cerrarSesion(): void {
    this.auth.cerrarSesion();
  }
}
