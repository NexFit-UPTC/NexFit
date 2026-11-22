import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface Ingreso {
  id: number;
  memberId: number;
  memberName: string;
  branchId: number;
  branchName: string;
  checkInAt: string;
  checkOutAt: string | null;
  autoClosed: boolean;
  registeredByMemberId: number | null;
  registeredByName: string | null;
}

export interface FiltroAsistencia {
  memberId?: number;
  /** AAAA-MM-DD, inclusiva, en la zona horaria del gimnasio. */
  from?: string;
  to?: string;
  open?: boolean;
}

@Injectable({ providedIn: 'root' })
export class AsistenciaService {
  private readonly http = inject(HttpClient);

  listar(filtro: FiltroAsistencia = {}): Observable<Ingreso[]> {
    let params = new HttpParams();
    if (filtro.memberId !== undefined) params = params.set('memberId', filtro.memberId);
    if (filtro.from) params = params.set('from', filtro.from);
    if (filtro.to) params = params.set('to', filtro.to);
    if (filtro.open) params = params.set('open', true);
    return this.http.get<Ingreso[]>('/api/check-ins', { params });
  }

  /** El Cliente registra el suyo; el Staff envía además el cliente. */
  ingresar(branchId: number, memberId?: number): Observable<Ingreso> {
    return this.http.post<Ingreso>('/api/check-ins', memberId === undefined ? { branchId } : { branchId, memberId });
  }

  salir(id: number): Observable<Ingreso> {
    return this.http.patch<Ingreso>(`/api/check-ins/${id}/check-out`, {});
  }
}
