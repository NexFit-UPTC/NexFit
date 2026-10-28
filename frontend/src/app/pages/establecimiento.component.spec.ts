import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SessionService } from '../core/session.service';
import { EstablecimientoComponent } from './establecimiento.component';

const lista = [
  { organizationId: 1, name: 'Gym Norte', roles: ['admin', 'trainer'] },
  { organizationId: 2, name: 'Gym Sur', roles: ['client'] }
];

describe('EstablecimientoComponent', () => {
  let fixture: ComponentFixture<EstablecimientoComponent>;
  let http: HttpTestingController;
  let router: Router;
  let sesion: SessionService;

  beforeEach(async () => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    sesion = TestBed.inject(SessionService);
    sesion.estado.set('sin-establecimiento');
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(EstablecimientoComponent);
    fixture.detectChanges();
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;

  async function cargarLista(cuerpo: object[] = lista) {
    http.expectOne({ method: 'GET', url: '/api/auth/establishments' }).flush(cuerpo);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('lista los establecimientos con sus roles en español', async () => {
    await cargarLista();
    const opciones = el().querySelectorAll('button.opcion');
    expect(opciones).toHaveLength(2);
    expect(opciones[0].textContent).toContain('Gym Norte');
    expect(opciones[0].textContent).toContain('Administrador');
    expect(opciones[0].textContent).toContain('Entrenador');
    expect(opciones[1].textContent).toContain('Cliente');
  });

  it('al elegir: POST /api/auth/establishment con el id y entra', async () => {
    await cargarLista();
    el().querySelectorAll<HTMLButtonElement>('button.opcion')[1].click();
    const req = http.expectOne({ method: 'POST', url: '/api/auth/establishment' });
    expect(req.request.body).toEqual({ organizationId: 2 });
    req.flush(null, { status: 204, statusText: '' });
    await Promise.resolve();
    http
      .expectOne('/api/me')
      .flush({
        identity: { firstName: 'A', lastName: 'B', email: 'a@x.co' },
        affiliation: { organizationId: 2, organizationName: 'Gym Sur', roles: ['client'] }
      });
    await fixture.whenStable();
    expect(sesion.establecimiento()?.name).toBe('Gym Sur');
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('si el elegido no tiene Administrador, avisa y se queda', async () => {
    await cargarLista();
    el().querySelectorAll<HTMLButtonElement>('button.opcion')[0].click();
    http.expectOne('/api/auth/establishment').flush(null, { status: 204, statusText: '' });
    await Promise.resolve();
    http.expectOne('/api/me').flush({ code: 'ESTABLISHMENT_WITHOUT_ADMIN' }, { status: 403, statusText: '' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('Administrador asignado');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('400 al elegir: mensaje de no disponible', async () => {
    await cargarLista();
    el().querySelectorAll<HTMLButtonElement>('button.opcion')[0].click();
    http.expectOne('/api/auth/establishment').flush({ message: 'x' }, { status: 400, statusText: '' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('ya no está disponible');
  });

  it('lista vacía: lo dice y deja cerrar sesión', async () => {
    await cargarLista([]);
    expect(el().textContent).toContain('No tienes ningún establecimiento activo');
    expect(el().textContent).toContain('Cerrar sesión');
  });

  it('error al cargar: ofrece reintentar', async () => {
    http.expectOne('/api/auth/establishments').flush(null, { status: 500, statusText: '' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('No se pudieron cargar');
    expect(el().textContent).toContain('Reintentar');
  });
});
