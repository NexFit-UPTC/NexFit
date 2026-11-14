import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService, Establecimiento } from '../core/auth.service';
import { SessionService } from '../core/session.service';

interface Seccion {
  ruta: string;
  titulo: string;
  roles: string[] | 'todos';
}

const SECCIONES: Seccion[] = [
  { ruta: '/panel', titulo: 'Panel', roles: ['master', 'admin'] },
  { ruta: '/establecimientos', titulo: 'Establecimientos', roles: ['master'] },
  { ruta: '/eps', titulo: 'Catálogo de EPS', roles: ['master'] },
  { ruta: '/administrador', titulo: 'Administrador', roles: ['master'] },
  { ruta: '/auditoria', titulo: 'Auditoría', roles: ['master', 'admin'] },
  { ruta: '/sedes', titulo: 'Sedes', roles: ['admin'] },
  { ruta: '/personal', titulo: 'Personal', roles: ['admin'] },
  { ruta: '/planes', titulo: 'Planes', roles: ['admin'] },
  { ruta: '/clientes', titulo: 'Clientes', roles: ['staff'] },
  { ruta: '/membresias', titulo: 'Membresías', roles: ['staff', 'admin'] },
  { ruta: '/pagos', titulo: 'Pagos', roles: ['staff', 'admin', 'client'] },
  { ruta: '/clases', titulo: 'Clases', roles: ['admin', 'staff', 'trainer', 'client'] },
  { ruta: '/reservas', titulo: 'Reservas', roles: ['admin', 'staff', 'trainer', 'client'] },
  { ruta: '/equipos', titulo: 'Equipo', roles: ['admin', 'staff'] },
  { ruta: '/alumnos', titulo: 'Alumnos', roles: ['admin', 'staff'] },
  { ruta: '/mis-alumnos', titulo: 'Mis alumnos', roles: ['trainer'] },
  { ruta: '/inicio', titulo: 'Inicio', roles: ['client'] },
  { ruta: '/progreso', titulo: 'Mi progreso', roles: ['client'] },
  { ruta: '/peso', titulo: 'Mi peso', roles: ['client'] },
  { ruta: '/rutinas', titulo: 'Rutinas', roles: ['trainer', 'client'] },
  { ruta: '/ejercicios', titulo: 'Ejercicios', roles: ['master', 'admin', 'staff', 'trainer', 'client'] },
  { ruta: '/asistencia', titulo: 'Asistencia', roles: ['admin', 'staff', 'trainer', 'client'] },
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
  imports: [RouterLink, RouterLinkActive, RouterOutlet, FormsModule],
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

  // Lista de establecimientos propios (GIN-13): solo tiene sentido mostrar el selector con más de uno.
  protected readonly establecimientos = signal<Establecimiento[]>([]);
  protected readonly cambiando = signal(false);

  constructor() {
    effect(() => {
      const activo = this.sesion.establecimiento();
      if (activo && this.sesion.estado() === 'activa') {
        this.auth.listarEstablecimientos().then(
          (lista) => this.establecimientos.set(lista.length > 1 ? lista : []),
          () => this.establecimientos.set([])
        );
      } else {
        this.establecimientos.set([]);
      }
    });
  }

  protected async cambiarEstablecimiento(organizationId: number): Promise<void> {
    const actual = this.sesion.establecimiento()?.organizationId;
    if (organizationId === actual || this.cambiando()) return;
    this.cambiando.set(true);
    try {
      const entro = await this.auth.elegirEstablecimiento(organizationId);
      if (!entro) {
        this.sesion.mensaje.set('No se pudo cambiar de establecimiento. Inténtalo de nuevo.');
      }
    } catch {
      this.sesion.mensaje.set('No se pudo cambiar de establecimiento. Inténtalo de nuevo.');
    } finally {
      this.cambiando.set(false);
    }
  }

  protected cerrarSesion(): void {
    this.auth.cerrarSesion();
  }
}
