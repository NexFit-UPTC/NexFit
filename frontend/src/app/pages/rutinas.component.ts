import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AsignacionesService, Asignacion } from '../core/asignaciones.service';
import { Ejercicio, EjerciciosService } from '../core/ejercicios.service';
import { errorDeApi } from '../core/establecimientos.service';
import { imagenEjercicio } from '../core/imagenes';
import {
  CategoriaRutina,
  ETIQUETA_CATEGORIA,
  ETIQUETA_NIVEL,
  NivelRutina,
  ResumenRutina,
  Rutina,
  RutinasService
} from '../core/rutinas.service';
import { SessionService } from '../core/session.service';

interface FilaEjercicio {
  exerciseId: number | null;
  series: number | null;
  repetitions: number | null;
  restSeconds: number | null;
}

interface FormularioRutina {
  clientMemberId: number | null;
  name: string;
  category: '' | CategoriaRutina;
  level: '' | NivelRutina;
  estimatedMinutes: number | null;
  estimatedKcal: number | null;
  exercises: FilaEjercicio[];
}

type EstadoFiltro = 'activas' | 'archivadas' | 'todas';

const CAMPOS_SIMPLES = ['clientMemberId', 'name', 'category', 'level', 'estimatedMinutes', 'estimatedKcal'];

