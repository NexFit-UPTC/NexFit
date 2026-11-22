import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PesoComponent } from './peso.component';

const registros = [
  { id: 1, measuredOn: '2026-10-01', weightKg: 72 },
  { id: 2, measuredOn: '2026-10-15', weightKg: 71 },
  { id: 3, measuredOn: '2026-11-01', weightKg: 70.5 }
];

describe('PesoComponent', () => {
  let fixture: ComponentFixture<PesoComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function montar(datos: object[] = registros) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(PesoComponent);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/body-weight' }).flush(datos);
    await estabilizar();
  }

  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  interface Interno {
    peso: number | null;
    fecha: string;
    guardar(): void;
  }
  const interno = () => fixture.componentInstance as unknown as Interno;

  it('muestra el peso más reciente, el cambio, la gráfica y el historial más nuevo primero', async () => {
    await montar();
    expect(el().querySelector('[data-testid="ultimo"]')?.textContent?.trim()).toBe('70.5 kg');
    expect(el().querySelector('[data-testid="cambio"]')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('-1.5 kg');
    expect(el().querySelectorAll('svg circle')).toHaveLength(3);
    expect(el().querySelector('svg polyline')?.getAttribute('points')?.split(' ')).toHaveLength(3);
    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(3);
    expect(filas[0].textContent).toContain('01/11/2026');
  });

  it('con un solo registro no hay cambio ni gráfica', async () => {
    await montar([registros[0]]);
    expect(el().querySelector('[data-testid="ultimo"]')).not.toBeNull();
    expect(el().querySelector('[data-testid="cambio"]')).toBeNull();
    expect(el().querySelector('svg')).toBeNull();
  });

  it('avisa cuando aún no hay registros', async () => {
    await montar([]);
    expect(el().querySelector('.nf-vacio')?.textContent).toContain('Aún no has registrado');
  });

  it('valida el rango de 20 a 500 kg antes de enviar', async () => {
    await montar();
    for (const malo of [null, 19, 501]) {
      interno().peso = malo;
      interno().guardar();
      await estabilizar();
      expect(el().querySelector('.nf-error-campo')?.textContent).toContain('20 a 500');
    }
    http.expectNone({ method: 'POST', url: '/api/body-weight' });
  });

  it('guarda el peso (con fecha opcional) y recarga la evolución', async () => {
    await montar();
    interno().peso = 70;
    interno().fecha = '2026-11-05';
    interno().guardar();
    const post = http.expectOne({ method: 'POST', url: '/api/body-weight' });
    expect(post.request.body).toEqual({ weightKg: 70, measuredOn: '2026-11-05' });
    post.flush({ id: 4, measuredOn: '2026-11-05', weightKg: 70 }, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/body-weight' }).flush([...registros, { id: 4, measuredOn: '2026-11-05', weightKg: 70 }]);
    await estabilizar();
    expect(el().querySelector('[role="status"]')?.textContent).toContain('Peso guardado');
    expect(el().querySelectorAll('tbody tr')).toHaveLength(4);
  });

  it('sin fecha envía solo el peso y muestra el error del servidor', async () => {
    await montar();
    interno().peso = 70.123;
    interno().guardar();
    const post = http.expectOne({ method: 'POST', url: '/api/body-weight' });
    expect(post.request.body).toEqual({ weightKg: 70.123 });
    post.flush(
      { code: 'VALIDATION_ERROR', field: 'weightKg', message: 'El peso va de 20 a 500 kg, con hasta dos decimales' },
      { status: 400, statusText: 'Bad Request' }
    );
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('dos decimales');
  });
});
