import { inject } from '@angular/core';
import { Routes } from '@angular/router';
import { adminGuard } from './core/admin.guard';
import { gestionGuard } from './core/personal-gestion.guard';
import { equiposGuard } from './core/equipos.guard';
import { panelGuard } from './core/panel.guard';
import { SessionService } from './core/session.service';
import { masterGuard } from './core/master.guard';
import { sessionGuard } from './core/session.guard';
import { staffGuard } from './core/staff.guard';
import { LayoutComponent } from './layout/layout.component';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./pages/login.component').then((m) => m.LoginComponent), canActivate: [sessionGuard], data: { public: true } },
  { path: 'establecimiento', loadComponent: () => import('./pages/establecimiento.component').then((m) => m.EstablecimientoComponent), canActivate: [sessionGuard], data: { sinEstablecimiento: true } },
  { path: 'cambiar-contrasena', loadComponent: () => import('./pages/cambiar-contrasena.component').then((m) => m.CambiarContrasenaComponent), canActivate: [sessionGuard], data: { sinEstablecimiento: true } },
  {
    path: '',
    component: LayoutComponent,
    canActivate: [sessionGuard],
    children: [
      { path: 'panel', loadComponent: () => import('./pages/panel.component').then((m) => m.PanelComponent), canActivate: [panelGuard] },
      { path: 'mi-perfil', loadComponent: () => import('./pages/mi-perfil.component').then((m) => m.MiPerfilComponent) },
      { path: 'apariencia', loadComponent: () => import('./pages/apariencia.component').then((m) => m.AparienciaComponent) },
      { path: 'establecimientos', loadComponent: () => import('./pages/establecimientos.component').then((m) => m.EstablecimientosComponent), canActivate: [masterGuard] },
      { path: 'administrador', loadComponent: () => import('./pages/administrador.component').then((m) => m.AdministradorComponent), canActivate: [masterGuard] },
      { path: 'auditoria', loadComponent: () => import('./pages/auditoria.component').then((m) => m.AuditoriaComponent) },
      { path: 'sedes', loadComponent: () => import('./pages/sedes.component').then((m) => m.SedesComponent), canActivate: [adminGuard] },
      { path: 'personal', loadComponent: () => import('./pages/personal.component').then((m) => m.PersonalComponent), canActivate: [adminGuard] },
      { path: 'planes', loadComponent: () => import('./pages/planes.component').then((m) => m.PlanesComponent), canActivate: [adminGuard] },
      { path: 'clientes', loadComponent: () => import('./pages/clientes.component').then((m) => m.ClientesComponent), canActivate: [staffGuard] },
      { path: 'membresias', loadComponent: () => import('./pages/membresias.component').then((m) => m.MembresiasComponent), canActivate: [gestionGuard] },
      { path: 'pagos', loadComponent: () => import('./pages/pagos.component').then((m) => m.PagosComponent) },
      { path: 'clases', loadComponent: () => import('./pages/clases.component').then((m) => m.ClasesComponent) },
      { path: 'equipos', loadComponent: () => import('./pages/equipos.component').then((m) => m.EquiposComponent), canActivate: [equiposGuard] },
      { path: 'ejercicios', loadComponent: () => import('./pages/ejercicios.component').then((m) => m.EjerciciosComponent) },
      { path: 'reservas', loadComponent: () => import('./pages/reservas.component').then((m) => m.ReservasComponent) },
      { path: 'asistencia', loadComponent: () => import('./pages/asistencia.component').then((m) => m.AsistenciaComponent) },
      {
        path: '',
        pathMatch: 'full',
        redirectTo: () => {
          const sesion = inject(SessionService);
          return sesion.esMaster() || sesion.roles().includes('admin') ? 'panel' : 'mi-perfil';
        }
      }
    ]
  },
  { path: '**', redirectTo: '' }
];