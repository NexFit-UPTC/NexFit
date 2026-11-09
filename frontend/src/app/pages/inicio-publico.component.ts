import { Component, effect, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { destinoPorRol } from '../core/destino-por-rol';
import { GimnasiosService, PaginaGimnasios } from '../core/gimnasios.service';
import { SessionService } from '../core/session.service';

const TAMANO_PAGINA = 12;

/** Pantalla principal pública (NEX-100): gimnasios activos, sin pedir sesión. */
@Component({
  selector: 'app-inicio-publico',
  imports: [RouterLink],
  template: `
    <div class="fondo">
      <header class="barra">
        <span class="marca">NexFit</span>
        <nav class="acciones">
          <a class="nf-boton-sec" routerLink="/login">Rutinas</a>
          <a class="nf-boton" routerLink="/login">Ingresar</a>
        </nav>
      </header>

      <main class="contenido">
        <h1>Encuentra tu gimnasio</h1>
        <p class="nf-ayuda">Elige un gimnasio para ver sus sedes y planes.</p>

        @if (error()) {
          <p class="nf-alerta" role="alert">
            No se pudieron cargar los gimnasios.
            <button type="button" class="nf-boton-sec reintentar" (click)="cargar()">Reintentar</button>
          </p>
        } @else if (cargando()) {
          <p class="nf-ayuda" role="status">Cargando gimnasios…</p>
        } @else if (pagina(); as p) {
          @if (p.content.length === 0) {
            <p class="nf-vacio">Aún no hay gimnasios disponibles.</p>
          } @else {
            <ul class="rejilla">
              @for (g of p.content; track g.id) {
                <li class="nf-tarjeta gimnasio">
                  <div class="encabezado">
                    <span class="nf-avatar" aria-hidden="true">{{ g.name.charAt(0).toUpperCase() }}</span>
                    <h2>{{ g.name }}</h2>
                  </div>
                  @if (g.branches.length === 0) {
                    <p class="nf-ayuda">Sin sedes activas.</p>
                  } @else {
                    <ul class="sedes">
                      @for (s of g.branches; track $index) {
                        <li>{{ s.name }} · {{ s.city }}</li>
                      }
                    </ul>
                  }
                  <div class="acciones">
                    <a class="nf-boton-sec" [routerLink]="['/gimnasios', g.id]">Ver detalle</a>
                    <a class="nf-boton" routerLink="/login" [queryParams]="{ gimnasio: g.id }">Ingresar al gimnasio</a>
                  </div>
                </li>
              }
            </ul>
            @if (p.totalPages > 1) {
              <div class="paginacion">
                <span class="nf-ayuda">Página {{ p.page + 1 }} de {{ p.totalPages }}</span>
                <button type="button" class="nf-boton-sec anterior" [disabled]="p.page === 0" (click)="irA(p.page - 1)">
                  Anterior
                </button>
                <button
                  type="button"
                  class="nf-boton-sec siguiente"
                  [disabled]="p.page + 1 >= p.totalPages"
                  (click)="irA(p.page + 1)"
                >
                  Siguiente
                </button>
              </div>
            }
          }
        }
      </main>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .fondo {
      min-height: 100vh;
      background:
        radial-gradient(60rem 28rem at 50% -8rem, color-mix(in srgb, var(--acento) 22%, transparent), transparent 70%),
        var(--fondo);
    }
    .barra {
      display: flex;
      align-items: center;
      justify-content: space-between;
      max-width: 1200px;
      margin: 0 auto;
      padding: var(--espacio-3) var(--espacio-4);
    }
    .marca {
      font-family: var(--fuente-titulo);
      font-size: 1.5rem;
      font-weight: 700;
    }
    .acciones {
      display: flex;
      gap: var(--espacio-2);
      align-items: center;
    }
    .contenido {
      max-width: 1200px;
      margin: 0 auto;
      padding: var(--espacio-4);
    }
    h1 {
      margin: 0;
      font-family: var(--fuente-titulo);
    }
    .rejilla {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: var(--espacio-3);
      margin: var(--espacio-4) 0 0;
      padding: 0;
      list-style: none;
    }
    .gimnasio {
      display: grid;
      align-content: space-between;
      gap: var(--espacio-3);
    }
    .encabezado {
      display: flex;
      align-items: center;
      gap: var(--espacio-2);
    }
    .encabezado h2 {
      margin: 0;
      font-size: 1.125rem;
    }
    .sedes {
      margin: 0;
      padding-left: var(--espacio-3);
      color: var(--texto-suave);
    }
    .paginacion {
      display: flex;
      gap: var(--espacio-3);
      align-items: center;
      justify-content: flex-end;
      margin-top: var(--espacio-3);
    }
    .reintentar {
      margin-left: var(--espacio-2);
    }
  `
})
export class InicioPublicoComponent implements OnInit {
  private readonly gimnasios = inject(GimnasiosService);
  private readonly router = inject(Router);
  private readonly sesion = inject(SessionService);

  protected readonly pagina = signal<PaginaGimnasios | null>(null);
  protected readonly cargando = signal(true);
  protected readonly error = signal(false);
  private paginaActual = 0;

  constructor() {
    // /api/me puede responder después de pintar: quien resulta tener sesión va a su pantalla.
    effect(() => {
      if (this.sesion.estado() === 'activa') {
        this.router.navigateByUrl('/' + destinoPorRol(this.sesion), { replaceUrl: true });
      }
    });
  }

  ngOnInit(): void {
    this.cargar();
  }

  protected irA(pagina: number): void {
    this.paginaActual = Math.max(pagina, 0);
    this.cargar();
  }

  protected cargar(): void {
    this.cargando.set(true);
    this.error.set(false);
    this.gimnasios.listar(this.paginaActual, TAMANO_PAGINA).subscribe({
      next: (p) => {
        this.pagina.set(p);
        this.cargando.set(false);
      },
      error: () => {
        this.error.set(true);
        this.cargando.set(false);
      }
    });
  }
}
