import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter, Router } from '@angular/router';
import { SessionService } from '../core/session.service';
import { LoginComponent } from './login.component';

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let http: HttpTestingController;
  let router: Router;
  let sesion: SessionService;

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    sesion = TestBed.inject(SessionService);
    sesion.estado.set('anonimo');
    vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    fixture = TestBed.createComponent(LoginComponent);
    fixture.detectChanges();
  });
  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const entrada = (id: string) => el().querySelector<HTMLInputElement>('#' + id)!;

  function escribir(email: string, clave: string) {
    entrada('email').value = email;
    entrada('email').dispatchEvent(new Event('input'));
    entrada('password').value = clave;
    entrada('password').dispatchEvent(new Event('input'));
  }

  async function enviar() {
    el().querySelector('form')!.dispatchEvent(new Event('submit'));
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('muestra el formulario con sus etiquetas', () => {
    expect(el().querySelector('h1')?.textContent).toContain('Iniciar sesión');
    expect(el().querySelector('label[for=email]')).toBeTruthy();
    expect(el().querySelector('label[for=password]')).toBeTruthy();
  });

  it('muestra debajo del formulario «¿No tienes cuenta? Regístrate» hacia /registro', () => {
    const enlace = el().querySelector<HTMLAnchorElement>('a[href="/registro"]')!;
    expect(enlace.textContent).toContain('Regístrate');
    expect(el().textContent).toContain('¿No tienes cuenta?');
    const form = el().querySelector('form')!;
    expect(form.compareDocumentPosition(enlace) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  describe('parámetro gimnasio', () => {
    function abrirCon(gimnasio?: string) {
      TestBed.inject(Router);
      const ruta = TestBed.inject(ActivatedRoute);
      vi.spyOn(ruta.snapshot.queryParamMap, 'get').mockImplementation((k) => (k === 'gimnasio' ? (gimnasio ?? null) : null));
      TestBed.createComponent(LoginComponent);
    }
    beforeEach(() => sessionStorage.removeItem('nexfit.gimnasio'));
    afterEach(() => vi.restoreAllMocks());

    it('válido: lo guarda', () => {
      abrirCon('7');
      expect(sessionStorage.getItem('nexfit.gimnasio')).toBe('7');
    });

    it.each(['abc', '0', '-1', '1.5', ''])('inválido «%s»: no guarda y borra el anterior', (valor) => {
      sessionStorage.setItem('nexfit.gimnasio', '7');
      abrirCon(valor);
      expect(sessionStorage.getItem('nexfit.gimnasio')).toBeNull();
    });

    it('ausente: borra el anterior', () => {
      sessionStorage.setItem('nexfit.gimnasio', '7');
      abrirCon();
      expect(sessionStorage.getItem('nexfit.gimnasio')).toBeNull();
    });

    it('sin sessionStorage disponible no rompe el login', () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('bloqueado');
      });
      expect(() => abrirCon('7')).not.toThrow();
    });
  });

  it('no llama a la API con el formulario vacío y marca los errores', async () => {
    await enviar();
    http.expectNone('/api/auth/login');
    expect(el().textContent).toContain('Escribe un correo válido.');
    expect(el().textContent).toContain('Escribe tu contraseña.');
  });

  it('envía correo y contraseña a POST /api/auth/login', async () => {
    escribir('ana@x.co', 'secreto');
    const hecho = enviar();
    const req = http.expectOne({ method: 'POST', url: '/api/auth/login' });
    expect(req.request.body).toEqual({ email: 'ana@x.co', password: 'secreto' });
    // Como el router real, al navegar fuera de /login se destruye el componente (y su effect).
    vi.mocked(router.navigateByUrl).mockImplementation(async () => {
      fixture.destroy();
      return true;
    });
    req.flush({ userId: 1, mustChangePassword: true, establishments: [] });
    await hecho;
    expect(router.navigateByUrl).toHaveBeenCalledWith('/cambiar-contrasena');
    expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
  });

  it('401: mensaje genérico, sin revelar si el correo existe, y limpia la contraseña', async () => {
    escribir('nadie@x.co', 'mala');
    const hecho = enviar();
    http.expectOne('/api/auth/login').flush({ message: 'Credenciales inválidas' }, { status: 401, statusText: '' });
    await hecho;
    const alerta = el().querySelector('[role=alert]')!.textContent!;
    expect(alerta).toContain('Correo o contraseña incorrectos');
    expect(alerta.toLowerCase()).not.toMatch(/no existe|no registrado|bloquead/);
    expect(entrada('password').value).toBe('');
    expect(router.navigateByUrl).not.toHaveBeenCalled();
  });

  it('falla de red: mensaje de conexión', async () => {
    escribir('ana@x.co', 'secreto');
    const hecho = enviar();
    http.expectOne('/api/auth/login').error(new ProgressEvent('error'));
    await hecho;
    expect(el().querySelector('[role=alert]')?.textContent).toContain('No se pudo conectar');
  });

  it('el botón Mostrar alterna la visibilidad de la contraseña', () => {
    expect(entrada('password').type).toBe('password');
    el().querySelector<HTMLButtonElement>('button[aria-pressed]')!.click();
    fixture.detectChanges();
    expect(entrada('password').type).toBe('text');
  });

  it('mientras la sesión carga muestra «Verificando sesión…» y el formulario sigue usable', () => {
    sesion.estado.set('cargando');
    fixture.detectChanges();
    expect(el().querySelector('[role=status]')?.textContent).toContain('Verificando sesión');
    expect(el().querySelector('form')).toBeTruthy();
    expect(el().querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(false);
    sesion.estado.set('anonimo');
    fixture.detectChanges();
    expect(el().querySelector('[role=status]')).toBeNull();
  });

  it('si /api/me llega con sesión después de pintar, redirige a /', () => {
    sesion.estado.set('cargando');
    fixture.detectChanges();
    expect(router.navigateByUrl).not.toHaveBeenCalled();
    sesion.estado.set('activa');
    fixture.detectChanges();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('quien ya tiene sesión es llevado fuera del login', () => {
    sesion.estado.set('activa');
    const otro = TestBed.createComponent(LoginComponent);
    otro.detectChanges();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/');
  });

  describe('aviso de cuenta creada', () => {
    function abrirCon(registrado?: string) {
      const ruta = TestBed.inject(ActivatedRoute);
      vi.spyOn(ruta.snapshot.queryParamMap, 'get').mockImplementation((k) => (k === 'registrado' ? (registrado ?? null) : null));
      const f = TestBed.createComponent(LoginComponent);
      f.detectChanges();
      return f.nativeElement as HTMLElement;
    }
    afterEach(() => vi.restoreAllMocks());

    it('con ?registrado=1 muestra el aviso', () => {
      expect(abrirCon('1').querySelector('[role=status]')?.textContent).toContain('Cuenta creada');
    });

    it('sin el parámetro (o con otro valor) no lo muestra', () => {
      expect(abrirCon().textContent).not.toContain('Cuenta creada');
      expect(abrirCon('0').textContent).not.toContain('Cuenta creada');
    });
  });

  describe('inicio de sesión con Google', () => {
    function abrirCon(parametros: Record<string, string>) {
      const ruta = TestBed.inject(ActivatedRoute);
      vi.spyOn(ruta.snapshot.queryParamMap, 'get').mockImplementation((k) => parametros[k] ?? null);
      const f = TestBed.createComponent(LoginComponent);
      f.detectChanges();
      return f.nativeElement as HTMLElement;
    }
    beforeEach(() => sessionStorage.removeItem('nexfit.gimnasio'));
    afterEach(() => vi.restoreAllMocks());

    it('el botón es un enlace de navegación completa al backend, debajo del formulario', () => {
      const boton = el().querySelector<HTMLAnchorElement>('a[href="/api/oauth2/authorization/google"]')!;
      expect(boton.textContent).toContain('Continuar con Google');
      const form = el().querySelector('form')!;
      expect(form.compareDocumentPosition(boton) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('con ?error=google muestra un mensaje genérico, sin revelar el motivo', () => {
      const alerta = abrirCon({ error: 'google' }).querySelector('[role=alert]')!.textContent!;
      expect(alerta).toContain('No se pudo iniciar sesión con Google');
      expect(alerta.toLowerCase()).not.toMatch(/no existe|no registrado|bloquead|inactiv|master/);
    });

    it('sin ?error=google no muestra el mensaje', () => {
      expect(el().textContent).not.toContain('con Google. Usa tu correo');
      expect(abrirCon({ error: 'otro' }).querySelector('[role=alert]')).toBeNull();
    });

    it.each([{ error: 'google' }, { google: '1' }])('al volver de Google (%o) conserva el gimnasio guardado', (parametros) => {
      sessionStorage.setItem('nexfit.gimnasio', '7');
      abrirCon(parametros);
      expect(sessionStorage.getItem('nexfit.gimnasio')).toBe('7');
    });

    it('sin marca de Google y sin ?gimnasio sigue borrando el gimnasio guardado', () => {
      sessionStorage.setItem('nexfit.gimnasio', '7');
      abrirCon({});
      expect(sessionStorage.getItem('nexfit.gimnasio')).toBeNull();
    });

    describe('correo verificado sin cuenta (?google=nuevo, NEX-148)', () => {
      beforeEach(() => {
        sessionStorage.removeItem('nexfit.registro-google');
        vi.spyOn(router, 'navigate').mockResolvedValue(true);
      });
      afterEach(() => sessionStorage.removeItem('nexfit.registro-google'));

      it('ofrece crear la cuenta en vez del error genérico', () => {
        const pagina = abrirCon({ google: 'nuevo' });
        expect(pagina.textContent).toContain('No encontramos una cuenta con ese correo. ¿Quieres crear una?');
        expect(pagina.textContent).not.toContain('No se pudo iniciar sesión con Google');
        const boton = pagina.querySelector<HTMLAnchorElement>('a.nf-boton')!;
        expect(boton.textContent).toContain('Crear cuenta con este correo');
        expect(boton.getAttribute('href')).toBe('/registro?google=1');
        expect(router.navigate).not.toHaveBeenCalled();
      });

      it('no aparece con otro valor de ?google ni con ?error=google', () => {
        expect(abrirCon({ google: '1' }).textContent).not.toContain('¿Quieres crear una?');
        expect(abrirCon({ error: 'google' }).textContent).not.toContain('¿Quieres crear una?');
      });

      it('conserva el gimnasio guardado', () => {
        sessionStorage.setItem('nexfit.gimnasio', '7');
        abrirCon({ google: 'nuevo' });
        expect(sessionStorage.getItem('nexfit.gimnasio')).toBe('7');
      });

      it('con la marca de «Registrarme con Google» la borra y salta directo a /registro?google=1', () => {
        sessionStorage.setItem('nexfit.registro-google', '1');
        sessionStorage.setItem('nexfit.gimnasio', '7');
        const pagina = abrirCon({ google: 'nuevo' });
        expect(sessionStorage.getItem('nexfit.registro-google')).toBeNull();
        expect(router.navigate).toHaveBeenCalledWith(['/registro'], { queryParams: { google: 1 }, replaceUrl: true });
        expect(pagina.textContent).not.toContain('¿Quieres crear una?');
        expect(sessionStorage.getItem('nexfit.gimnasio')).toBe('7');
      });

      it('sin ?google=nuevo (con ?error=google o ?google=1) la marca se borra y no se navega', () => {
        for (const consulta of [{ error: 'google' }, { google: '1' }]) {
          sessionStorage.setItem('nexfit.registro-google', '1');
          abrirCon(consulta);
          expect(sessionStorage.getItem('nexfit.registro-google')).toBeNull();
          expect(router.navigate).not.toHaveBeenCalled();
        }
      });

      it('una marca vieja ya borrada no salta la pregunta en una carga posterior con ?google=nuevo', () => {
        sessionStorage.setItem('nexfit.registro-google', '1');
        abrirCon({ error: 'google' });
        const pagina = abrirCon({ google: 'nuevo' });
        expect(pagina.textContent).toContain('¿Quieres crear una?');
        expect(router.navigate).not.toHaveBeenCalled();
      });
    });
  });
});
