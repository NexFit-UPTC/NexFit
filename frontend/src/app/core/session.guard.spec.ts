import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, UrlTree } from '@angular/router';
import { firstValueFrom, Observable } from 'rxjs';
import { EstadoSesion, SessionService } from './session.service';
import { sessionGuard } from './session.guard';

describe('sessionGuard', () => {
  function ejecutar(data: Record<string, boolean>, path: string) {
    const route = { data, routeConfig: { path } } as unknown as ActivatedRouteSnapshot;
    const r = TestBed.runInInjectionContext(() => sessionGuard(route, {} as never));
    return firstValueFrom(r as Observable<boolean | UrlTree>);
  }

  async function evaluar(estado: EstadoSesion, data: Record<string, boolean> = {}, path = 'x') {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.inject(SessionService).estado.set(estado);
    const res = await ejecutar(data, path);
    return res instanceof UrlTree ? TestBed.inject(Router).serializeUrl(res) : res;
  }

  it('ruta pública pasa sin sesión', async () => {
    expect(await evaluar('anonimo', { public: true })).toBe(true);
  });
  it('sin sesión → /login', async () => {
    expect(await evaluar('anonimo')).toBe('/login');
  });
  it('sin establecimiento → /establecimiento', async () => {
    expect(await evaluar('sin-establecimiento')).toBe('/establecimiento');
  });
  it('/establecimiento no exige establecimiento', async () => {
    expect(await evaluar('sin-establecimiento', { sinEstablecimiento: true })).toBe(true);
  });
  it('contraseña temporal → /cambiar-contrasena', async () => {
    expect(await evaluar('cambiar-contrasena')).toBe('/cambiar-contrasena');
  });
  it('sesión activa pasa', async () => {
    expect(await evaluar('activa')).toBe(true);
  });
  it('espera mientras carga y luego decide', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const sesion = TestBed.inject(SessionService);
    const pendiente = ejecutar({}, 'x');
    sesion.estado.set('activa');
    TestBed.tick();
    expect(await pendiente).toBe(true);
  });
});
