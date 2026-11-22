import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Personal, PersonalService } from './personal.service';

const entrenador = (memberId: number, active = true) =>
  ({ memberId, firstName: 'T' + memberId, lastName: 'X', active, roles: ['trainer'] }) as unknown as Personal;

describe('PersonalService', () => {
  let service: PersonalService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(PersonalService);
    http = TestBed.inject(HttpTestingController);
  });

  it('listar pide la página y el tamaño, y el rol si se indica', () => {
    service.listar(2, 10, 'staff').subscribe();
    http
      .expectOne({ method: 'GET', url: '/api/staff?page=2&size=10&role=staff' })
      .flush({ content: [], page: 2, size: 10, totalElements: 0, totalPages: 0 });
    service.listar().subscribe();
    http
      .expectOne({ method: 'GET', url: '/api/staff?page=0&size=20' })
      .flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });

  it('entrenadoresActivos recorre todas las páginas y deja solo a los activos', () => {
    let resultado: Personal[] = [];
    service.entrenadoresActivos().subscribe((r) => (resultado = r));
    http
      .expectOne('/api/staff?page=0&size=50&role=trainer')
      .flush({ content: [entrenador(1), entrenador(2, false)], page: 0, size: 50, totalElements: 3, totalPages: 2 });
    http
      .expectOne('/api/staff?page=1&size=50&role=trainer')
      .flush({ content: [entrenador(3)], page: 1, size: 50, totalElements: 3, totalPages: 2 });
    expect(resultado.map((t) => t.memberId)).toEqual([1, 3]);
    http.verify();
  });
});
