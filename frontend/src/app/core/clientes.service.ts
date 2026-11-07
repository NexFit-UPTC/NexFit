import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { TipoDocumento } from './establecimientos.service';

export type EstadoMembresia = 'active' | 'suspended' | 'scheduled' | 'expired' | 'cancelled';

export interface ResumenCliente {
  memberId: number;
  documentType: TipoDocumento;
  documentNumber: string;
  firstName: string;
  lastName: string;
  /** Estado de la afiliación al gimnasio. */
  active: boolean;
  /** Membresía efectiva; null si nunca tuvo. */
  membershipStatus: EstadoMembresia | null;
  membershipEndDate: string | null;
}

export interface PaginaClientes {
  content: ResumenCliente[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface FiltroClientes {
  q?: string;
  active?: boolean;
  page?: number;
  size?: number;
}

export interface ContactoEmergencia {
  id: number;
  fullName: string;
  relationship: string;
  phone: string;
  primary: boolean;
}

export interface MembresiaCliente {
  status: EstadoMembresia;
  planName: string | null;
  startDate: string;
  endDate: string;
}

export interface DetalleCliente {
  memberId: number;
  documentType: TipoDocumento;
  documentNumber: string;
  firstName: string;
  lastName: string;
  email: string;
  birthDate: string;
  active: boolean;
  joinedAt: string;
  membership: MembresiaCliente | null;
  phone: string | null;
  epsId: number | null;
  epsName: string | null;
  emergencyContacts: ContactoEmergencia[];
}

export interface DatosCliente {
  documentType: TipoDocumento;
  documentNumber: string;
  firstName: string;
  lastName: string;
  birthDate: string;
  email: string;
  phone: string;
  epsId: number | null;
  emergencyContact: { fullName: string; relationship: string; phone: string };
  healthConsent: boolean;
  privacyAccepted: boolean;
}

/** 201 trae temporaryPassword (cuenta nueva); 200 solo userId, memberId y nombre (persona ya existente, afiliada). */
export interface ResultadoCliente {
  userId: number;
  memberId: number;
  temporaryPassword?: string;
  firstName?: string;
  lastName?: string;
}

export interface PersonaEncontrada {
  firstName: string;
  lastName: string;
}

@Injectable({ providedIn: 'root' })
export class ClientesService {
  private readonly http = inject(HttpClient);

  listar(filtro: FiltroClientes = {}): Observable<PaginaClientes> {
    let params = new HttpParams();
    if (filtro.q?.trim()) params = params.set('q', filtro.q.trim());
    if (filtro.active !== undefined) params = params.set('active', filtro.active);
    if (filtro.page !== undefined) params = params.set('page', filtro.page);
    if (filtro.size !== undefined) params = params.set('size', filtro.size);
    return this.http.get<PaginaClientes>('/api/clients', { params });
  }

  /** Detalle con EPS y contactos de emergencia; el servidor audita cada consulta. */
  detalle(memberId: number): Observable<DetalleCliente> {
    return this.http.get<DetalleCliente>(`/api/clients/${memberId}`);
  }

  crear(datos: DatosCliente): Observable<ResultadoCliente> {
    return this.http.post<ResultadoCliente>('/api/clients', datos);
  }

  /**
   * ¿Ya existe una persona con ese documento? 404 si no. El Staff confirma que verificó el documento y que la
   * persona está presente (RF-90), y el servidor audita la consulta.
   */
  buscarPersona(tipo: TipoDocumento, numero: string): Observable<PersonaEncontrada> {
    const params = new HttpParams()
      .set('documentType', tipo)
      .set('documentNumber', numero)
      .set('documentVerified', true)
      .set('personPresent', true);
    return this.http.get<PersonaEncontrada>('/api/clients/search', { params });
  }
}
