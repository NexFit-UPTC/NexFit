import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ProgresoComponent } from './progreso.component';

const progreso = {
  today: '2026-11-07', streak: 3, weeklyGoal: 4, weekCount: 2, weekStart: '2026-11-02', weekEnd: '2026-11-08',
  totalWorkouts: 12
};
const rutina = {
  id: 5, trainerMemberId: 10, trainerName: 'Ana Ruiz', clientMemberId: 20, clientName: 'Luis', name: 'Fuerza A',
  category: 'strength', level: null, estimatedMinutes: null, estimatedKcal: null, createdAt: '2026-10-01T00:00:00Z',
  active: true, exerciseCount: 2
};
const detalle = {
  ...rutina,
  exercises: [
    { id: 51, exerciseId: 100, exerciseName: 'Press banca', muscleGroup: 'Pecho', imageUrl: null, order: 1, series: 3,
      repetitions: 12, restSeconds: 60 },
    { id: 52, exerciseId: 101, exerciseName: 'Sentadilla', muscleGroup: 'Piernas', imageUrl: null, order: 2, series: 4,
      repetitions: 8, restSeconds: 90 }
  ]
};
const historial = [{ id: 1, routineId: 5, routineName: 'Fuerza A', performedOn: '2026-11-06', completedCount: 1, totalCount: 2 }];

describe('ProgresoComponent', () => {
  let fixture: ComponentFixture<ProgresoComponent>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  async function estabilizar() {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function montar(rutinas: object[] = [rutina], p: object = progreso, hist: object[] = historial) {
    document.cookie = 'XSRF-TOKEN=abc; path=/';
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProgresoComponent);
    fixture.detectChanges();
    http.expectOne({ method: 'GET', url: '/api/my-progress' }).flush(p);
    http.expectOne({ method: 'GET', url: '/api/workouts' }).flush(hist);
    http.expectOne((r) => r.url === '/api/routines' && r.params.get('active') === 'true').flush(rutinas);
    await estabilizar();
  }

  afterEach(() => {
    http.verify();
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  interface Interno {
    elegirRutina(id: number | null): void;
    marcar(id: number, v: boolean): void;
    registrar(): void;
    fecha: string;
  }
  const interno = () => fixture.componentInstance as unknown as Interno;
  const clic = (texto: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.trim() === texto)!.click();

  async function abrirRutina() {
    interno().elegirRutina(5);
    http.expectOne({ method: 'GET', url: '/api/routines/5' }).flush(detalle);
    await estabilizar();
  }

  it('muestra la racha, el avance de la semana frente a la meta y el historial', async () => {
    await montar();
    expect(el().querySelector('[data-testid="racha"]')?.textContent?.trim()).toBe('3');
    expect(el().querySelector('[data-testid="semana"]')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('2 / 4');
    expect(el().querySelector('progress')?.getAttribute('ng-reflect-value') ?? el().querySelector('progress')?.value).toBeTruthy();
    expect(el().querySelector<HTMLProgressElement>('progress')!.value).toBe(50);
    expect(el().textContent).toContain('06/11/2026');
    expect(el().textContent).toContain('1 de 2');
  });

  it('invita a empezar cuando la racha es cero y no hay meta', async () => {
    await montar([], { ...progreso, streak: 0, weeklyGoal: null, weekCount: 0, totalWorkouts: 0 }, []);
    expect(el().textContent).toContain('Entrena hoy para empezar una racha');
    expect(el().querySelector('progress')).toBeNull();
    expect(el().textContent).toContain('Todavía no has registrado entrenamientos');
    expect(el().textContent).toContain('te asigne una rutina');
  });

  it('fija y quita la meta semanal', async () => {
    await montar();
    clic('6');
    const put = http.expectOne({ method: 'PUT', url: '/api/my-goal' });
    expect(put.request.body).toEqual({ weeklyGoal: 6 });
    put.flush({ ...progreso, weeklyGoal: 6 });
    await estabilizar();
    expect(el().querySelector('[data-testid="semana"]')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('2 / 6');
    clic('Quitar meta');
    const quitar = http.expectOne({ method: 'PUT', url: '/api/my-goal' });
    expect(quitar.request.body).toEqual({ weeklyGoal: null });
    quitar.flush({ ...progreso, weeklyGoal: null });
    await estabilizar();
    expect(el().querySelector('progress')).toBeNull();
  });

  it('registra un entrenamiento con los ejercicios marcados y actualiza racha e historial', async () => {
    await montar();
    await abrirRutina();
    expect(el().querySelectorAll('.nf-checklist li')).toHaveLength(2);
    interno().marcar(52, true);
    interno().registrar();
    const post = http.expectOne({ method: 'POST', url: '/api/workouts' });
    expect(post.request.body).toEqual({ routineId: 5, completedExerciseIds: [52] });
    post.flush({ id: 9 }, { status: 201, statusText: 'Created' });
    http.expectOne({ method: 'GET', url: '/api/my-progress' }).flush({ ...progreso, streak: 4 });
    http.expectOne({ method: 'GET', url: '/api/workouts' }).flush(historial);
    await estabilizar();
    expect(el().querySelector('[role="status"]')?.textContent).toContain('Entrenamiento registrado');
    expect(el().querySelector('[data-testid="racha"]')?.textContent?.trim()).toBe('4');
  });

  it('"Marcar todos" selecciona cada ejercicio', async () => {
    await montar();
    await abrirRutina();
    clic('Marcar todos');
    await estabilizar();
    expect(Array.from(el().querySelectorAll<HTMLInputElement>('.nf-checklist input')).every((i) => i.checked)).toBe(true);
    interno().registrar();
    expect(http.expectOne({ method: 'POST', url: '/api/workouts' }).request.body.completedExerciseIds).toEqual([51, 52]);
  });

  it('valida rutina, ejercicios y fecha antes de enviar', async () => {
    await montar();
    interno().registrar();
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('Selecciona la rutina');
    await abrirRutina();
    interno().registrar();
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('Marca al menos un ejercicio');
    interno().marcar(51, true);
    interno().fecha = '2026-11-08';
    interno().registrar();
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('fecha futura');
    http.expectNone({ method: 'POST', url: '/api/workouts' });
  });

  it('muestra el error del servidor, por ejemplo si la rutina se archivó', async () => {
    await montar();
    await abrirRutina();
    interno().marcar(51, true);
    interno().registrar();
    http.expectOne({ method: 'POST', url: '/api/workouts' }).flush(
      { code: 'ROUTINE_ARCHIVED', field: 'routineId', message: 'La rutina está archivada: ya no admite entrenamientos' },
      { status: 409, statusText: 'Conflict' }
    );
    await estabilizar();
    expect(el().querySelector('.nf-error-campo')?.textContent).toContain('archivada');
  });
});
