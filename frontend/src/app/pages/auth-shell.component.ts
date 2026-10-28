import { Component } from '@angular/core';

/** Marco común de las pantallas de acceso: marca de NexFit y tarjeta; el contenido de cada pantalla va dentro. */
@Component({
  selector: 'app-auth-shell',
  template: `
    <div class="fondo">
      <main class="tarjeta">
        <span class="barra" aria-hidden="true"></span>
        <p class="marca">NexFit</p>
        <p class="lema">Gestión inteligente de gimnasios</p>
        <ng-content />
      </main>
      <p class="pie">NexFit · Proyecto Grupo 1 · UPTC</p>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }

    .fondo {
      min-height: 100vh;
      min-height: 100dvh;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--espacio-4);
      padding: var(--espacio-4) var(--espacio-3);
      background:
        radial-gradient(60rem 28rem at 50% -8rem, rgba(69, 200, 245, 0.16), transparent 70%),
        var(--color-fondo);
    }

    .tarjeta {
      width: 100%;
      max-width: 420px;
      display: grid;
      gap: var(--espacio-3);
      padding: 32px 28px 28px;
      background: var(--color-superficie);
      border: 1px solid var(--color-borde);
      border-radius: 16px;
      box-shadow: var(--sombra);
    }

    .barra {
      width: 56px;
      height: 6px;
      border-radius: 6px;
      background: var(--color-primario);
    }

    .marca {
      margin: var(--espacio-1) 0 0;
      font-size: 1.75rem;
      font-weight: 800;
      letter-spacing: -0.02em;
    }

    .lema {
      margin: calc(var(--espacio-2) * -1) 0 var(--espacio-2);
      color: var(--color-texto-suave);
    }

    .pie {
      margin: 0;
      font-size: 0.8125rem;
      color: var(--color-texto-suave);
    }

    @media (max-width: 480px) {
      .tarjeta {
        padding: 24px 20px 20px;
      }
    }
  `
})
export class AuthShellComponent {}
