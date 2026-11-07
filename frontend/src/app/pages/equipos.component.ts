import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Equipo, EquiposService, EstadoEquipo, ETIQUETA_ESTADO_EQUIPO } from '../core/equipos.service';
import { errorDeApi } from '../core/establecimientos.service';
import { imagenEquipo } from '../core/imagenes';
import { Sede, SedesService } from '../core/sedes.service';
import { SessionService } from '../core/session.service';

interface FormularioEquipo {
  branchId: number | null;
  name: string;
  category: string;
  status: EstadoEquipo;
  acquisitionDate: string;
  imageUrl: string;
}

const CATEGORIAS = ['Cardio', 'Fuerza', 'Pesas libres', 'Funcional'];

/** Equipo de las sedes (US-12): el Administrador lo registra y mantiene; el Staff lo consulta. */
@Component({
  selector: 'app-equipos',
  imports: [FormsModule, DatePipe],
  template: `
    <section class="nf-pagina">
      <div class="nf-cabecera">
        <h1>Equipo de las sedes</h1>
        @if (puedeGestionar()) {
          <button type="button" class="nf-boton" (click)="nuevo()">Registrar equipo</button>
        }
      </div>

      @if (formulario(); as f) {
        <form class="nf-tarjeta" (ngSubmit)="guardar()" novalidate>
          <h2>{{ editandoId() === null ? 'Nuevo equipo' : 'Editar equipo' }}</h2>
          <div class="nf-rejilla">
            @if (editandoId() === null) {
              <label>
                Sede
                <select name="sede" [(ngModel)]="f.branchId">
                  <option [ngValue]="null">Selecciona…</option>
                  @for (s of sedesActivas(); track s.id) {
                    <option [ngValue]="s.id">{{ s.name }}</option>
                  }
                </select>
              </label>
              @if (errores()['branchId']) {
                <p class="nf-error-campo" role="alert">{{ errores()['branchId'] }}</p>
              }
            }
            <label>
              Nombre
              <input name="nombre" [(ngModel)]="f.name" />
            </label>
            @if (errores()['name']) {
              <p class="nf-error-campo" role="alert">{{ errores()['name'] }}</p>
            }
            <label>
              Categoría
              <input name="categoria" list="categorias-equipo" [(ngModel)]="f.category" />
              <datalist id="categorias-equipo">
                @for (c of categorias; track c) {
                  <option [value]="c"></option>
                }
              </datalist>
            </label>
            @if (editandoId() === null) {
              <label>
                Estado
                <select name="estadoInicial" [(ngModel)]="f.status">
                  @for (e of estados; track e.valor) {
                    <option [value]="e.valor">{{ e.etiqueta }}</option>
                  }
                </select>
              </label>
            }
            <label>
              Fecha de adquisición
              <input name="adquisicion" type="date" [(ngModel)]="f.acquisitionDate" />
            </label>
            @if (errores()['acquisitionDate']) {
              <p class="nf-error-campo" role="alert">{{ errores()['acquisitionDate'] }}</p>
            }
            <label>
              Imagen (dirección https://…, opcional)
              <input name="imagen" [(ngModel)]="f.imageUrl" />
            </label>
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
          Sede
          <select name="filtroSede" [ngModel]="filtroSede()" (ngModelChange)="filtrar('sede', $event)">
            <option [ngValue]="null">Todas</option>
            @for (s of sedes(); track s.id) {
              <option [ngValue]="s.id">{{ s.name }}</option>
            }
          </select>
        </label>
        <label>
          Estado
          <select name="filtroEstado" [ngModel]="filtroEstado()" (ngModelChange)="filtrar('estado', $event)">
            <option value="">Todos</option>
            @for (e of estados; track e.valor) {
              <option [value]="e.valor">{{ e.etiqueta }}</option>
            }
          </select>
        </label>
        <label>
          Categoría
          <input name="filtroCategoria" [ngModel]="filtroCategoria()" (ngModelChange)="filtrar('categoria', $event)" />
        </label>
      </div>

      @if (error()) {
        <p class="nf-alerta" role="alert">{{ error() }}</p>
      }
      @if (bajaPendiente(); as e) {
        <div class="nf-alerta" role="alertdialog">
          <p>
            Dar de baja «{{ e.name }}» es definitivo: después no podrá cambiar de estado.
          </p>
          <div class="nf-acciones">
            <button type="button" class="nf-boton" (click)="confirmarBaja()">Confirmar baja</button>
            <button type="button" class="nf-boton-sec" (click)="bajaPendiente.set(null)">Cancelar</button>
          </div>
        </div>
      }
      @if (cargando()) {
        <p class="nf-ayuda">Cargando…</p>
      } @else if (equipos().length === 0) {
        <p class="nf-vacio">No hay equipo registrado con esos filtros.</p>
      } @else {
        <ul class="nf-galeria">
          @for (e of equipos(); track e.id) {
            <li class="nf-media">
              <img [src]="imagen(e)" [alt]="e.name" loading="lazy" />
              <div class="nf-media-cuerpo">
                <h2>{{ e.name }}</h2>
                <p class="nf-ayuda">{{ e.branchName }}{{ e.category ? ' · ' + e.category : '' }}</p>
                <p>
                  <span class="nf-insignia" [class.ok]="e.status === 'available'" [class.alerta]="e.status !== 'available'">
                    {{ etiqueta(e.status) }}
                  </span>
                </p>
                @if (e.acquisitionDate) {
                  <p class="nf-ayuda">Adquirido el {{ e.acquisitionDate | date: 'dd/MM/yyyy' }}</p>
                }
                @if (puedeGestionar()) {
                  <div class="nf-acciones">
                    <button type="button" class="nf-boton-sec" (click)="editar(e)">Editar</button>
                    @if (e.status !== 'retired') {
                      <select [attr.aria-label]="'Estado de ' + e.name" (change)="cambiarEstado(e, $any($event.target))">
                        @for (s of estados; track s.valor) {
                          <option [value]="s.valor" [selected]="s.valor === e.status">{{ s.etiqueta }}</option>
                        }
                      </select>
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
export class EquiposComponent implements OnInit {
  private readonly api = inject(EquiposService);
  private readonly sedesApi = inject(SedesService);
  private readonly sesion = inject(SessionService);

  protected readonly categorias = CATEGORIAS;
  protected readonly estados = (Object.keys(ETIQUETA_ESTADO_EQUIPO) as EstadoEquipo[]).map((valor) => ({
    valor,
    etiqueta: ETIQUETA_ESTADO_EQUIPO[valor]
  }));
  protected readonly puedeGestionar = computed(() => this.sesion.roles().includes('admin'));
  protected readonly equipos = signal<Equipo[]>([]);
  protected readonly sedes = signal<Sede[]>([]);
  protected readonly sedesActivas = computed(() => this.sedes().filter((s) => s.active));
  protected readonly filtroSede = signal<number | null>(null);
  protected readonly filtroEstado = signal<'' | EstadoEquipo>('');
  protected readonly filtroCategoria = signal('');
  protected readonly cargando = signal(true);
  protected readonly guardando = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly errorGeneral = signal<string | null>(null);
  protected readonly errores = signal<Record<string, string>>({});
  protected readonly formulario = signal<FormularioEquipo | null>(null);
  protected readonly editandoId = signal<number | null>(null);
  protected readonly bajaPendiente = signal<Equipo | null>(null);

  protected imagen = imagenEquipo;

  protected etiqueta(estado: EstadoEquipo): string {
    return ETIQUETA_ESTADO_EQUIPO[estado];
  }

  ngOnInit() {
    this.sedesApi.listar().subscribe((s) => this.sedes.set(s));
    this.recargar();
  }

  protected filtrar(cual: 'sede' | 'estado' | 'categoria', valor: unknown) {
    if (cual === 'sede') this.filtroSede.set(valor as number | null);
    else if (cual === 'estado') this.filtroEstado.set(valor as '' | EstadoEquipo);
    else this.filtroCategoria.set(valor as string);
    this.recargar();
  }

  protected nuevo() {
    const activas = this.sedesActivas();
    this.editandoId.set(null);
    this.errores.set({});
    this.errorGeneral.set(null);
    this.formulario.set({
      branchId: activas.length === 1 ? activas[0].id : null,
      name: '',
      category: '',
      status: 'available',
      acquisitionDate: '',
      imageUrl: ''
    });
  }

  protected editar(e: Equipo) {
    this.editandoId.set(e.id);
    this.errores.set({});
    this.errorGeneral.set(null);
    this.formulario.set({
      branchId: e.branchId,
      name: e.name,
      category: e.category ?? '',
      status: e.status,
      acquisitionDate: e.acquisitionDate ?? '',
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
    const id = this.editandoId();
    if (id === null && f.branchId === null) {
      this.errores.set({ branchId: 'Selecciona la sede.' });
      return;
    }
    if (!f.name.trim()) {
      this.errores.set({ name: 'El nombre es obligatorio.' });
      return;
    }
    const datos = {
      name: f.name.trim(),
      category: f.category.trim() || null,
      acquisitionDate: f.acquisitionDate || null,
      imageUrl: f.imageUrl.trim() || null
    };
    this.guardando.set(true);
    const peticion =
      id === null ? this.api.crear(f.branchId as number, datos, f.status) : this.api.editar(id, datos);
    peticion.subscribe({
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

  protected cambiarEstado(e: Equipo, control: HTMLSelectElement) {
    const estado = control.value as EstadoEquipo;
    if (estado === e.status) return;
    if (estado === 'retired') {
      // Dar de baja es definitivo: se restablece el control y se pide confirmación antes de enviarlo.
      control.value = e.status;
      this.bajaPendiente.set(e);
      return;
    }
    this.enviarEstado(e, estado);
  }

  protected confirmarBaja() {
    const e = this.bajaPendiente();
    if (e) this.enviarEstado(e, 'retired');
    this.bajaPendiente.set(null);
  }

  private enviarEstado(e: Equipo, estado: EstadoEquipo) {
    this.error.set(null);
    this.api.cambiarEstado(e.id, estado).subscribe({
      next: () => this.recargar(),
      error: (err: HttpErrorResponse) => {
        this.error.set(errorDeApi(err).mensaje);
        this.recargar();
      }
    });
  }

  private recargar() {
    this.cargando.set(true);
    this.api
      .listar({
        branchId: this.filtroSede() ?? undefined,
        status: this.filtroEstado() || undefined,
        category: this.filtroCategoria()
      })
      .subscribe({
        next: (datos) => {
          this.equipos.set(datos);
          this.cargando.set(false);
        },
        error: () => {
          this.error.set('No se pudo cargar el equipo.');
          this.cargando.set(false);
        }
      });
  }
}
