import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, UrlTree } from '@angular/router';
import { panelGuard } from './panel.guard';
import { SessionService } from './session.service';

describe('panelGuard', () => {
  function evaluar(roles: string[]) {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.inject(SessionService).roles.set(roles);
    const res = TestBed.runInInjectionContext(() => panelGuard({} as ActivatedRouteSnapshot, {} as never));
    return res instanceof UrlTree ? TestBed.inject(Router).serializeUrl(res) : res;
  }

  it('Administrador y Master pasan', () => {
    for (const rol of ['admin', 'master']) {
      expect(evaluar([rol])).toBe(true);
      TestBed.resetTestingModule();
    }
  });
  it('Staff, Entrenador y Cliente van a /mi-perfil', () => {
    for (const rol of ['staff', 'trainer', 'client']) {
      expect(evaluar([rol])).toBe('/mi-perfil');
      TestBed.resetTestingModule();
    }
  });
});
