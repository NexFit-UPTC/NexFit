import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, UrlTree } from '@angular/router';
import { gestionGuard } from './personal-gestion.guard';
import { SessionService } from './session.service';

describe('gestionGuard', () => {
  function evaluar(roles: string[]) {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.inject(SessionService).roles.set(roles);
    const res = TestBed.runInInjectionContext(() => gestionGuard({} as ActivatedRouteSnapshot, {} as never));
    return res instanceof UrlTree ? TestBed.inject(Router).serializeUrl(res) : res;
  }

  it('Staff y Administrador pasan', () => {
    for (const rol of ['staff', 'admin']) {
      expect(evaluar([rol])).toBe(true);
      TestBed.resetTestingModule();
    }
  });
  it('Entrenador, Cliente y Master van a /mi-perfil', () => {
    for (const rol of ['trainer', 'client', 'master']) {
      expect(evaluar([rol])).toBe('/mi-perfil');
      TestBed.resetTestingModule();
    }
  });
});
