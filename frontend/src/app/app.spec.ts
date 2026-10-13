import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { AuthService } from './core/auth/auth.service';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    })
      .compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();
  });

  it('should render the toolbar with the app name', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('mat-toolbar')?.textContent).toContain('Support System');
  });

  it('shows role and logout button only when logged in', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const toolbar = (fixture.nativeElement as HTMLElement).querySelector('mat-toolbar')!;
    expect(toolbar.querySelector('button')).toBeNull();

    TestBed.inject(AuthService).loginAs('AGENT');
    await fixture.whenStable();
    expect(toolbar.textContent).toContain('AGENT');
    expect(toolbar.querySelector('button')?.textContent).toContain('Log out');
  });
});
