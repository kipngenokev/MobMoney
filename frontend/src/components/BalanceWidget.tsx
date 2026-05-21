"use client";

import useSWR from "swr";
import { api } from "@/lib/api";
import { Account } from "@/types";

function formatMoney(amount: string, currency: string): string {
  const value = Number(amount);
  try {
    return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(value);
  } catch {
    return `${value.toFixed(2)} ${currency}`;
  }
}

/**
 * Real-time balance widget. Polls the account endpoint every few seconds via
 * SWR so the displayed balance reflects transfers made in other tabs/devices.
 * `refreshInterval` + revalidate-on-focus give a "live" feel without WebSockets;
 * the design notes in the README discuss the SSE/WebSocket alternative.
 */
export function BalanceWidget({ accountNumber }: { accountNumber: string }) {
  const { data, error, isLoading } = useSWR<Account>(
    accountNumber ? ["account", accountNumber] : null,
    () => api.getAccount(accountNumber),
    { refreshInterval: 4000, revalidateOnFocus: true, keepPreviousData: true },
  );

  return (
    <div className="rounded-2xl bg-gradient-to-br from-brand-600 to-brand-700 p-6 text-white shadow-sm">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-brand-50/80">Available balance</span>
        <span className="flex items-center gap-1.5 text-xs text-brand-50/80">
          <span className="relative flex h-2 w-2">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-white opacity-75" />
            <span className="relative inline-flex h-2 w-2 rounded-full bg-white" />
          </span>
          live
        </span>
      </div>
      <div className="mt-3 text-3xl font-bold tabular-nums">
        {error ? (
          <span className="text-lg text-red-100">Unable to load balance</span>
        ) : isLoading && !data ? (
          <span className="text-lg text-brand-50/70">Loading…</span>
        ) : (
          data && formatMoney(data.balance, data.currency)
        )}
      </div>
      <div className="mt-2 text-sm text-brand-50/70">{accountNumber}</div>
    </div>
  );
}
