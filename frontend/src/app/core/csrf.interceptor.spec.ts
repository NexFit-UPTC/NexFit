import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { csrfInterceptor } from './csrf.interceptor';
import { errorInterceptor } from './error.interceptor';
import { SessionService } from './session.service';

const BORRAR = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';

describe('csrfInterceptor', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;

  beforeEach(() => {
    document.cookie = BORRAR;
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([csrfInterceptor, errorInterceptor])), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    document.cookie = BORRAR;
  });

  it('POST sin cookie: GET previo y luego el POST', () => {
    http.post('/api/auth/login', {}).subscribe();
    ctrl.expectOne({ method: 'GET', url: '/api/actuator/health' }).flush(null, { status: 401, statusText: '' });
    ctrl.expectOne({ method: 'POST', url: '/api/auth/login' }).flush({});
  });

  it('errores de la llamada previa no pasan por el interceptor de errores', () => {
    const sesion = TestBed.inject(SessionService);
    sesion.estado.set('activa');
    http.post('/api/auth/login', {}).subscribe();
    ctrl.expectOne('/api/actuator/health').flush({ code: 'PASSWORD_CHANGE_REQUIRED' }, { status: 403, statusText: '' });
    ctrl.expectOne({ method: 'POST', url: '/api/auth/login' }).flush({});
    expect(sesion.mensaje()).toBeNull();
    expect(sesion.estado()).toBe('activa');
  });

  it('POST con cookie: sin GET previo', () => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    http.post('/api/auth/login', {}).subscribe();
    ctrl.expectOne({ method: 'POST', url: '/api/auth/login' }).flush({});
    ctrl.verify();
  });

  it('GET: pasa directo', () => {
    http.get('/api/me').subscribe();
    ctrl.expectOne('/api/me').flush({});
    ctrl.verify();
  });
});
