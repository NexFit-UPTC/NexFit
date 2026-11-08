const CLAVE = 'nexfit.gimnasio';

/** Gimnasio elegido en la pantalla pública (?gimnasio=<id>), guardado solo hasta elegir establecimiento. Sin sessionStorage, no se preselecciona. */
export function guardarGimnasio(valor: string | null): void {
  try {
    if (valor !== null && /^\d+$/.test(valor) && Number(valor) > 0) sessionStorage.setItem(CLAVE, valor);
    else sessionStorage.removeItem(CLAVE);
  } catch {
    // Almacenamiento no disponible: se ignora.
  }
}

export function leerGimnasio(): number | null {
  try {
    const v = sessionStorage.getItem(CLAVE);
    return v !== null && /^\d+$/.test(v) && Number(v) > 0 ? Number(v) : null;
  } catch {
    return null;
  }
}

export function borrarGimnasio(): void {
  try {
    sessionStorage.removeItem(CLAVE);
  } catch {
    // Almacenamiento no disponible: se ignora.
  }
}
