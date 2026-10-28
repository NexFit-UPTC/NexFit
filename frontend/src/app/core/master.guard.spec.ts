import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, provideRouter, Router, UrlTree } from '@angular/router';
import { masterGuard } from './master.guard';
import { SessionService } from './session.service';

describe('masterGuard', () => {
  function evaluar(roles: string[]) {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    TestBed.inject(SessionService).roles.set(roles);
    const res = TestBed.runInInjectionContext(() => masterGuard({} as ActivatedRouteSnapshot, {} as never));
    return res instanceof UrlTree ? TestBed.inject(Router).serializeUrl(res) : res;
  }

  it('el Master pasa', () => {
    expect(evaluar(['master'])).toBe(true);
  });
  it('un Administrador es rechazado hacia /mi-perfil', () => {
    expect(evaluar(['admin'])).toBe('/mi-perfil');
  });
});
