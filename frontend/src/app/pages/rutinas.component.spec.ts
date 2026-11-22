import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { SessionService } from '../core/session.service';
import { RutinasComponent } from './rutinas.component';

const ejercicio = (id: number, name: string) => ({
  id, scope: 'global', name, muscleGroup: 'Pecho', description: null, imageUrl: null, active: true
});
const alumna = {
  trainerMemberId: 10, trainerName: 'Ana Ruiz', clientMemberId: 20, clientName: 'Luis Gómez',
  assignedAt: '2026-10-01T10:00:00Z', active: true
};
const resumen = {
  id: 1, trainerMemberId: 10, trainerName: 'Ana Ruiz', clientMemberId: 20, clientName: 'Luis Gómez',
  name: 'Fuerza A', category: 'strength', level: 'beginner', estimatedMinutes: 45, estimatedKcal: 300,
  createdAt: '2026-10-05T10:00:00Z', active: true, exerciseCount: 2
};
const archivada = { ...resumen, id: 2, name: 'Vieja', active: false, category: null, level: null };
const detalle = {
  ...resumen,
  exercises: [
    { id: 1, exerciseId: 100, exerciseName: 'Press banca', muscleGroup: 'Pecho', imageUrl: null, order: 1, series: 3,
      repetitions: 12, restSeconds: 60 },
    { id: 2, exerciseId: 101, exerciseName: 'Sentadilla', muscleGroup: 'Piernas', imageUrl: null, order: 2, series: 4,
      repetitions: 8, restSeconds: 90 }
  ]
};

