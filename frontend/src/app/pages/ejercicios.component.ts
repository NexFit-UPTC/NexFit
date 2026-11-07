import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Ejercicio, EjerciciosService } from '../core/ejercicios.service';
import { errorDeApi } from '../core/establecimientos.service';
import { imagenEjercicio } from '../core/imagenes';
import { SessionService } from '../core/session.service';

interface FormularioEjercicio {
  name: string;
  muscleGroup: string;
  description: string;
  imageUrl: string;
}

const GRUPOS = ['Pecho', 'Espalda', 'Hombros', 'Brazos', 'Piernas', 'Core', 'Cardio'];

/**
 * Catálogo de ejercicios (US-41): el Master gestiona el global; el Administrador y el Entrenador crean los propios
 * del gimnasio y el Administrador los modifica; el resto solo consulta.
 */
@Component({
  selector: 'app-ejercicios',
  imports: [FormsModule],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Catálogo de ejercicios</h1>
        @if (puedeCrear()) {
          <button type="button" class="nf-boton" (click)="nuevo()">
            {{ esMaster() ? 'Crear ejercicio global' : 'Crear ejercicio propio' }}
          </button>
        }
      </div>

      @if (formulario(); as f) {
        <form class="nf-tarjeta" (ngSubmit)="guardar()" novalidate>
          <h2>{{ editandoId() === null ? 'Nuevo ejercicio' : 'Editar ejercicio' }}</h2>
          <div class="nf-rejilla">
            <label>
              Nombre
              <input name="nombre" [(ngModel)]="f.name" />
            </label>
            @if (errores()['name']) {
              <p class="nf-error-campo" role="alert">{{ errores()['name'] }}</p>
            }
            <label>
              Grupo muscular
              <input name="grupo" list="grupos-musculares" [(ngModel)]="f.muscleGroup" />
              <datalist id="grupos-musculares">
                @for (g of grupos; track g) {
                  <option [value]="g"></option>
                }
              </datalist>
            </label>
            <label>
              Descripción
              <textarea name="descripcion" rows="3" [(ngModel)]="f.description"></textarea>
            </label>
            @if (errores()['description']) {
              <p class="nf-error-campo" role="alert">{{ errores()['description'] }}</p>
            }
            <label>
              Imagen (dirección https://…, opcional)
              <input name="imagen" [(ngModel)]="f.imageUrl" />
            </label>
            <p class="nf-ayuda">
              Usa la ruta de un archivo del proyecto (por ejemplo /img/ejemplo.jpg) o una dirección https://.
            </p>
            @if (f.imageUrl.trim()) {
              <img class="nf-vista-previa" [src]="f.imageUrl.trim()" alt="Vista previa de la imagen" />
            }
            @if (errores()['imageUrl']) {
              <p class="nf-error-campo" role="alert">{{ errores()['imageUrl'] }}</p>
            }
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
        <label>
          Buscar por nombre
          <input name="busqueda" [ngModel]="q()" (ngModelChange)="filtrar('q', $event)" />
        </label>
        <label>
          Grupo muscular
          <select name="filtroGrupo" [ngModel]="grupo()" (ngModelChange)="filtrar('grupo', $event)">
            <option value="">Todos</option>
            @for (g of grupos; track g) {
              <option [value]="g">{{ g }}</option>
            }
          </select>
        </label>
        @if (!esMaster()) {
          <label>
            Origen
            <select name="filtroOrigen" [ngModel]="ambito()" (ngModelChange)="filtrar('ambito', $event)">
              <option value="all">Todos</option>
              <option value="global">Globales</option>
              <option value="own">Propios del gimnasio</option>
            </select>
          </label>
        }
      </div>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (ejercicios().length === 0) {
        <p class="nf-vacio">No hay ejercicios con esos filtros.</p>
      } @else {
        <ul class="nf-galeria">
          @for (e of ejercicios(); track e.id) {
            <li class="nf-media">
              <img [src]="imagen(e)" [alt]="e.name" loading="lazy" />
              <div class="nf-media-cuerpo">
                <h2>{{ e.name }}</h2>
                <p>
                  @if (e.muscleGroup) {
                    <span class="nf-insignia">{{ e.muscleGroup }}</span>
                  }
                  <span class="nf-insignia" [class.ok]="e.scope === 'own'">
                    {{ e.scope === 'global' ? 'Global' : 'Propio' }}
                  </span>
                  @if (!e.active) {
                    <span class="nf-insignia alerta">Inactivo</span>
                  }
                </p>
                @if (e.description) {
                  <p class="nf-ayuda">{{ e.description }}</p>
                }
                @if (puedeEditar(e) || esMaster()) {
                  <div class="nf-acciones">
                    @if (puedeEditar(e)) {
                      <button type="button" class="nf-boton-sec" (click)="editar(e)">Editar</button>
                    }
                    @if (esMaster()) {
                      <button type="button" class="nf-boton-sec" (click)="alternar(e)">
                        {{ e.active ? 'Desactivar' : 'Activar' }}
                      </button>
                    }
                  </div>
                }
              </div>
            </li>
          }
        </ul>
      }
    </section>
  `
})
export class EjerciciosComponent implements OnInit {
  private readonly api = inject(EjerciciosService);
  private readonly sesion = inject(SessionService);

  protected readonly grupos = GRUPOS;
  protected readonly esMaster = computed(() => this.sesion.esMaster());
  private readonly esAdmin = computed(() => this.sesion.roles().includes('admin'));
  protected readonly puedeCrear = computed(
    () => this.esMaster() || this.esAdmin() || this.sesion.roles().includes('trainer')
  );
  protected readonly ejercicios = signal<Ejercicio[]>([]);
  protected readonly q = signal('');
  protected readonly grupo = signal('');
  protected readonly ambito = signal<'all' | 'global' | 'own'>('all');
  protected readonly cargando = signal(true);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly formulario = signal<FormularioEjercicio | null>(null);
  protected readonly editandoId = signal<number | null>(null);

  protected imagen = imagenEjercicio;

  /** El Master modifica los globales; el Administrador, los propios. */
  protected puedeEditar(e: Ejercicio): boolean {
    return (this.esMaster() && e.scope === 'global') || (this.esAdmin() && e.scope === 'own');
  }

  ngOnInit() {
    this.recargar();
  }

  protected filtrar(cual: 'q' | 'grupo' | 'ambito', valor: unknown) {
    if (cual === 'q') this.q.set(valor as string);
    else if (cual === 'grupo') this.grupo.set(valor as string);
    else this.ambito.set(valor as 'all' | 'global' | 'own');
    this.recargar();
  }

  protected nuevo() {
    this.editandoId.set(null);
    this.errores.set({});
    this.errorGeneral.set(null);
    this.formulario.set({ name: '', muscleGroup: '', description: '', imageUrl: '' });
  }

  protected editar(e: Ejercicio) {
    this.editandoId.set(e.id);
    this.errores.set({});
    this.errorGeneral.set(null);
    this.formulario.set({
      name: e.name,
      muscleGroup: e.muscleGroup ?? '',
      description: e.description ?? '',
      imageUrl: e.imageUrl ?? ''
    });
  }

  protected cancelar() {
    this.formulario.set(null);
  }

  protected guardar() {
    const f = this.formulario();
    if (!f) return;
    this.errores.set({});
    this.errorGeneral.set(null);
    if (!f.name.trim()) {
      this.errores.set({ name: 'El nombre es obligatorio.' });
      return;
    }
    const datos = {
      name: f.name.trim(),
      muscleGroup: f.muscleGroup.trim() || null,
      description: f.description.trim() || null,
      imageUrl: f.imageUrl.trim() || null
    };
    const id = this.editandoId();
    this.guardando.set(true);
    (id === null ? this.api.crear(datos) : this.api.editar(id, datos)).subscribe({
      next: () => {
        this.guardando.set(false);
        this.formulario.set(null);
        this.recargar();
      },
      error: (e: HttpErrorResponse) => {
        this.guardando.set(false);
        const { campo, mensaje } = errorDeApi(e);
        if (campo) this.errores.set({ [campo]: mensaje });
        else this.errorGeneral.set(mensaje);
      }
    });
  }

  protected alternar(e: Ejercicio) {
    this.error.set(null);
    this.api.activar(e.id, !e.active).subscribe({
      next: () => this.recargar(),
      error: (err: HttpErrorResponse) => this.error.set(errorDeApi(err).mensaje)
    });
  }

  private recargar() {
    this.cargando.set(true);
    this.api
      .listar({
        q: this.q(),
        muscleGroup: this.grupo(),
        scope: this.esMaster() ? undefined : this.ambito(),
        active: undefined
      })
      .subscribe({
        next: (datos) => {
          this.ejercicios.set(datos);
          this.cargando.set(false);
        },
        error: () => {
          this.error.set('No se pudo cargar el catálogo.');
          this.cargando.set(false);
        }
      });
  }
}
