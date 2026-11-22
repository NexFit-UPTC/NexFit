import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ClientesService } from './clientes.service';

describe('ClientesService', () => {
  let service: ClientesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(ClientesService);
    http = TestBed.inject(HttpTestingController);
  });

  it('listar solo envía los filtros que tienen valor', () => {
    service.listar({ q: '  laura ', active: false, page: 2, size: 20 }).subscribe();
    const req = http.expectOne((r) => r.url === '/api/clients');
    expect(req.request.params.get('q')).toBe('laura');
    expect(req.request.params.get('active')).toBe('false');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('20');
    req.flush({});

    service.listar({ q: '   ' }).subscribe();
    const vacio = http.expectOne((r) => r.url === '/api/clients');
    expect(vacio.request.params.keys()).toEqual([]);
    vacio.flush({});
  });

  it('detalle y crear usan las rutas de la API', () => {
    service.detalle(7).subscribe();
    http.expectOne({ method: 'GET', url: '/api/clients/7' }).flush({});
    service.crear({} as never).subscribe();
    http.expectOne({ method: 'POST', url: '/api/clients' }).flush({});
  });

  it('buscarPersona confirma la verificación del documento y la presencia', () => {
    service.buscarPersona('CE', '99').subscribe();
    const req = http.expectOne((r) => r.url === '/api/clients/search');
    expect(req.request.params.get('documentType')).toBe('CE');
    expect(req.request.params.get('documentNumber')).toBe('99');
    expect(req.request.params.get('documentVerified')).toBe('true');
    expect(req.request.params.get('personPresent')).toBe('true');
    req.flush({});
  });
});
