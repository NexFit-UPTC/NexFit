import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AsignacionesComponent } from './asignaciones.component';

const entrenador = { memberId: 10, name: 'Ana Ruiz', specialty: 'Fuerza', activeStudents: 1 };
const cliente = {
  memberId: 20, documentType: 'CC', documentNumber: '123', firstName: 'Luis', lastName: 'Gómez', active: true,
  membershipStatus: 'active', membershipEndDate: '2026-12-01'
};
const asignacion = {
  trainerMemberId: 10, trainerName: 'Ana Ruiz', clientMemberId: 20, clientName: 'Luis Gómez',
  assignedAt: '2026-10-01T10:00:00Z', active: true
};

describe('AsignacionesComponent', () => {
  let fixture: ComponentFixture<AsignacionesComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function montar(lista: object[] = [asignacion]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AsignacionesComponent);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/trainers' }).flush([entrenador]);
    http
      .expectOne((r) => r.url === '/api/clients' && r.params.get('active') === 'true')
      .flush({ content: [cliente], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    http
      .expectOne((r) => r.url === '/api/trainer-assignments' && r.params.get('active') === 'true')
      .flush(lista);
    await estabilizar();
  }

  afterEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('lista las asignaciones activas con su estado', async () => {
    await montar();
    expect(el().querySelectorAll('tbody tr')).toHaveLength(1);
    expect(el().textContent).toContain('Ana Ruiz');
    expect(el().textContent).toContain('Luis Gómez');
    expect(el().textContent).toContain('Retirar');
  });

  it('avisa cuando no hay alumnos asignados', async () => {
    await montar([]);
    expect(el().querySelector('.nf-vacio')).not.toBeNull();
  });

  it('pide elegir entrenador y cliente antes de asignar', async () => {
    await montar();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('Selecciona el entrenador y el cliente');
    http.expectNone({ method: 'POST', url: '/api/trainer-assignments' });
  });

  it('asigna un cliente a un entrenador y recarga', async () => {
    await montar();
    const c = fixture.componentInstance as unknown as { entrenadorElegido: number; clienteElegido: number };
    c.entrenadorElegido = 10;
    c.clienteElegido = 20;
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const post = http.expectOne({ method: 'POST', url: '/api/trainer-assignments' });
    expect(post.request.body).toEqual({ trainerMemberId: 10, clientMemberId: 20 });
    post.flush(asignacion, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/trainers' }).flush([entrenador]);
    http.expectOne((r) => r.url === '/api/trainer-assignments').flush([asignacion]);
    await estabilizar();
    expect(el().querySelector('[role="status"]')?.textContent).toContain('Luis Gómez ahora es alumno de Ana Ruiz');
  });

  it('muestra el error del servidor si el cliente ya estaba asignado', async () => {
    await montar();
    const c = fixture.componentInstance as unknown as { entrenadorElegido: number; clienteElegido: number };
    c.entrenadorElegido = 10;
    c.clienteElegido = 20;
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http
      .expectOne({ method: 'POST', url: '/api/trainer-assignments' })
      .flush(
        { code: 'ALREADY_ASSIGNED', field: 'clientMemberId', message: 'El cliente ya es alumno de ese entrenador' },
        { status: 409, statusText: 'Conflict' }
      );
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('ya es alumno');
  });

  it('retira a un alumno', async () => {
    await montar();
    el().querySelector<HTMLButtonElement>('tbody button')!.click();
    const patch = http.expectOne({ method: 'PATCH', url: '/api/trainer-assignments/10/20' });
    expect(patch.request.body).toEqual({ active: false });
    patch.flush({ ...asignacion, active: false });
    http.expectOne({ method: 'GET', url: '/api/trainers' }).flush([entrenador]);
    http.expectOne((r) => r.url === '/api/trainer-assignments').flush([]);
    await estabilizar();
    expect(el().querySelector('.nf-vacio')).not.toBeNull();
  });

  it('el filtro de estado pide también los retirados', async () => {
    await montar();
    const c = fixture.componentInstance as unknown as { filtrar(c: string, v: unknown): void };
    c.filtrar('estado', 'retirados');
    http.expectOne((r) => r.url === '/api/trainer-assignments' && r.params.get('active') === 'false').flush([]);
    c.filtrar('estado', 'todos');
    http.expectOne((r) => r.url === '/api/trainer-assignments' && !r.params.has('active')).flush([]);
  });
});
