import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface EventoAuditoria {
  id: number;
  organizationId: number | null;
  actorUserId: number | null;
  eventType: string;
  entityType: string | null;
  entityId: number | null;
  occurredAt: string;
  ipAddress: string | null;
  metadata: Record<string, unknown>;
}

export interface FiltroAuditoria {
  from?: string;
  to?: string;
  userId?: number;
  type?: string;
}

/** Tipos de evento auditables (AuditEventType del backend) con su etiqueta en español. */
export const TIPOS_EVENTO: Record<string, string> = {
  LOGIN: 'Inicio de sesión',
  LOGOUT: 'Cierre de sesión',
  LOGIN_FAILED: 'Intento de acceso fallido',
  ACCOUNT_LOCKED: 'Cuenta bloqueada',
  ROLE_CHANGED: 'Cambio de rol',
  ADMIN_ASSIGNED: 'Administrador asignado',
  STAFF_ACCOUNT_CREATED: 'Cuenta de personal creada',
  CLIENT_ACCOUNT_CREATED: 'Cuenta de cliente creada',
  MEMBERSHIP_AFFILIATION_STATUS_CHANGED: 'Estado de afiliación cambiado',
  ORGANIZATION_DEACTIVATED: 'Establecimiento desactivado',
  ORGANIZATION_REACTIVATED: 'Establecimiento reactivado',
  PAYMENT_VOIDED: 'Pago anulado',
  PENDING_PAYMENT_COMPLETED: 'Pago pendiente completado',
  MEMBERSHIP_SUSPENDED: 'Membresía suspendida',
  MEMBERSHIP_CANCELLED: 'Membresía cancelada',
  MEMBERSHIP_REACTIVATED: 'Membresía reactivada',
  PASSWORD_RESET: 'Contraseña restablecida',
  IDENTITY_CORRECTED: 'Identidad corregida',
  PERSON_LOOKUP_BY_DOCUMENT: 'Consulta de persona por documento',
  HEALTH_CONTACT_DATA_ACCESSED: 'Acceso a datos de salud y contactos'
};

@Injectable({ providedIn: 'root' })
export class AuditoriaService {
  private readonly http = inject(HttpClient);

  buscar(filtro: FiltroAuditoria = {}): Observable<EventoAuditoria[]> {
    let params = new HttpParams();
    if (filtro.from) params = params.set('from', filtro.from);
    if (filtro.to) params = params.set('to', filtro.to);
    if (filtro.userId !== undefined) params = params.set('userId', filtro.userId);
    if (filtro.type) params = params.set('type', filtro.type);
    return this.http.get<EventoAuditoria[]>('/api/audit/events', { params });
  }
}
