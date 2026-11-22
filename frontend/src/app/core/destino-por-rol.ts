import { SessionService } from './session.service';

/** Pantalla de inicio según el rol de la sesión activa. */
export function destinoPorRol(sesion: SessionService): string {
  const roles = sesion.roles();
  if (sesion.esMaster() || roles.includes('admin')) return 'panel';
  return roles.includes('client') && !roles.includes('trainer') ? 'inicio' : 'mi-perfil';
}
