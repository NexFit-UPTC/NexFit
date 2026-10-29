import { Routes } from '@angular/router';
import { adminGuard } from './core/admin.guard';
import { masterGuard } from './core/master.guard';
import { sessionGuard } from './core/session.guard';
import { LayoutComponent } from './layout/layout.component';
import { AdministradorComponent } from './pages/administrador.component';
import { AuditoriaComponent } from './pages/auditoria.component';
import { CambiarContrasenaComponent } from './pages/cambiar-contrasena.component';
import { EstablecimientoComponent } from './pages/establecimiento.component';
import { EstablecimientosComponent } from './pages/establecimientos.component';
import { LoginComponent } from './pages/login.component';
import { MiPerfilComponent } from './pages/mi-perfil.component';
import { PlanesComponent } from './pages/planes.component';
import { SedesComponent } from './pages/sedes.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, canActivate: [sessionGuard], data: { public: true } },
  { path: 'establecimiento', component: EstablecimientoComponent, canActivate: [sessionGuard], data: { sinEstablecimiento: true } },
  { path: 'cambiar-contrasena', component: CambiarContrasenaComponent, canActivate: [sessionGuard], data: { sinEstablecimiento: true } },
  {
    path: '',
    component: LayoutComponent,
    canActivate: [sessionGuard],
    children: [
      { path: 'mi-perfil', component: MiPerfilComponent },
      { path: 'establecimientos', component: EstablecimientosComponent, canActivate: [masterGuard] },
      { path: 'administrador', component: AdministradorComponent, canActivate: [masterGuard] },
      { path: 'auditoria', component: AuditoriaComponent },
      { path: 'sedes', component: SedesComponent, canActivate: [adminGuard] },
      { path: 'planes', component: PlanesComponent, canActivate: [adminGuard] },
      { path: '', pathMatch: 'full', redirectTo: 'mi-perfil' }
    ]
  },
  { path: '**', redirectTo: '' }
];