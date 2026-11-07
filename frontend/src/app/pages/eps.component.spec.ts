import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EpsComponent } from './eps.component';

const activa = { id: 1, name: 'Sura', active: true };
const inactiva = { id: 2, name: 'Nueva EPS', active: false };

describe('EpsComponent', () => {
  let fixture: ComponentFixture<EpsComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  async function montar() {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EpsComponent);
    fixture.detectChanges();
    http.expectOne((r) => r.url === '/api/health-insurance-providers' && r.params.get('includeInactive') === 'true')
      .flush([activa, inactiva]);
    await estabilizar();
  }
  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }
  function recargar(datos: object[]) {
    http.expectOne((r) => r.url === '/api/health-insurance-providers' && r.params.get('includeInactive') === 'true')
      .flush(datos);
  }
  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('lista las EPS con su estado, incluidas las inactivas', async () => {
    await montar();

    const filas = el().querySelectorAll('tbody tr');
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('Activa');
    expect(filas[1].textContent).toContain('Inactiva');
  });

  it('agrega una EPS y recarga el listado', async () => {
    await montar();
    const entrada = el().querySelector('input[name="nueva"]') as HTMLInputElement;
    entrada.value = 'Compensar';
    entrada.dispatchEvent(new Event('input'));
    await estabilizar();

    (el().querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: '/api/health-insurance-providers' });
    expect(req.request.body).toEqual({ name: 'Compensar' });
    req.flush({ id: 3, name: 'Compensar', active: true });
    recargar([activa, inactiva, { id: 3, name: 'Compensar', active: true }]);
    await estabilizar();

    expect(el().querySelectorAll('tbody tr')).toHaveLength(3);
  });

  it('muestra el error de nombre repetido junto al campo', async () => {
    await montar();

    (el().querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http.expectOne({ method: 'POST', url: '/api/health-insurance-providers' }).flush(
      { code: 'HEALTH_INSURER_NAME_TAKEN', field: 'name', message: 'Ya existe una EPS con ese nombre' },
      { status: 409, statusText: 'Conflict' });
    await estabilizar();

    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('Ya existe una EPS');
  });

  it('activa o desactiva y renombra con las rutas del API', async () => {
    await montar();

    (el().querySelector('button.alternar') as HTMLButtonElement).click();
    const off = http.expectOne({ method: 'PATCH', url: '/api/health-insurance-providers/1/active' });
    expect(off.request.body).toEqual({ active: false });
    off.flush({ ...activa, active: false });
    recargar([{ ...activa, active: false }, inactiva]);
    await estabilizar();

    const renombrar = Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.includes('Renombrar'))!;
    renombrar.click();
    await estabilizar();
    const entrada = el().querySelector('input[name="edicion"]') as HTMLInputElement;
    entrada.value = 'Sura EPS';
    entrada.dispatchEvent(new Event('input'));
    const guardar = Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.includes('Guardar'))!;
    guardar.click();
    const put = http.expectOne({ method: 'PUT', url: '/api/health-insurance-providers/1' });
    expect(put.request.body).toEqual({ name: 'Sura EPS' });
    put.flush({ id: 1, name: 'Sura EPS', active: false });
    recargar([{ id: 1, name: 'Sura EPS', active: false }, inactiva]);
  });
});
