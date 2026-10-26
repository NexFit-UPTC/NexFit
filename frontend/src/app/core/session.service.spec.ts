import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SessionService } from './session.service';

describe('SessionService', () => {
  let http: HttpTestingController;
  let sesion: SessionService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    sesion = TestBed.inject(SessionService);
  });

  function responder(body: object, status = 200) {
    sesion.cargar();
    http.expectOne('/api/me').flush(body, { status, statusText: '' });
  }

  const identity = { firstName: 'Ana', lastName: 'Ruiz', email: 'a@x.co' };

  it('Master: affiliation nula → activa con rol master', () => {
    responder({ identity, affiliation: null });
    expect(sesion.estado()).toBe('activa');
    expect(sesion.esMaster()).toBe(true);
  });

  it('con afiliación → roles del establecimiento activo', () => {
    responder({ identity, affiliation: { organizationId: 1, organizationName: 'Gym', roles: ['admin', 'trainer'] } });
    expect(sesion.roles()).toEqual(['admin', 'trainer']);
    expect(sesion.establecimiento()?.name).toBe('Gym');
  });

  it.each([
    [401, undefined, 'anonimo'],
    [403, 'ESTABLISHMENT_REQUIRED', 'sin-establecimiento'],
    [403, 'PASSWORD_CHANGE_REQUIRED', 'cambiar-contrasena'],
    [500, undefined, 'error']
  ])('%i %s → %s', (status, code, esperado) => {
    responder({ code }, status);
    expect(sesion.estado()).toBe(esperado);
  });
});