describe('RutinasComponent', () => {
  let fixture: ComponentFixture<RutinasComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function montar(roles: string[], lista: object[] = [resumen, archivada]) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.inject(SessionService).roles.set(roles);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RutinasComponent);
    fixture.detectChanges();
    if (roles.includes('trainer')) {
      http.expectOne((r) => r.url === '/api/exercises' && r.params.get('active') === 'true')
        .flush([ejercicio(100, 'Press banca'), ejercicio(101, 'Sentadilla')]);
      http.expectOne((r) => r.url === '/api/trainer-assignments' && r.params.get('active') === 'true').flush([alumna]);
      http.expectOne((r) => r.url === '/api/routines' && r.params.get('active') === 'true').flush(lista);
    } else {
      http.expectOne((r) => r.url === '/api/routines' && !r.params.has('active')).flush(lista);
    }
    await estabilizar();
  }

  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  interface Interno {
    formulario(): {
      clientMemberId: number | null; name: string; category: string; level: string;
      estimatedMinutes: number | null; estimatedKcal: number | null;
      exercises: { exerciseId: number | null; series: number | null; repetitions: number | null; restSeconds: number | null }[];
    } | null;
    guardar(): void; agregarFila(): void; quitarFila(i: number): void; mover(i: number, d: -1 | 1): void;
    filtrar(c: string, v: unknown): void;
  }
  const interno = () => fixture.componentInstance as unknown as Interno;
  const clic = (texto: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === texto)!.click();

  it('el Entrenador ve sus rutinas con alumno, categoría y acciones solo en las activas', async () => {
    await montar(['trainer']);
    const tarjetas = el().querySelectorAll('.nf-rutinas li');
    expect(tarjetas).toHaveLength(2);
    expect(tarjetas[0].textContent).toContain('Fuerza A');
    expect(tarjetas[0].textContent).toContain('Alumno: Luis Gómez');
    expect(tarjetas[0].textContent).toContain('Fuerza');
    expect(tarjetas[0].textContent).toContain('2 ejercicios');
    expect(tarjetas[0].textContent).toContain('45 min');
    expect(tarjetas[0].textContent).toContain('Editar');
    expect(tarjetas[1].textContent).toContain('Archivada');
    expect(tarjetas[1].textContent).not.toContain('Editar');
    expect(tarjetas[1].textContent).not.toContain('Archivar');
  });

  it('el Cliente solo consulta: sin botón de nueva rutina ni edición, y ve también las archivadas', async () => {
    await montar(['client']);
    expect(el().querySelector('.nf-cabecera button')).toBeNull();
    expect(el().textContent).toContain('Entrenador: Ana Ruiz');
    expect(el().textContent).toContain('Vieja');
    expect(el().textContent).not.toContain('Editar');
    expect(el().textContent).not.toContain('Archivar');
  });

  it('avisa cuando no hay rutinas', async () => {
    await montar(['client'], []);
    expect(el().querySelector('.nf-vacio')?.textContent).toContain('aún no te ha asignado');
  });

  it('muestra los ejercicios de una rutina al abrirla', async () => {
    await montar(['client']);
    clic('Ver ejercicios');
    http.expectOne({ method: 'GET', url: '/api/routines/1' }).flush(detalle);
    await estabilizar();
    const filas = el().querySelectorAll('.nf-ejercicios-rutina li');
    expect(filas).toHaveLength(2);
    expect(filas[0].textContent).toContain('Press banca');
    expect(filas[0].textContent).toContain('3 × 12 · descanso 60 s');
    expect(filas[0].querySelector('img')?.getAttribute('src')).toBe('/img/ejercicios/pecho.svg');
    clic('Ocultar ejercicios');
    await estabilizar();
    expect(el().querySelectorAll('.nf-ejercicios-rutina li')).toHaveLength(0);
  });

  it('valida alumno, nombre y filas antes de enviar', async () => {
    await montar(['trainer']);
    clic('Nueva rutina');
    await estabilizar();
    interno().guardar();
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('Selecciona el alumno');

    interno().formulario()!.clientMemberId = 20;
    interno().guardar();
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('El nombre es obligatorio');

    interno().formulario()!.name = 'Nueva';
    interno().guardar();
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('Ejercicio 1: elige el ejercicio');

    interno().formulario()!.exercises[0].exerciseId = 100;
    interno().formulario()!.exercises[0].series = 0;
    interno().guardar();
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('las series van de 1 a 50');
    http.expectNone({ method: 'POST', url: '/api/routines' });
  });

  it('crea una rutina con los ejercicios en el orden de las filas', async () => {
    await montar(['trainer']);
    clic('Nueva rutina');
    await estabilizar();
    const f = interno().formulario()!;
    f.clientMemberId = 20;
    f.name = '  Piernas  ';
    f.category = 'strength';
    f.estimatedMinutes = 50;
    f.exercises[0].exerciseId = 101;
    interno().agregarFila();
    interno().formulario()!.exercises[1].exerciseId = 100;
    interno().mover(1, -1);
    interno().guardar();
    const post = http.expectOne({ method: 'POST', url: '/api/routines' });
    expect(post.request.body).toEqual({
      clientMemberId: 20, name: 'Piernas', category: 'strength', level: null, estimatedMinutes: 50,
      estimatedKcal: null,
      exercises: [
        { exerciseId: 100, order: 1, series: 3, repetitions: 10, restSeconds: 60 },
        { exerciseId: 101, order: 2, series: 3, repetitions: 10, restSeconds: 60 }
      ]
    });
    post.flush(detalle, { status: 201, statusText: 'Created' });
    http.expectOne((r) => r.url === '/api/routines' && r.params.get('active') === 'true').flush([resumen]);
    await estabilizar();
    expect(el().querySelector('form')).toBeNull();
    expect(el().querySelectorAll('.nf-rutinas li')).toHaveLength(1);
  });

  it('quita filas y muestra el error del servidor sobre un ejercicio', async () => {
    await montar(['trainer']);
    clic('Nueva rutina');
    await estabilizar();
    interno().agregarFila();
    expect(interno().formulario()!.exercises).toHaveLength(2);
    interno().quitarFila(1);
    expect(interno().formulario()!.exercises).toHaveLength(1);
    const f = interno().formulario()!;
    f.clientMemberId = 20;
    f.name = 'X';
    f.exercises[0].exerciseId = 100;
    interno().guardar();
    http.expectOne({ method: 'POST', url: '/api/routines' }).flush(
      { code: 'VALIDATION_ERROR', field: 'exercises[0].exerciseId', message: 'El ejercicio no existe en el catálogo o está inactivo' },
      { status: 400, statusText: 'Bad Request' }
    );
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('no existe en el catálogo');
  });

  it('muestra bajo su campo el error del servidor sobre el alumno', async () => {
    await montar(['trainer']);
    clic('Nueva rutina');
    await estabilizar();
    const f = interno().formulario()!;
    f.clientMemberId = 20;
    f.name = 'X';
    f.exercises[0].exerciseId = 100;
    interno().guardar();
    http.expectOne({ method: 'POST', url: '/api/routines' }).flush(
      { code: 'VALIDATION_ERROR', field: 'clientMemberId', message: 'El cliente no es alumno activo de este entrenador' },
      { status: 400, statusText: 'Bad Request' }
    );
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('no es alumno activo');
  });

  it('edita una rutina: carga el detalle y la reemplaza con PUT', async () => {
    await montar(['trainer']);
    clic('Editar');
    http.expectOne({ method: 'GET', url: '/api/routines/1' }).flush(detalle);
    await estabilizar();
    expect(el().querySelector('form h2')?.textContent).toContain('Editar rutina');
    expect(interno().formulario()!.exercises).toHaveLength(2);
    expect(el().querySelector<HTMLSelectElement>('select[name="alumno"]')!.disabled).toBe(true);
    interno().formulario()!.name = 'Fuerza B';
    interno().guardar();
    const put = http.expectOne({ method: 'PUT', url: '/api/routines/1' });
    expect(put.request.body.name).toBe('Fuerza B');
    expect(put.request.body.clientMemberId).toBeUndefined();
    expect(put.request.body.exercises).toHaveLength(2);
    put.flush(detalle);
    http.expectOne((r) => r.url === '/api/routines' && r.params.get('active') === 'true').flush([resumen]);
    await estabilizar();
    expect(el().querySelector('form')).toBeNull();
  });

  it('archiva solo tras confirmar', async () => {
    await montar(['trainer']);
    clic('Archivar');
    await estabilizar();
    expect(el().querySelector('[role="alertdialog"]')?.textContent).toContain('solo lectura');
    http.expectNone({ method: 'PATCH', url: '/api/routines/1/archive' });
    clic('Confirmar');
    http.expectOne({ method: 'PATCH', url: '/api/routines/1/archive' }).flush({ ...detalle, active: false });
    http.expectOne((r) => r.url === '/api/routines' && r.params.get('active') === 'true').flush([]);
    await estabilizar();
    expect(el().querySelector('.nf-vacio')).not.toBeNull();
  });

  it('cancelar el archivado no envía nada', async () => {
    await montar(['trainer']);
    clic('Archivar');
    await estabilizar();
    clic('Cancelar');
    await estabilizar();
    expect(el().querySelector('[role="alertdialog"]')).toBeNull();
  });

  it('los filtros piden al servidor alumno, categoría y estado', async () => {
    await montar(['trainer']);
    interno().filtrar('estado', 'archivadas');
    http.expectOne((r) => r.url === '/api/routines' && r.params.get('active') === 'false').flush([archivada]);
    interno().filtrar('alumno', 20);
    http.expectOne((r) => r.params.get('clientMemberId') === '20' && r.params.get('active') === 'false').flush([]);
    interno().filtrar('categoria', 'cardio');
    http.expectOne((r) => r.params.get('category') === 'cardio' && r.params.get('clientMemberId') === '20').flush([]);
    interno().filtrar('estado', 'todas');
    http.expectOne((r) => r.url === '/api/routines' && !r.params.has('active')).flush([]);
  });
});
