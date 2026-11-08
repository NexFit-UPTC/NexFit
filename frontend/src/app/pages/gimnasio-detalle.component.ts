import { CurrencyPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DetalleGimnasio, GimnasiosService } from '../core/gimnasios.service';

type Estado = 'cargando' | 'listo' | 'no-encontrado' | 'error';

/** Detalle público de un gimnasio (NEX-100): sedes y planes activos. */
@Component({
  selector: 'app-gimnasio-detalle',
  imports: [RouterLink, CurrencyPipe],
  template: `
    <div class="fondo">
      <header class="barra">
        <a class="marca" routerLink="/">NexFit</a>
        <a class="nf-boton-sec" routerLink="/">Volver</a>
      </header>

      <main class="contenido">
        @switch (estado()) {
          @case ('cargando') {
            <p class="nf-ayuda" role="status">Cargando gimnasio…</p>
          }
          @case ('no-encontrado') {
            <h1>Gimnasio no encontrado</h1>
            <p class="nf-ayuda">El gimnasio no existe o ya no está disponible.</p>
            <a class="nf-boton" routerLink="/">Ver gimnasios</a>
          }
          @case ('error') {
            <p class="nf-alerta" role="alert">
              No se pudo cargar el gimnasio.
              <button type="button" class="nf-boton-sec reintentar" (click)="cargar()">Reintentar</button>
            </p>
          }
          @case ('listo') {
            @if (gimnasio(); as g) {
              <div class="titulo">
                <span class="nf-avatar" aria-hidden="true">{{ g.name.charAt(0).toUpperCase() }}</span>
                <h1>{{ g.name }}</h1>
                <a class="nf-boton" routerLink="/login" [queryParams]="{ gimnasio: g.id }">Ingresar al gimnasio</a>
              </div>

              <section class="nf-tarjeta">
                <h2>Sedes</h2>
                @if (g.branches.length === 0) {
                  <p class="nf-vacio">Sin sedes activas.</p>
                } @else {
                  <ul>
                    @for (s of g.branches; track $index) {
                      <li>
                        <strong>{{ s.name }}</strong><br />
                        <span class="nf-ayuda">
                          {{ s.addressLine1 }}@if (s.addressLine2) {, {{ s.addressLine2 }}} · {{ s.city }}, {{ s.department }}
                        </span>
                      </li>
                    }
                  </ul>
                }
              </section>

              <section class="nf-tarjeta">
                <h2>Planes</h2>
                @if (g.plans.length === 0) {
                  <p class="nf-vacio">Sin planes activos.</p>
                } @else {
                  <ul>
                    @for (p of g.plans; track $index) {
                      <li>
                        <strong>{{ p.name }}</strong> ·
                        {{ p.price | currency: 'COP' : 'symbol-narrow' : '1.0-0' }} · {{ p.durationDays }} días
                      </li>
                    }
                  </ul>
                }
              </section>
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
      color: inherit;
      text-decoration: none;
    }
    .contenido {
      display: grid;
      gap: var(--espacio-3);
      max-width: 1200px;
      margin: 0 auto;
      padding: var(--espacio-4);
    }
    .titulo {
      display: flex;
      align-items: center;
      gap: var(--espacio-3);
    }
    .titulo h1 {
      margin: 0;
      flex: 1;
    }
    h1 {
      font-family: var(--fuente-titulo);
    }
    h2 {
      margin-top: 0;
    }
    ul {
      display: grid;
      gap: var(--espacio-2);
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .reintentar {
      margin-left: var(--espacio-2);
    }
  `
})
export class GimnasioDetalleComponent implements OnInit {
  private readonly gimnasios = inject(GimnasiosService);
  private readonly ruta = inject(ActivatedRoute);

  protected readonly estado = signal<Estado>('cargando');
  protected readonly gimnasio = signal<DetalleGimnasio | null>(null);
  private id = NaN;

  ngOnInit(): void {
    const crudo = this.ruta.snapshot.paramMap.get('id') ?? '';
    // Un id no numérico no se envía al servidor (daría 400).
    if (!/^\d+$/.test(crudo)) {
      this.estado.set('no-encontrado');
      return;
    }
    this.id = Number(crudo);
    this.cargar();
  }

  protected cargar(): void {
    this.estado.set('cargando');
    this.gimnasios.detalle(this.id).subscribe({
      next: (g) => {
        this.gimnasio.set(g);
        this.estado.set('listo');
      },
      error: (e: HttpErrorResponse) => this.estado.set(e.status === 404 ? 'no-encontrado' : 'error')
    });
  }
}
