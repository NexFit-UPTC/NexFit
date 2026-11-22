import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { EstablecimientosComponent } from './establecimientos.component';

describe('EstablecimientosComponent (validaciones al crear)', () => {
  let fixture: ComponentFixture<EstablecimientosComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EstablecimientosComponent);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/establishments' }).flush([]);
    await fixture.whenStable();
    fixture.detectChanges();
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;

  async function abrirFormulario() {
    el().querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
  }

  function escribir(nombre: string, valor: string) {
    const campo = el().querySelector<HTMLInputElement>(`[name="${nombre}"]`)!;
    campo.value = valor;
    campo.dispatchEvent(new Event('input'));
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const mensajes = () =>
    Array.from(el().querySelectorAll('.nf-error-campo')).map((p) => p.textContent?.trim());

  it('sin nombre ni correo muestra los dos avisos y no llama al servidor', async () => {
    await abrirFormulario();
    await enviar();

    http.expectNone({ method: 'POST', url: '/api/establishments' });
    expect(mensajes()).toEqual(['El nombre es obligatorio', 'El correo de contacto es obligatorio']);
  });

  it('un correo con formato inválido se rechaza con un mensaje claro y sin llamar al servidor', async () => {
    await abrirFormulario();
    for (const correo of ['sin-arroba', 'a@b', 'a b@nexfit.test', '@nexfit.test', 'a@@nexfit.test', 'a@nexfit..test']) {
      escribir('name', 'Gym Centro');
      escribir('contactEmail', correo);
      await enviar();
      expect(mensajes(), correo).toEqual(['El correo de contacto no es válido']);
    }
    http.expectNone({ method: 'POST', url: '/api/establishments' });
  });

  it('un nombre de más de 150 caracteres se rechaza con su mensaje', async () => {
    await abrirFormulario();
    escribir('name', 'N'.repeat(151));
    escribir('contactEmail', 'gym@nexfit.test');
    await enviar();

    http.expectNone({ method: 'POST', url: '/api/establishments' });
    expect(mensajes()).toEqual(['El nombre no puede superar 150 caracteres']);
  });

  it('un correo de más de 150 caracteres se rechaza con su mensaje de longitud', async () => {
    await abrirFormulario();
    escribir('name', 'Gym Centro');
    escribir('contactEmail', `${'c'.repeat(139)}@nexfit.test`);
    await enviar();

    http.expectNone({ method: 'POST', url: '/api/establishments' });
    expect(mensajes()).toEqual(['El correo de contacto no puede superar 150 caracteres']);
  });

  it('con exactamente 150 caracteres sí envía', async () => {
    await abrirFormulario();
    escribir('name', 'N'.repeat(150));
    escribir('contactEmail', `${'c'.repeat(138)}@nexfit.test`);
    await enviar();

    const req = http.expectOne({ method: 'POST', url: '/api/establishments' });
    expect(req.request.body.name).toHaveLength(150);
    expect(req.request.body.contactEmail).toHaveLength(150);
    req.flush({ id: 1 }, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/establishments' }).flush([]);
  });

  it('con datos válidos envía el POST y recarga la lista', async () => {
    await abrirFormulario();
    escribir('name', 'Gym Norte');
    escribir('contactEmail', 'norte@nexfit.test');
    await enviar();

    const req = http.expectOne({ method: 'POST', url: '/api/establishments' });
    expect(req.request.body).toMatchObject({ name: 'Gym Norte', contactEmail: 'norte@nexfit.test' });
    req.flush({ id: 2 }, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/establishments' }).flush([]);
  });

  it('un 400 del servidor (por ejemplo, la zona horaria) se sigue mostrando junto al campo', async () => {
    await abrirFormulario();
    escribir('name', 'Gym Sur');
    escribir('contactEmail', 'sur@nexfit.test');
    escribir('timezone', 'Marte/Olimpo');
    await enviar();

    http.expectOne({ method: 'POST', url: '/api/establishments' }).flush(
      { code: 'VALIDATION_ERROR', field: 'timezone', message: 'La zona horaria no es válida' },
      { status: 400, statusText: 'Bad Request' }
    );
    await fixture.whenStable();
    fixture.detectChanges();

    expect(mensajes()).toEqual(['La zona horaria no es válida']);
  });
});
