import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SessionService } from '../core/session.service';
import { InicioPublicoComponent } from './inicio-publico.component';

const pagina = (page: number, totalPages = 2) => ({
  content: [
    {
      id: 7,
      name: 'Iron Gym',
      branches: [{ name: 'Centro', addressLine1: 'Calle 1', addressLine2: null, city: 'Tunja', department: 'Boyacá' }],
      // Campos que el servidor no envía hoy: la pantalla no debe pintarlos aunque lleguen (CA57).
      contactEmail: 'secreto@gym.co',
      timezone: 'America/Bogota',
      clientCount: 999
    }
  ],
  page,
  size: 12,
  totalElements: 24,
  totalPages
});

describe('InicioPublicoComponent', () => {
  let fixture: ComponentFixture<InicioPublicoComponent>;
  let http: HttpTestingController;
  let sesion: SessionService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    sesion = TestBed.inject(SessionService);
    router = TestBed.inject(Router);
    sesion.estado.set('cargando'); // la página se pinta sin esperar a /api/me
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(InicioPublicoComponent);
    fixture.detectChanges();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const texto = () => el().textContent!;

  it('CA1: sin sesión lista gimnasios con sus sedes y pagina', () => {
    expect(texto()).toContain('Cargando gimnasios');
    http.expectOne('/api/public/gyms?page=0&size=12').flush(pagina(0));
    fixture.detectChanges();
    expect(texto()).toContain('Iron Gym');
    expect(texto()).toContain('Centro · Tunja');
    el().querySelector<HTMLButtonElement>('.siguiente')!.click();
    http.expectOne('/api/public/gyms?page=1&size=12').flush(pagina(1));
    fixture.detectChanges();
    expect(el().querySelector<HTMLButtonElement>('.siguiente')!.disabled).toBe(true);
    el().querySelector<HTMLButtonElement>('.anterior')!.click();
    http.expectOne('/api/public/gyms?page=0&size=12').flush(pagina(0));
  });

  it('CA4: «Ingresar al gimnasio» pasa el id y «Rutinas» va al login', () => {
    http.expectOne('/api/public/gyms?page=0&size=12').flush(pagina(0));
    fixture.detectChanges();
    const enlaces = Array.from(el().querySelectorAll('a'));
    const ingresar = enlaces.find((a) => a.textContent!.includes('Ingresar al gimnasio'))!;
    expect(ingresar.getAttribute('href')).toBe('/login?gimnasio=7');
    expect(enlaces.find((a) => a.textContent!.trim() === 'Rutinas')!.getAttribute('href')).toBe('/login');
    expect(enlaces.find((a) => a.textContent!.includes('Ver detalle'))!.getAttribute('href')).toBe('/gimnasios/7');
  });

  it('CA6: si /api/me llega con sesión después de pintar, va a la pantalla del rol', () => {
    http.expectOne('/api/public/gyms?page=0&size=12').flush(pagina(0));
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    sesion.roles.set(['client']);
    sesion.estado.set('activa');
    fixture.detectChanges();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/inicio', { replaceUrl: true });
  });

  it('CA7: vacío', () => {
    http.expectOne('/api/public/gyms?page=0&size=12').flush({ ...pagina(0), content: [], totalElements: 0, totalPages: 0 });
    fixture.detectChanges();
    expect(el().querySelector('.nf-vacio')?.textContent).toContain('Aún no hay gimnasios');
  });

  it('CA7: error con API caída muestra alerta y Reintentar vuelve a pedir', () => {
    http.expectOne('/api/public/gyms?page=0&size=12').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(el().querySelector('[role=alert]')?.textContent).toContain('No se pudieron cargar');
    el().querySelector<HTMLButtonElement>('.reintentar')!.click();
    http.expectOne('/api/public/gyms?page=0&size=12').flush(pagina(0));
    fixture.detectChanges();
    expect(texto()).toContain('Iron Gym');
  });

  it('CA8: no muestra correo, zona horaria ni conteos', () => {
    http.expectOne('/api/public/gyms?page=0&size=12').flush(pagina(0));
    fixture.detectChanges();
    expect(texto()).not.toMatch(/@|America\/Bogota|999|24/);
  });
});
