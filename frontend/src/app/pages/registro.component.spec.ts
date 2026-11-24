import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SessionService } from '../core/session.service';
import { RegistroComponent } from './registro.component';

describe('RegistroComponent', () => {
  let fixture: ComponentFixture<RegistroComponent>;
  let http: HttpTestingController;
  let router: Router;
  let sesion: SessionService;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    sessionStorage.removeItem('nexfit.gimnasio');
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    sesion = TestBed.inject(SessionService);
    sesion.estado.set('anonimo');
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(RegistroComponent);
    fixture.detectChanges();
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    sessionStorage.removeItem('nexfit.gimnasio');
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const campo = (id: string) => el().querySelector<HTMLInputElement>('#' + id)!;

  function escribir(id: string, valor: string) {
    campo(id).value = valor;
    campo(id).dispatchEvent(new Event(id === 'documentType' ? 'change' : 'input'));
  }

  function llenar(cambios: Record<string, string> = {}) {
    const datos: Record<string, string> = {
      firstName: ' Ana ',
      lastName: 'Pérez',
      documentType: 'CE',
      documentNumber: '12345',
      birthDate: '1990-05-17',
      email: ' ana@x.co ',
      password: 'claveSegura1 ',
      confirmar: 'claveSegura1 ',
      ...cambios
    };
    for (const [id, valor] of Object.entries(datos)) escribir(id, valor);
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('muestra los campos y el enlace «¿Ya tienes cuenta? Inicia sesión» a /login', () => {
    for (const id of ['firstName', 'lastName', 'documentType', 'documentNumber', 'birthDate', 'email', 'password', 'confirmar']) {
      expect(campo(id)).toBeTruthy();
    }
    expect(campo('birthDate').type).toBe('date');
    const enlace = el().querySelector<HTMLAnchorElement>('a[href="/login"]')!;
    expect(enlace.textContent).toContain('Inicia sesión');
    expect(el().textContent).toContain('¿Ya tienes cuenta?');
  });

  it('página propia: título, sección «Datos personales», Cancelar a /login y sin campos que el backend no guarda', () => {
    expect(el().querySelector('h1')?.textContent).toContain('Crea tu cuenta');
    expect(el().querySelector('legend')?.textContent).toContain('Datos personales');
    expect(el().querySelector('app-auth-shell')).toBeNull();
    const cancelar = Array.from(el().querySelectorAll<HTMLAnchorElement>('a.nf-boton-sec')).find((a) => a.textContent?.includes('Cancelar'))!;
    expect(cancelar.getAttribute('href')).toBe('/login');
    expect(el().querySelector('button[type=submit]')?.textContent).toContain('Crear cuenta');
    for (const id of ['phone', 'eps', 'weight', 'height']) expect(campo(id)).toBeNull();
    expect(el().querySelectorAll('input:not([type=checkbox]), select').length).toBe(8);
  });

  it('registro válido: POST con los siete campos (contraseña sin recortar) y navega a /login?registrado=1', async () => {
    llenar();
    const hecho = enviar();
    const req = http.expectOne({ method: 'POST', url: '/api/auth/register' });
    expect(req.request.body).toEqual({
      documentType: 'CE',
      documentNumber: '12345',
      firstName: 'Ana',
      lastName: 'Pérez',
      birthDate: '1990-05-17',
      email: 'ana@x.co',
      password: 'claveSegura1 '
    });
    req.flush({ userId: 9 }, { status: 201, statusText: 'Created' });
    await hecho;
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { registrado: 1 } });
  });

  it('conserva ?gimnasio= al ir al login', async () => {
    sessionStorage.setItem('nexfit.gimnasio', '7');
    llenar();
    const hecho = enviar();
    http.expectOne('/api/auth/register').flush({ userId: 9 }, { status: 201, statusText: 'Created' });
    await hecho;
    expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { registrado: 1, gimnasio: 7 } });
  });

  it('contraseña de 9 caracteres: mensaje claro y no llama a la API', async () => {
    llenar({ password: '123456789', confirmar: '123456789' });
    await enviar();
    http.expectNone('/api/auth/register');
    expect(el().textContent).toContain('La contraseña debe tener al menos 10 caracteres.');
  });

  it('contraseñas distintas: mensaje y no llama a la API', async () => {
    llenar({ confirmar: 'otraClave12345' });
    await enviar();
    http.expectNone('/api/auth/register');
    expect(el().textContent).toContain('Las contraseñas no coinciden.');
  });

  it('formulario vacío: marca los requeridos sin llamar a la API', async () => {
    await enviar();
    http.expectNone('/api/auth/register');
    expect(el().textContent).toContain('Escribe tu nombre.');
    expect(el().textContent).toContain('Escribe tu fecha de nacimiento.');
  });

  it('400: el mensaje del servidor aparece junto al campo de `field`', async () => {
    llenar();
    const hecho = enviar();
    http
      .expectOne('/api/auth/register')
      .flush(
        { code: 'VALIDATION_ERROR', field: 'birthDate', message: 'La fecha debe tener el formato AAAA-MM-DD' },
        { status: 400, statusText: '' }
      );
    await hecho;
    expect(el().querySelector('#birthDate-error')?.textContent).toContain('La fecha debe tener el formato');
    expect(el().querySelector('[role=alert]')).toBeNull();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('409: alerta general con el mensaje del backend, sin atribuirlo a un campo', async () => {
    llenar();
    const hecho = enviar();
    http.expectOne('/api/auth/register').flush({ message: 'Ya existe una cuenta con esos datos' }, { status: 409, statusText: '' });
    await hecho;
    expect(el().querySelector('[role=alert]')?.textContent).toContain('Ya existe una cuenta con esos datos');
    expect(el().querySelector('.nf-campo .nf-error-campo')).toBeNull();
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('falla de red: mensaje de conexión', async () => {
    llenar();
    const hecho = enviar();
    http.expectOne('/api/auth/register').error(new ProgressEvent('error'));
    await hecho;
    expect(el().querySelector('[role=alert]')?.textContent).toContain('No se pudo conectar');
  });

  it('con sesión activa redirige a /; también si /api/me llega después de pintar', () => {
    sesion.estado.set('cargando');
    fixture.detectChanges();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    sesion.estado.set('activa');
    fixture.detectChanges();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('sin establecimiento o con contraseña temporal va a su pantalla', () => {
    sesion.estado.set('sin-establecimiento');
    fixture.detectChanges();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/establecimiento');
    sesion.estado.set('cambiar-contrasena');
    fixture.detectChanges();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/cambiar-contrasena');
  });
});
