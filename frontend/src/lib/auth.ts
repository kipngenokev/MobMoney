"use client";

import { LoginResponse } from "@/types";

const TOKEN_KEY = "mobmoney.token";
const USER_KEY = "mobmoney.user";

/**
 * Token storage. For a demo we keep the JWT in localStorage; a production app
 * should prefer an httpOnly cookie set by a backend-for-frontend to mitigate XSS
 * token theft (called out in the README's security notes).
 */
export function saveSession(login: LoginResponse): void {
  localStorage.setItem(TOKEN_KEY, login.accessToken);
  localStorage.setItem(USER_KEY, login.username);
}

export function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(TOKEN_KEY);
}

export function getUsername(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem(USER_KEY);
}

export function clearSession(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export function isAuthenticated(): boolean {
  return !!getToken();
}
