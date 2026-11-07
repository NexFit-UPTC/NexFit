import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Reserva {
  id: number;
  classId: number;
  className: string;
  classStartsAt: string;
  branchName: string;
  memberId: number;
  memberName: string;
  status: 'confirmed' | 'cancelled';
  bookedAt: string;
  cancelledAt: string | null;
}

export interface FiltroReservas {
  memberId?: number;
  classId?: number;
  status?: 'confirmed' | 'cancelled';
}

@Injectable({ providedIn: 'root' })
export class ReservasService {
  private readonly http = inject(HttpClient);

  listar(filtro: FiltroReservas = {}): Observable<Reserva[]> {
    let params = new HttpParams();
    if (filtro.memberId !== undefined) params = params.set('memberId', filtro.memberId);
    if (filtro.classId !== undefined) params = params.set('classId', filtro.classId);
    if (filtro.status) params = params.set('status', filtro.status);
    return this.http.get<Reserva[]>('/api/bookings', { params });
  }

  /** El Cliente reserva para sí; el Staff envía además el cliente. */
  reservar(classId: number, memberId?: number): Observable<Reserva> {
    return this.http.post<Reserva>('/api/bookings', memberId === undefined ? { classId } : { classId, memberId });
  }

  cancelar(id: number): Observable<Reserva> {
    return this.http.patch<Reserva>(`/api/bookings/${id}/cancel`, {});
  }
}
