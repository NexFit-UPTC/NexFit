import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { csrfInterceptor } from './csrf.interceptor';
import { SessionService } from './session.service';

const BORRAR = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
const identity = { firstName: 'Ana', lastName: 'Ruiz', email: 'a@x.co' };
const gym = (id: number, name: string) => ({ organizationId: id, name, roles: ['admin'] });

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;
  let router: Router;
  let sesion: SessionService;

  beforeEach(() => {
    document.cookie = BORRAR;
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(withInterceptors([csrfInterceptor])), provideHttpClientTesting()]
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    sesion = TestBed.inject(SessionService);
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
  });
  afterEach(() => {
    document.cookie = BORRAR;
  });

  /** Deja pasar el login con la cookie ya sembrada, para centrar la prueba en lo que sigue. */
  function login(respuesta: object) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    const promesa = auth.iniciarSesion(' ana@x.co ', 'clave');
    const req = http.expectOne({ method: 'POST', url: '/api/auth/login' });
    req.flush(respuesta);
    return { promesa, req };
  }

  async function atenderMe(body: object, status = 200) {
    // El POST ya respondió; falta una microtarea para que arranque cargar().
    await Promise.resolve();
    http.expectOne('/api/me').flush(body, { status, statusText: '' });
  }

  describe('login', () => {
    it('sin cookie: pide primero XSRF-TOKEN y la envía en X-XSRF-TOKEN al hacer el POST', async () => {
      const promesa = auth.iniciarSesion('ana@x.co', 'clave');
      const health = http.expectOne({ method: 'GET', url: '/api/actuator/health' });
      document.cookie = 'XSRF-TOKEN=semilla; path=/'; // el Set-Cookie de health
      health.flush(null);
      const req = http.expectOne({ method: 'POST', url: '/api/auth/login' });
      expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('semilla');
      req.flush({ userId: 1, mustChangePassword: true, establishments: [] });
      await promesa;
    });

    it('con la cookie presente envía X-XSRF-TOKEN y el correo sin espacios', () => {
      const { req } = login({ userId: 1, mustChangePassword: false, establishments: [] });
      expect(req.request.headers.get('X-XSRF-TOKEN')).toBe('abc');
      expect(req.request.body).toEqual({ email: 'ana@x.co', password: 'clave' });
    });

    it('mustChangePassword → /cambiar-contrasena', async () => {
      const { promesa } = login({ userId: 1, mustChangePassword: true, establishments: [gym(1, 'A')] });
      await promesa;
      expect(sesion.estado()).toBe('cambiar-contrasena');
      expect(router.navigateByUrl).toHaveBeenCalledWith('/cambiar-contrasena');
    });

    it('varios establecimientos → /establecimiento sin consultar /me', async () => {
      const { promesa } = login({ userId: 1, mustChangePassword: false, establishments: [gym(1, 'A'), gym(2, 'B')] });
      await promesa;
      expect(sesion.estado()).toBe('sin-establecimiento');
      expect(router.navigateByUrl).toHaveBeenCalledWith('/establecimiento');
      http.expectNone('/api/me');
    });

    it('un solo establecimiento → entra directo', async () => {
      const { promesa } = login({ userId: 1, mustChangePassword: false, establishments: [gym(1, 'A')] });
      await atenderMe({ identity, affiliation: { organizationId: 1, organizationName: 'A', roles: ['admin'] } });
      await promesa;
      expect(sesion.estado()).toBe('activa');
      expect(router.navigateByUrl).toHaveBeenCalledWith('/');
    });

    it('Master (sin establecimientos) → entra directo', async () => {
      const { promesa } = login({ userId: 1, mustChangePassword: false, establishments: [] });
      await atenderMe({ identity, affiliation: null });
      await promesa;
      expect(sesion.esMaster()).toBe(true);
      expect(router.navigateByUrl).toHaveBeenCalledWith('/');
    });

    it('401 se propaga y no navega', async () => {
      document.cookie = 'XSRF-TOKEN=abc; path=/';
      const promesa = auth.iniciarSesion('ana@x.co', 'mala');
      http.expectOne('/api/auth/login').flush({ message: 'Credenciales inválidas' }, { status: 401, statusText: '' });
      await expect(promesa).rejects.toMatchObject({ status: 401 });
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });
  });

  describe('cambiar contraseña', () => {
    it('envía actual y nueva y luego recarga /me', async () => {
      document.cookie = 'XSRF-TOKEN=abc; path=/';
      const promesa = auth.cambiarContrasena('temporal', 'NuevaClave-123');
      const req = http.expectOne({ method: 'POST', url: '/api/auth/password' });
      expect(req.request.body).toEqual({ currentPassword: 'temporal', newPassword: 'NuevaClave-123' });
      req.flush(null, { status: 204, statusText: '' });
      await atenderMe({ identity, affiliation: null });
      await promesa;
      expect(router.navigateByUrl).toHaveBeenCalledWith('/');
    });

    it('si hay varios establecimientos, /me lleva a elegirlo', async () => {
      document.cookie = 'XSRF-TOKEN=abc; path=/';
      const promesa = auth.cambiarContrasena('temporal', 'NuevaClave-123');
      http.expectOne('/api/auth/password').flush(null, { status: 204, statusText: '' });
      await atenderMe({ code: 'ESTABLISHMENT_REQUIRED' }, 403);
      await promesa;
      expect(sesion.estado()).toBe('sin-establecimiento');
    });

    it('400 se propaga', async () => {
      document.cookie = 'XSRF-TOKEN=abc; path=/';
      const promesa = auth.cambiarContrasena('mala', 'NuevaClave-123');
      http.expectOne('/api/auth/password').flush({ message: 'x' }, { status: 400, statusText: '' });
      await expect(promesa).rejects.toMatchObject({ status: 400 });
    });
  });

  describe('establecimientos', () => {
    it('lista con GET /api/auth/establishments', async () => {
      const promesa = auth.listarEstablecimientos();
      http.expectOne({ method: 'GET', url: '/api/auth/establishments' }).flush([gym(1, 'A')]);
      expect(await promesa).toEqual([gym(1, 'A')]);
    });

    it('elegir: POST, recarga /me y entra', async () => {
      document.cookie = 'XSRF-TOKEN=abc; path=/';
      const promesa = auth.elegirEstablecimiento(2);
      const req = http.expectOne({ method: 'POST', url: '/api/auth/establishment' });
      expect(req.request.body).toEqual({ organizationId: 2 });
      req.flush(null, { status: 204, statusText: '' });
      await atenderMe({ identity, affiliation: { organizationId: 2, organizationName: 'B', roles: ['staff'] } });
      expect(await promesa).toBe(true);
      expect(sesion.establecimiento()?.name).toBe('B');
      expect(router.navigateByUrl).toHaveBeenCalledWith('/');
    });

    it('elegir uno sin Administrador: no entra y devuelve false', async () => {
      document.cookie = 'XSRF-TOKEN=abc; path=/';
      const promesa = auth.elegirEstablecimiento(2);
      http.expectOne('/api/auth/establishment').flush(null, { status: 204, statusText: '' });
      await atenderMe({ code: 'ESTABLISHMENT_WITHOUT_ADMIN' }, 403);
      expect(await promesa).toBe(false);
      expect(router.navigateByUrl).not.toHaveBeenCalled();
    });
  });

  describe('cerrar sesión', () => {
    it('POST /api/auth/logout, limpia la sesión y vuelve al login', async () => {
      document.cookie = 'XSRF-TOKEN=abc; path=/';
      sesion.estado.set('activa');
      sesion.roles.set(['admin']);
      const promesa = auth.cerrarSesion();
      http.expectOne({ method: 'POST', url: '/api/auth/logout' }).flush(null, { status: 204, statusText: '' });
      await promesa;
      expect(sesion.estado()).toBe('anonimo');
      expect(sesion.roles()).toEqual([]);
      expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
    });

    it('vuelve al login aunque el servidor falle', async () => {
      document.cookie = 'XSRF-TOKEN=abc; path=/';
      const promesa = auth.cerrarSesion();
      http.expectOne('/api/auth/logout').flush(null, { status: 401, statusText: '' });
      await promesa;
      expect(sesion.estado()).toBe('anonimo');
      expect(router.navigateByUrl).toHaveBeenCalledWith('/login');
    });
  });
});
