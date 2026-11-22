import { HttpErrorResponse } from '@angular/common/http';
import { errorDeApi } from './establecimientos.service';

describe('errorDeApi', () => {
  it('un 400 {code, field, message} se asigna al campo', () => {
    const e = new HttpErrorResponse({
      status: 400,
      error: { code: 'VALIDATION_ERROR', field: 'contactEmail', message: 'El correo de contacto no es válido' }
    });
    expect(errorDeApi(e)).toEqual({ campo: 'contactEmail', mensaje: 'El correo de contacto no es válido' });
  });
  it('un error sin campo (409) queda como mensaje general', () => {
    const e = new HttpErrorResponse({ status: 409, error: { message: 'El establecimiento está inactivo' } });
    expect(errorDeApi(e)).toEqual({ campo: null, mensaje: 'El establecimiento está inactivo' });
  });
});
