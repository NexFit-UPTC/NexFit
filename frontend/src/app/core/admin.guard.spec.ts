import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, UrlTree } from '@angular/router';
import { adminGuard } from './admin.guard';
import { SessionService } from './session.service';

describe('adminGuard', () => {
  function evaluar(roles: string[]) {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.inject(SessionService).roles.set(roles);
    const res = TestBed.runInInjectionContext(() => adminGuard({} as ActivatedRouteSnapshot, {} as never));
    return res instanceof UrlTree ? TestBed.inject(Router).serializeUrl(res) : res;
  }

  it('el Administrador pasa', () => {
    expect(evaluar(['admin'])).toBe(true);
  });
  it('un Administrador que también es Entrenador pasa', () => {
    expect(evaluar(['trainer', 'admin'])).toBe(true);
  });
  it('Staff, Entrenador, Cliente y Master son rechazados hacia /mi-perfil', () => {
    for (const rol of ['staff', 'trainer', 'client', 'master']) {
      expect(evaluar([rol])).toBe('/mi-perfil');
      TestBed.resetTestingModule();
    }
  });
});
