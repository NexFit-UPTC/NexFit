import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, UrlTree } from '@angular/router';
import { routes } from '../app.routes';
import { EstadoSesion, SessionService } from './session.service';
import { destinoPorRol } from './destino-por-rol';
import { inicioGuard } from './inicio.guard';
import { sessionGuard } from './session.guard';

describe('inicioGuard y destinoPorRol', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));

  function evaluar(estado: EstadoSesion, roles: string[] = []) {
    const sesion = TestBed.inject(SessionService);
    sesion.estado.set(estado);
    sesion.roles.set(roles);
    const r = TestBed.runInInjectionContext(() => inicioGuard({} as ActivatedRouteSnapshot, {} as never));
    return r instanceof UrlTree ? TestBed.inject(Router).serializeUrl(r) : r;
  }

  it('CA1/CA7: sin sesión o cargando deja ver la página pública', () => {
    expect(evaluar('anonimo')).toBe(true);
    expect(evaluar('cargando')).toBe(true);
  });
  it('CA6: con sesión activa va a la pantalla del rol', () => {
    expect(evaluar('activa', ['master'])).toBe('/panel');
  });
  it('CA6: sin establecimiento o con contraseña temporal va a su pantalla', () => {
    expect(evaluar('sin-establecimiento')).toBe('/establecimiento');
    expect(evaluar('cambiar-contrasena')).toBe('/cambiar-contrasena');
  });

  it('CA6: destino por rol', () => {
    const sesion = TestBed.inject(SessionService);
    const destino = (roles: string[]) => (sesion.roles.set(roles), destinoPorRol(sesion));
    expect(destino(['master'])).toBe('panel');
    expect(destino(['admin'])).toBe('panel');
    expect(destino(['client'])).toBe('inicio');
    expect(destino(['client', 'trainer'])).toBe('mi-perfil');
    expect(destino(['trainer'])).toBe('mi-perfil');
    expect(destino(['staff'])).toBe('mi-perfil');
  });

  it('CA1/CA5: «/» y el detalle son públicos; el layout (/panel) exige sesión', () => {
    const publica = routes.find((r) => r.path === '' && r.pathMatch === 'full')!;
    const detalle = routes.find((r) => r.path === 'gimnasios/:id')!;
    const layout = routes.find((r) => r.path === '' && r.children)!;
    expect(publica.data?.['public']).toBe(true);
    expect(detalle.data?.['public']).toBe(true);
    expect(layout.canActivate).toEqual([sessionGuard]);
    expect(layout.children!.some((r) => r.path === 'panel')).toBe(true);
    expect(layout.data?.['public']).toBeUndefined();
  });
});
