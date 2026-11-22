import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type MetodoPago = 'cash' | 'card' | 'transfer' | 'other';
export type EstadoPago = 'pending' | 'completed' | 'voided';

export const ETIQUETA_METODO: Record<MetodoPago, string> = {
  cash: 'Efectivo',
  card: 'Tarjeta',
  transfer: 'Transferencia',
  other: 'Otro'
};

export const ETIQUETA_ESTADO_PAGO: Record<EstadoPago, string> = {
  pending: 'Pendiente',
  completed: 'Completado',
  voided: 'Anulado'
};

export interface Pago {
  id: number;
  memberId: number;
  memberName: string;
  membershipId: number | null;
  concept: string;
  amount: number;
  paidAt: string;
  method: MetodoPago;
  reference: string | null;
  status: EstadoPago;
  voidReason: string | null;
  completedAt: string | null;
}

export interface PaginaPagos {
  items: Pago[];
  total: number;
  completedTotal: number;
  page: number;
  size: number;
}

export interface FiltroPagos {
  memberId?: number;
  status?: EstadoPago;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface DatosPago {
  memberId: number;
  membershipId?: number;
  concept: string;
  amount: number;
  method: MetodoPago;
  reference?: string;
  status?: 'pending' | 'completed';
}

/** Pago que acompaña una contratación o renovación; el valor por defecto es el precio del plan. */
export interface DatosPagoContratacion {
  amount?: number;
  method: MetodoPago;
  reference?: string;
  status?: 'pending' | 'completed';
}

@Injectable({ providedIn: 'root' })
export class PagosService {
  private readonly http = inject(HttpClient);

  listar(filtro: FiltroPagos = {}): Observable<PaginaPagos> {
    let params = new HttpParams();
    if (filtro.memberId !== undefined) params = params.set('memberId', filtro.memberId);
    if (filtro.status) params = params.set('status', filtro.status);
    if (filtro.from) params = params.set('from', filtro.from);
    if (filtro.to) params = params.set('to', filtro.to);
    if (filtro.page !== undefined) params = params.set('page', filtro.page);
    if (filtro.size !== undefined) params = params.set('size', filtro.size);
    return this.http.get<PaginaPagos>('/api/payments', { params });
  }

  registrar(datos: DatosPago): Observable<Pago> {
    return this.http.post<Pago>('/api/payments', datos);
  }

  anular(id: number, reason: string): Observable<Pago> {
    return this.http.patch<Pago>(`/api/payments/${id}/void`, { reason });
  }

  completar(id: number): Observable<Pago> {
    return this.http.patch<Pago>(`/api/payments/${id}/complete`, {});
  }
}
