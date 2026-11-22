import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, UrlTree } from '@angular/router';
import { SessionService } from './session.service';
import { staffGuard } from './staff.guard';

describe('staffGuard', () => {
  function evaluar(roles: string[]) {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.inject(SessionService).roles.set(roles);
    const res = TestBed.runInInjectionContext(() => staffGuard({} as ActivatedRouteSnapshot, {} as never));
    return res instanceof UrlTree ? TestBed.inject(Router).serializeUrl(res) : res;
  }

  it('el Staff pasa', () => {
    expect(evaluar(['staff'])).toBe(true);
  });
  it('un Staff que también es Entrenador pasa', () => {
    expect(evaluar(['trainer', 'staff'])).toBe(true);
  });
  it('Administrador, Entrenador, Cliente y Master son rechazados hacia /mi-perfil', () => {
    for (const rol of ['admin', 'trainer', 'client', 'master']) {
      expect(evaluar([rol])).toBe('/mi-perfil');
      TestBed.resetTestingModule();
    }
  });
});