/** Rutinas (US-42 a US-44, US-52): el Entrenador las arma, edita y archiva; el Cliente consulta las suyas. */
@Component({
  selector: 'app-rutinas',
  imports: [FormsModule, DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>{{ esEntrenador() ? 'Rutinas de mis alumnos' : 'Mis rutinas' }}</h1>
        @if (esEntrenador()) {
          <button type="button" class="nf-boton" (click)="nueva()">Nueva rutina</button>
        }
      </div>

      @if (formulario(); as f) {
        <form class="nf-tarjeta" (ngSubmit)="guardar()" novalidate>
          <h2>{{ editandoId() === null ? 'Nueva rutina' : 'Editar rutina' }}</h2>
          <div class="nf-rejilla">
            <label>
              Alumno
              <select name="alumno" [(ngModel)]="f.clientMemberId" [disabled]="editandoId() !== null">
                <option [ngValue]="null">Selecciona…</option>
                @for (a of alumnos(); track a.clientMemberId) {
                  <option [ngValue]="a.clientMemberId">{{ a.clientName }}</option>
                }
              </select>
            </label>
            @if (errores()['clientMemberId']) {
              <p class="nf-error-campo" role="alert">{{ errores()['clientMemberId'] }}</p>
            }
            <label>
              Nombre de la rutina
              <input name="nombre" [(ngModel)]="f.name" maxlength="150" />
            </label>
            @if (errores()['name']) {
              <p class="nf-error-campo" role="alert">{{ errores()['name'] }}</p>
            }
            <label>
              Categoría
              <select name="categoria" [(ngModel)]="f.category">
                <option value="">Sin categoría</option>
                @for (c of categorias; track c.valor) {
                  <option [value]="c.valor">{{ c.etiqueta }}</option>
                }
              </select>
            </label>
            <label>
              Nivel
              <select name="nivel" [(ngModel)]="f.level">
                <option value="">Sin nivel</option>
                @for (n of niveles; track n.valor) {
                  <option [value]="n.valor">{{ n.etiqueta }}</option>
                }
              </select>
            </label>
            <label>
              Minutos estimados
              <input name="minutos" type="number" min="1" max="1440" [(ngModel)]="f.estimatedMinutes" />
            </label>
            @if (errores()['estimatedMinutes']) {
              <p class="nf-error-campo" role="alert">{{ errores()['estimatedMinutes'] }}</p>
            }
            <label>
              Calorías estimadas (kcal)
              <input name="kcal" type="number" min="1" max="20000" [(ngModel)]="f.estimatedKcal" />
            </label>
            @if (errores()['estimatedKcal']) {
              <p class="nf-error-campo" role="alert">{{ errores()['estimatedKcal'] }}</p>
            }
          </div>

          <h3>Ejercicios (en orden de ejecución)</h3>
          <ol class="nf-filas-ejercicio">
            @for (fila of f.exercises; track $index) {
              <li class="nf-fila-ejercicio">
                <label>
                  Ejercicio
                  <select [name]="'ejercicio' + $index" [(ngModel)]="fila.exerciseId">
                    <option [ngValue]="null">Selecciona…</option>
                    @for (e of ejercicios(); track e.id) {
                      <option [ngValue]="e.id">{{ e.name }}{{ e.muscleGroup ? ' · ' + e.muscleGroup : '' }}</option>
                    }
                  </select>
                </label>
                <label>
                  Series
                  <input [name]="'series' + $index" type="number" min="1" max="50" [(ngModel)]="fila.series" />
                </label>
                <label>
                  Repeticiones
                  <input [name]="'reps' + $index" type="number" min="1" max="999" [(ngModel)]="fila.repetitions" />
                </label>
                <label>
                  Descanso (s)
                  <input [name]="'descanso' + $index" type="number" min="0" max="3600" [(ngModel)]="fila.restSeconds" />
                </label>
                <div class="nf-acciones">
                  <button type="button" class="nf-boton-sec" [disabled]="$first" (click)="mover($index, -1)"
                    [attr.aria-label]="'Subir el ejercicio ' + ($index + 1)">↑</button>
                  <button type="button" class="nf-boton-sec" [disabled]="$last" (click)="mover($index, 1)"
                    [attr.aria-label]="'Bajar el ejercicio ' + ($index + 1)">↓</button>
                  <button type="button" class="nf-boton-sec" (click)="quitarFila($index)"
                    [attr.aria-label]="'Quitar el ejercicio ' + ($index + 1)">Quitar</button>
                </div>
              </li>
            }
          </ol>
          <div class="nf-acciones">
            <button type="button" class="nf-boton-sec" (click)="agregarFila()">Agregar ejercicio</button>
          </div>
          @if (errorGeneral()) {
            <p class="nf-error-campo" role="alert">{{ errorGeneral() }}</p>
          }
          <div class="nf-acciones">
            <button type="submit" class="nf-boton" [disabled]="guardando()">Guardar</button>
            <button type="button" class="nf-boton-sec" (click)="cancelar()">Cancelar</button>
          </div>
        </form>
      }

      <div class="nf-filtros">
        @if (esEntrenador()) {
          <label>
            Alumno
            <select name="filtroAlumno" [ngModel]="filtroAlumno()" (ngModelChange)="filtrar('alumno', $event)">
              <option [ngValue]="null">Todos</option>
              @for (a of alumnos(); track a.clientMemberId) {
                <option [ngValue]="a.clientMemberId">{{ a.clientName }}</option>
              }
            </select>
          </label>
        }
        <label>
          Categoría
          <select name="filtroCategoria" [ngModel]="filtroCategoria()" (ngModelChange)="filtrar('categoria', $event)">
            <option value="">Todas</option>
            @for (c of categorias; track c.valor) {
              <option [value]="c.valor">{{ c.etiqueta }}</option>
            }
          </select>
        </label>
        <label>
          Estado
          <select name="filtroEstado" [ngModel]="filtroEstado()" (ngModelChange)="filtrar('estado', $event)">
            <option value="activas">Activas</option>
            <option value="archivadas">Archivadas</option>
            <option value="todas">Todas</option>
          </select>
        </label>
      </div>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (archivoPendiente(); as r) {
        <div class="nf-alerta" role="alertdialog">
          <p>
            Archivar «{{ r.name }}» la deja de solo lectura: ya no se podrá modificar, aunque {{ r.clientName }}
            seguirá viéndola.
          </p>
          <div class="nf-acciones">
            <button type="button" class="nf-boton" (click)="confirmarArchivo()">Confirmar</button>
            <button type="button" class="nf-boton-sec" (click)="archivoPendiente.set(null)">Cancelar</button>
          </div>
        </div>
      }

      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (rutinas().length === 0) {
        <p class="nf-vacio">
          {{ esEntrenador() ? 'No hay rutinas con esos filtros.' : 'Tu entrenador aún no te ha asignado rutinas.' }}
        </p>
      } @else {
        <ul class="nf-rutinas">
          @for (r of rutinas(); track r.id) {
            <li class="nf-tarjeta">
              <h2>{{ r.name }}</h2>
              <p class="nf-ayuda">
                {{ esEntrenador() ? 'Alumno: ' + r.clientName : 'Entrenador: ' + r.trainerName }}
                · creada el {{ r.createdAt | date: 'dd/MM/yyyy' }}
              </p>
              <p>
                @if (r.category) {
                  <span class="nf-insignia">{{ etiquetaCategoria(r.category) }}</span>
                }
                @if (r.level) {
                  <span class="nf-insignia">{{ etiquetaNivel(r.level) }}</span>
                }
                <span class="nf-insignia" [class.ok]="r.active">{{ r.active ? 'Activa' : 'Archivada' }}</span>
              </p>
              <p class="nf-ayuda">
                {{ r.exerciseCount }} {{ r.exerciseCount === 1 ? 'ejercicio' : 'ejercicios' }}
                @if (r.estimatedMinutes) {
                  · {{ r.estimatedMinutes }} min
                }
                @if (r.estimatedKcal) {
                  · {{ r.estimatedKcal }} kcal
                }
              </p>
              <div class="nf-acciones">
                <button type="button" class="nf-boton-sec" (click)="alternarDetalle(r)">
                  {{ abierta()?.id === r.id ? 'Ocultar ejercicios' : 'Ver ejercicios' }}
                </button>
                @if (esEntrenador() && r.active) {
                  <button type="button" class="nf-boton-sec" (click)="editar(r)">Editar</button>
                  <button type="button" class="nf-boton-sec" (click)="archivoPendiente.set(r)">Archivar</button>
                }
              </div>
              @if (abierta(); as d) {
                @if (d.id === r.id) {
                  <ol class="nf-ejercicios-rutina">
                    @for (e of d.exercises; track e.id) {
                      <li>
                        <img [src]="imagen(e)" [alt]="e.exerciseName" loading="lazy" />
                        <div>
                          <strong>{{ e.exerciseName }}</strong>
                          <span class="nf-ayuda">
                            {{ e.series }} × {{ e.repetitions }} · descanso {{ e.restSeconds }} s
                          </span>
                        </div>
                      </li>
                    }
                  </ol>
                }
              }
            </li>
          }
        </ul>
      }
    </section>
  `
})
export class RutinasComponent implements OnInit {
  private readonly api = inject(RutinasService);
  private readonly ejerciciosApi = inject(EjerciciosService);
  private readonly asignacionesApi = inject(AsignacionesService);
  private readonly sesion = inject(SessionService);

  protected readonly categorias = (Object.keys(ETIQUETA_CATEGORIA) as CategoriaRutina[]).map((valor) => ({
    valor,
    etiqueta: ETIQUETA_CATEGORIA[valor]
  }));
  protected readonly niveles = (Object.keys(ETIQUETA_NIVEL) as NivelRutina[]).map((valor) => ({
    valor,
    etiqueta: ETIQUETA_NIVEL[valor]
  }));
  protected readonly esEntrenador = computed(() => this.sesion.roles().includes('trainer'));
  protected readonly rutinas = signal<ResumenRutina[]>([]);
  protected readonly alumnos = signal<Asignacion[]>([]);
  protected readonly ejercicios = signal<Ejercicio[]>([]);
  protected readonly abierta = signal<Rutina | null>(null);
  protected readonly filtroAlumno = signal<number | null>(null);
  protected readonly filtroCategoria = signal<'' | CategoriaRutina>('');
  protected readonly filtroEstado = signal<EstadoFiltro>('activas');
  protected readonly cargando = signal(true);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly formulario = signal<FormularioRutina | null>(null);
  protected readonly editandoId = signal<number | null>(null);
  protected readonly archivoPendiente = signal<ResumenRutina | null>(null);

  protected imagen = imagenEjercicio;

  protected etiquetaCategoria(c: CategoriaRutina): string {
    return ETIQUETA_CATEGORIA[c];
  }

  protected etiquetaNivel(n: NivelRutina): string {
    return ETIQUETA_NIVEL[n];
  }

  ngOnInit() {
    if (this.esEntrenador()) {
      this.ejerciciosApi.listar({ active: true }).subscribe((e) => this.ejercicios.set(e));
      this.asignacionesApi.listar({ active: true }).subscribe((a) => this.alumnos.set(a));
    } else {
      this.filtroEstado.set('todas');
    }
    this.recargar();
  }

  protected filtrar(cual: 'alumno' | 'categoria' | 'estado', valor: unknown) {
    if (cual === 'alumno') this.filtroAlumno.set(valor as number | null);
    else if (cual === 'categoria') this.filtroCategoria.set(valor as '' | CategoriaRutina);
    else this.filtroEstado.set(valor as EstadoFiltro);
    this.recargar();
  }

  protected alternarDetalle(r: ResumenRutina) {
    if (this.abierta()?.id === r.id) {
      this.abierta.set(null);
      return;
    }
    this.api.detalle(r.id).subscribe({
      next: (d) => this.abierta.set(d),
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  protected nueva() {
    this.editandoId.set(null);
    this.reiniciarErrores();
    this.formulario.set({
      clientMemberId: this.filtroAlumno(),
      name: '',
      category: '',
      level: '',
      estimatedMinutes: null,
      estimatedKcal: null,
      exercises: [this.filaVacia()]
    });
  }

  protected editar(r: ResumenRutina) {
    this.error.set(null);
    this.api.detalle(r.id).subscribe({
      next: (d) => {
        this.editandoId.set(d.id);
        this.reiniciarErrores();
        this.formulario.set({
          clientMemberId: d.clientMemberId,
          name: d.name,
          category: d.category ?? '',
          level: d.level ?? '',
          estimatedMinutes: d.estimatedMinutes,
          estimatedKcal: d.estimatedKcal,
          exercises: d.exercises.map((e) => ({
            exerciseId: e.exerciseId,
            series: e.series,
            repetitions: e.repetitions,
            restSeconds: e.restSeconds
          }))
        });
      },
      error: (e: HttpErrorResponse) => this.error.set(errorDeApi(e).mensaje)
    });
  }

  protected cancelar() {
    this.formulario.set(null);
  }

  protected agregarFila() {
    this.formulario.update((f) => (f ? { ...f, exercises: [...f.exercises, this.filaVacia()] } : f));
  }

  protected quitarFila(i: number) {
    this.formulario.update((f) => (f ? { ...f, exercises: f.exercises.filter((_, k) => k !== i) } : f));
  }

  protected mover(i: number, delta: -1 | 1) {
    this.formulario.update((f) => {
      const j = i + delta;
      if (!f || j < 0 || j >= f.exercises.length) return f;
      const filas = [...f.exercises];
      [filas[i], filas[j]] = [filas[j], filas[i]];
      return { ...f, exercises: filas };
    });
  }

  protected guardar() {
    const f = this.formulario();
    if (!f) return;
    this.reiniciarErrores();
    const id = this.editandoId();
    if (id === null && f.clientMemberId === null) {
      this.errores.set({ clientMemberId: 'Selecciona el alumno.' });
      return;
    }
    if (!f.name.trim()) {
      this.errores.set({ name: 'El nombre es obligatorio.' });
      return;
    }
    const problema = this.problemaEnFilas(f.exercises);
    if (problema) {
      this.errorGeneral.set(problema);
      return;
    }
    const datos = {
      name: f.name.trim(),
      category: f.category || null,
      level: f.level || null,
      estimatedMinutes: f.estimatedMinutes || null,
      estimatedKcal: f.estimatedKcal || null,
      exercises: f.exercises.map((e, i) => ({
        exerciseId: e.exerciseId as number,
        order: i + 1,
        series: e.series as number,
        repetitions: e.repetitions as number,
        restSeconds: e.restSeconds as number
      }))
    };
    this.guardando.set(true);
    const peticion =
      id === null ? this.api.crear(f.clientMemberId as number, datos) : this.api.editar(id, datos);
    peticion.subscribe({
      next: () => {
        this.guardando.set(false);
        this.formulario.set(null);
        this.abierta.set(null);
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        const { campo, mensaje } = errorDeApi(e);
        if (campo && CAMPOS_SIMPLES.includes(campo)) this.errores.set({ [campo]: mensaje });
        else this.errorGeneral.set(mensaje);
      }
    });
  }

  protected confirmarArchivo() {
    const r = this.archivoPendiente();
    this.archivoPendiente.set(null);
    if (!r) return;
    this.error.set(null);
    this.api.archivar(r.id).subscribe({
      next: () => {
        this.abierta.set(null);
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.error.set(errorDeApi(e).mensaje);
        this.recargar();
      }
    });
  }

  private filaVacia(): FilaEjercicio {
    return { exerciseId: null, series: 3, repetitions: 10, restSeconds: 60 };
  }

  private reiniciarErrores() {
    this.errores.set({});
    this.errorGeneral.set(null);
  }

  private problemaEnFilas(filas: FilaEjercicio[]): string | null {
    if (filas.length === 0) return 'Agrega al menos un ejercicio.';
    for (const [i, fila] of filas.entries()) {
      const n = `Ejercicio ${i + 1}: `;
      if (fila.exerciseId === null) return n + 'elige el ejercicio.';
      if (!this.enRango(fila.series, 1, 50)) return n + 'las series van de 1 a 50.';
      if (!this.enRango(fila.repetitions, 1, 999)) return n + 'las repeticiones van de 1 a 999.';
      if (!this.enRango(fila.restSeconds, 0, 3600)) return n + 'el descanso va de 0 a 3600 segundos.';
    }
    return null;
  }

  private enRango(valor: number | null, min: number, max: number): boolean {
    return valor !== null && Number.isInteger(valor) && valor >= min && valor <= max;
  }

  private recargar() {
    this.cargando.set(true);
    const estado = this.filtroEstado();
    this.api
      .listar({
        clientMemberId: this.esEntrenador() ? (this.filtroAlumno() ?? undefined) : undefined,
        category: this.filtroCategoria() || undefined,
        active: estado === 'todas' ? undefined : estado === 'activas'
      })
      .subscribe({
        next: (lista) => {
          this.rutinas.set(lista);
          this.cargando.set(false);
        },
        error: (e: HttpErrorResponse) => {
          this.error.set(errorDeApi(e).mensaje);
          this.cargando.set(false);
        }
      });
  }
}
