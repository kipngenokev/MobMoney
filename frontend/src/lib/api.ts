"use client";

import { Account, LoginResponse, PageResponse, Transfer } from "@/types";
import { clearSession, getToken } from "./auth";

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080";

export class ApiRequestError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string>),
  };
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(`${BASE_URL}${path}`, { ...options, headers });

  if (res.status === 401) {
    clearSession();
    throw new ApiRequestError(401, "Session expired. Please sign in again.");
  }

  const text = await res.text();
  const body = text ? JSON.parse(text) : null;

  if (!res.ok) {
    const message = body?.message || `Request failed with status ${res.status}`;
    throw new ApiRequestError(res.status, message);
  }
  return body as T;
}

export const api = {
  login(username: string, password: string): Promise<LoginResponse> {
    return request<LoginResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
  },

  listAccounts(): Promise<Account[]> {
    return request<Account[]>("/api/accounts");
  },

  getAccount(accountNumber: string): Promise<Account> {
    return request<Account>(`/api/accounts/${encodeURIComponent(accountNumber)}`);
  },

  history(accountNumber: string, page = 0, size = 20): Promise<PageResponse<Transfer>> {
    const q = new URLSearchParams({ accountNumber, page: String(page), size: String(size) });
    return request<PageResponse<Transfer>>(`/api/transfers?${q.toString()}`);
  },

  createTransfer(
    idempotencyKey: string,
    payload: {
      sourceAccountNumber: string;
      destinationAccountNumber: string;
      amount: string;
      currency: string;
      narrative?: string;
    },
  ): Promise<Transfer> {
    return request<Transfer>("/api/transfers", {
      method: "POST",
      // The Idempotency-Key makes a retried submit safe — the backend returns the
      // original result instead of moving money twice.
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(payload),
    });
  },
};

/** RFC4122-ish UUID for idempotency keys, using the platform crypto where available. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `key-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
