import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SessionService } from '../core/session.service';
import { EjerciciosComponent } from './ejercicios.component';

const global = { id: 1, scope: 'global', name: 'Sentadilla', muscleGroup: 'Piernas', description: 'Base', imageUrl: null, active: true };
const propio = { id: 2, scope: 'own', name: 'Remo TRX', muscleGroup: 'Espalda', description: null, imageUrl: 'https://cdn/r.png', active: true };

describe('EjerciciosComponent', () => {
  let fixture: ComponentFixture<EjerciciosComponent>;
  let http: HttpTestingController;

  async function montar(roles: string[], datos: object[] = [global, propio]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EjerciciosComponent);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/exercises').flush(datos);
    await estabilizar();
  }
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;
  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('muestra las tarjetas con su ilustración por grupo muscular y el origen', async () => {
    await montar(['client']);
    const imgs = el().querySelectorAll<HTMLImageElement>('.nf-media img');
    expect(imgs[0].getAttribute('src')).toBe('/img/ejercicios/piernas.svg');
    expect(imgs[1].getAttribute('src')).toBe('https://cdn/r.png');
    expect(el().textContent).toContain('Global');
    expect(el().textContent).toContain('Propio');
  });

  it('Staff y Cliente solo leen', async () => {
    for (const rol of ['staff', 'client']) {
      TestBed.resetTestingModule();
      await montar([rol]);
      expect(el().querySelector('.nf-cabecera button')).toBeNull();
      expect(el().querySelectorAll('.nf-media button')).toHaveLength(0);
    }
  });

  it('el Entrenador puede crear pero no editar', async () => {
    await montar(['trainer']);
    expect(el().querySelector('.nf-cabecera button')!.textContent).toContain('propio');
    expect(el().querySelectorAll('.nf-media button')).toHaveLength(0);
  });

  it('el Administrador edita solo los ejercicios propios', async () => {
    await montar(['admin']);
    const tarjetas = el().querySelectorAll('.nf-media');
    expect(tarjetas[0].querySelectorAll('button')).toHaveLength(0);
    expect(tarjetas[1].querySelectorAll('button')).toHaveLength(1);
    tarjetas[1].querySelector('button')!.click();
    await estabilizar();
    const nombre = el().querySelector<HTMLInputElement>('input[name="nombre"]')!;
    expect(nombre.value).toBe('Remo TRX');
    nombre.value = 'Remo TRX alto';
    nombre.dispatchEvent(new Event('input'));
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'PUT', url: '/api/exercises/2' });
    expect(req.request.body.name).toBe('Remo TRX alto');
    req.flush({ ...propio, name: 'Remo TRX alto' });
    http.expectOne((r) => r.url === '/api/exercises').flush([global, propio]);
    await estabilizar();
    expect(el().querySelector('form')).toBeNull();
  });

  it('muestra el error del servidor cuando el nombre ya existe', async () => {
    await montar(['admin'], []);
    el().querySelector<HTMLButtonElement>('.nf-cabecera button')!.click();
    await estabilizar();
    const nombre = el().querySelector<HTMLInputElement>('input[name="nombre"]')!;
    nombre.value = 'Sentadilla';
    nombre.dispatchEvent(new Event('input'));
    await estabilizar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/exercises' }).flush(
      { code: 'EXERCISE_NAME_TAKEN', field: 'name', message: 'Ya existe un ejercicio con ese nombre en este catálogo' },
      { status: 409, statusText: 'Conflict' }
    );
    await estabilizar();
    expect(el().querySelector('form')!.textContent).toContain('Ya existe un ejercicio');
  });

  it('el Master crea globales, edita solo globales y alterna activo', async () => {
    await montar(['master'], [global, { ...global, id: 3, name: 'Peso muerto', active: false }]);
    expect(el().querySelector('.nf-cabecera button')!.textContent).toContain('global');
    expect(el().querySelector('select[name="filtroOrigen"]')).toBeNull();
    const botones = el().querySelectorAll('.nf-media')[1].querySelectorAll('button');
    expect(botones).toHaveLength(2);
    expect(botones[1].textContent).toContain('Activar');
    botones[1].click();
    http.expectOne({ method: 'PATCH', url: '/api/exercises/3/active' }).flush({ ...global, id: 3, active: true });
    http.expectOne((r) => r.url === '/api/exercises').flush([global]);
    await estabilizar();
    expect(el().querySelectorAll('.nf-media')).toHaveLength(1);
  });

  it('filtra por nombre y origen', async () => {
    await montar(['admin']);
    const busqueda = el().querySelector<HTMLInputElement>('input[name="busqueda"]')!;
    busqueda.value = 'remo';
    busqueda.dispatchEvent(new Event('input'));
    http.expectOne({ method: 'GET', url: '/api/exercises?q=remo' }).flush([propio]);
    await estabilizar();
    const origen = el().querySelector<HTMLSelectElement>('select[name="filtroOrigen"]')!;
    origen.value = 'own';
    origen.dispatchEvent(new Event('change'));
    http.expectOne({ method: 'GET', url: '/api/exercises?q=remo&scope=own' }).flush([propio]);
    await estabilizar();
    expect(el().querySelectorAll('.nf-media')).toHaveLength(1);
  });
});
