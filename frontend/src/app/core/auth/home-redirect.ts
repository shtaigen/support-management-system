import { inject } from '@angular/core';
import { RedirectFunction } from '@angular/router';
import { AuthService } from './auth.service';

export const homeRedirect: RedirectFunction = () => inject(AuthService).homeUrl();
