"use client";

import useSWR from "swr";
import { api } from "@/lib/api";
import { PageResponse, Transfer, TransferStatus } from "@/types";

const STATUS_STYLES: Record<TransferStatus, string> = {
  COMPLETED: "bg-brand-50 text-brand-700",
  PENDING: "bg-amber-50 text-amber-700",
  FAILED: "bg-red-50 text-red-700",
};

function StatusBadge({ status }: { status: TransferStatus }) {
  return (
    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[status]}`}>
      {status}
    </span>
  );
}

/**
 * Transaction history for the selected account. Bound to the same SWR key family
 * the dashboard revalidates after a transfer, so a completed send shows up
 * immediately rather than waiting for the next poll.
 */
export function TransactionHistory({ accountNumber }: { accountNumber: string }) {
  const { data, error, isLoading } = useSWR<PageResponse<Transfer>>(
    accountNumber ? ["history", accountNumber] : null,
    () => api.history(accountNumber, 0, 20),
    { refreshInterval: 8000, keepPreviousData: true },
  );

  return (
    <div className="rounded-2xl bg-white p-6 shadow-sm ring-1 ring-slate-200">
      <h2 className="text-lg font-semibold text-slate-800">Transaction history</h2>
      {error && <p className="mt-4 text-sm text-red-600">Unable to load transactions.</p>}
      {isLoading && !data && <p className="mt-4 text-sm text-slate-400">Loading…</p>}
      {data && data.items.length === 0 && (
        <p className="mt-4 text-sm text-slate-400">No transactions yet.</p>
      )}
      <ul className="mt-4 divide-y divide-slate-100">
        {data?.items.map((t) => {
          const outgoing = t.sourceAccountNumber === accountNumber;
          return (
            <li key={t.reference} className="flex items-center justify-between py-3">
              <div className="min-w-0">
                <div className="flex items-center gap-2">
                  <span className="truncate text-sm font-medium text-slate-800">
                    {outgoing ? `To ${t.destinationAccountNumber}` : `From ${t.sourceAccountNumber}`}
                  </span>
                  <StatusBadge status={t.status} />
                </div>
                <div className="text-xs text-slate-400">
                  {new Date(t.createdAt).toLocaleString()} · {t.reference}
                </div>
              </div>
              <div
                className={`shrink-0 text-sm font-semibold tabular-nums ${
                  outgoing ? "text-slate-800" : "text-brand-600"
                }`}
              >
                {outgoing ? "-" : "+"}
                {Number(t.amount).toFixed(2)} {t.currency}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
