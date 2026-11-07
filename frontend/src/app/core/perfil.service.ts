import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export interface ContactoPerfil {
  id?: number;
  fullName: string;
  relationship: string;
  phone: string;
  primary: boolean;
}

export interface PerfilCompleto {
  identity: {
    documentType: string;
    documentNumber: string;
    firstName: string;
    lastName: string;
    birthDate: string;
    email: string;
  };
  affiliation: {
    organizationId: number;
    organizationName: string;
    roles: string[];
    phone: string | null;
    epsId: number | null;
    epsName: string | null;
    emergencyContacts: ContactoPerfil[];
  } | null;
}

export interface CambiosPerfil {
  phone?: string;
  epsId?: number;
  healthConsent?: boolean;
  emergencyContacts?: ContactoPerfil[];
}

@Injectable({ providedIn: 'root' })
export class PerfilService {
  private readonly http = inject(HttpClient);

  obtener(): Observable<PerfilCompleto> {
    return this.http.get<PerfilCompleto>('/api/me');
  }

  /** Solo teléfono, EPS y contactos de emergencia; el servidor rechaza cualquier otro campo (RN-34). */
  actualizar(cambios: CambiosPerfil): Observable<PerfilCompleto> {
    return this.http.patch<PerfilCompleto>('/api/me', cambios);
  }
}
