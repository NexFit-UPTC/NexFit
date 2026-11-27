const CLAVE = 'nexfit.registro-google';

/**
 * «Registrarme con Google» (NEX-148) pasa por el mismo flujo que el login: si el correo no tiene cuenta, el
 * servidor vuelve a /login?google=nuevo. Esta marca (solo en el navegador, sin datos) dice que la persona ya
 * pidió registrarse, para saltar directo a /registro en vez de volver a preguntarle. Sin sessionStorage no hay marca.
 */
export function marcarRegistroGoogle(): void {
  try {
    sessionStorage.setItem(CLAVE, '1');
  } catch {
    // Almacenamiento no disponible: se ignora.
  }
}

/** Devuelve si había marca y la borra: vale para un solo regreso de Google. */
export function consumirRegistroGoogle(): boolean {
  try {
    const hay = sessionStorage.getItem(CLAVE) === '1';
    sessionStorage.removeItem(CLAVE);
    return hay;
  } catch {
    return false;
  }
}
