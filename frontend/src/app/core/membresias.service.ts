import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { Pago, DatosPagoContratacion } from './pagos.service';

export type EstadoEfectivo = 'scheduled' | 'current' | 'expired' | 'suspended' | 'cancelled';

export interface Membresia {
  id: number;
  memberId: number;
  memberName: string;
  planId: number;
  planName: string;
  startDate: string;
  endDate: string;
  status: 'active' | 'suspended' | 'cancelled';
  effectiveStatus: EstadoEfectivo;
  statusReason: string | null;
  statusChangedAt: string | null;
  createdAt: string;
}

export interface Contratacion {
  membership: Membresia;
  payment: Pago | null;
}

export interface FiltroMembresias {
  memberId?: number;
  status?: EstadoEfectivo;
  expiringInDays?: number;
}

export interface DatosContratacion {
  memberId: number;
  planId: number;
  startDate?: string;
  payment?: DatosPagoContratacion;
}

export const ETIQUETA_ESTADO: Record<EstadoEfectivo, string> = {
  scheduled: 'Programada',
  current: 'Vigente',
  expired: 'Vencida',
  suspended: 'Suspendida',
  cancelled: 'Cancelada'
};

@Injectable({ providedIn: 'root' })
export class MembresiasService {
  private readonly http = inject(HttpClient);

  listar(filtro: FiltroMembresias = {}): Observable<Membresia[]> {
    let params = new HttpParams();
    if (filtro.memberId !== undefined) params = params.set('memberId', filtro.memberId);
    if (filtro.status) params = params.set('status', filtro.status);
    if (filtro.expiringInDays !== undefined) params = params.set('expiringInDays', filtro.expiringInDays);
    return this.http.get<Membresia[]>('/api/memberships', { params });
  }

  contratar(datos: DatosContratacion): Observable<Contratacion> {
    return this.http.post<Contratacion>('/api/memberships', datos);
  }

  renovar(id: number, planId: number | null, payment?: DatosPagoContratacion): Observable<Contratacion> {
    return this.http.post<Contratacion>(`/api/memberships/${id}/renew`, { ...(planId ? { planId } : {}), payment });
  }

  suspender(id: number, reason: string): Observable<Membresia> {
    return this.http.patch<Membresia>(`/api/memberships/${id}/suspend`, { reason });
  }

  reactivar(id: number, reason: string): Observable<Membresia> {
    return this.http.patch<Membresia>(`/api/memberships/${id}/reactivate`, { reason });
  }

  cancelar(id: number, reason: string): Observable<Membresia> {
    return this.http.patch<Membresia>(`/api/memberships/${id}/cancel`, { reason });
  }
}
