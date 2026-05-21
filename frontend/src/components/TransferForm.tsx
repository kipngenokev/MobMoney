"use client";

import { FormEvent, useMemo, useState } from "react";
import { api, ApiRequestError, newIdempotencyKey } from "@/lib/api";
import { Account, Transfer } from "@/types";

/**
 * Transfer flow. A single Idempotency-Key is generated per "compose" session and
 * reused across retries of the SAME transfer, so a double-click or a network
 * retry can never move money twice. A new key is minted only after a success.
 */
export function TransferForm({
  accounts,
  sourceAccountNumber,
  onCompleted,
}: {
  accounts: Account[];
  sourceAccountNumber: string;
  onCompleted: () => void;
}) {
  const [destination, setDestination] = useState("");
  const [amount, setAmount] = useState("");
  const [narrative, setNarrative] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<Transfer | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Stable key for this attempt; regenerated after a successful transfer.
  const [idempotencyKey, setIdempotencyKey] = useState(() => newIdempotencyKey());

  const source = useMemo(
    () => accounts.find((a) => a.accountNumber === sourceAccountNumber),
    [accounts, sourceAccountNumber],
  );

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!source) return;
    setSubmitting(true);
    setError(null);
    setResult(null);
    try {
      const transfer = await api.createTransfer(idempotencyKey, {
        sourceAccountNumber,
        destinationAccountNumber: destination,
        amount,
        currency: source.currency,
        narrative: narrative || undefined,
      });
      setResult(transfer);
      setIdempotencyKey(newIdempotencyKey()); // fresh key for the next transfer
      setAmount("");
      setNarrative("");
      onCompleted();
    } catch (err) {
      // On failure we keep the SAME idempotency key so the user can safely retry.
      setError(err instanceof ApiRequestError ? err.message : "Transfer failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="rounded-2xl bg-white p-6 shadow-sm ring-1 ring-slate-200">
      <h2 className="text-lg font-semibold text-slate-800">Send money</h2>
      <form onSubmit={onSubmit} className="mt-4 space-y-4">
        <div>
          <label className="mb-1 block text-sm font-medium text-slate-700">To account</label>
          <input
            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            placeholder="ACC-BOB-001 or PARTNER-EXT-001"
            value={destination}
            onChange={(e) => setDestination(e.target.value)}
            required
          />
        </div>
        <div>
          <label className="mb-1 block text-sm font-medium text-slate-700">
            Amount ({source?.currency ?? "USD"})
          </label>
          <input
            type="number"
            min="0.01"
            step="0.01"
            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm tabular-nums focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            placeholder="0.00"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            required
          />
        </div>
        <div>
          <label className="mb-1 block text-sm font-medium text-slate-700">Note (optional)</label>
          <input
            className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            placeholder="What's it for?"
            maxLength={140}
            value={narrative}
            onChange={(e) => setNarrative(e.target.value)}
          />
        </div>

        {error && (
          <p className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">
            {error}
          </p>
        )}
        {result && (
          <p className="rounded-lg bg-brand-50 px-3 py-2 text-sm text-brand-700">
            {result.status === "COMPLETED" && `Sent! Reference ${result.reference}`}
            {result.status === "PENDING" &&
              `Submitted (pending partner settlement). Reference ${result.reference}`}
            {result.status === "FAILED" && `Transfer failed: ${result.failureReason ?? "rejected"}`}
          </p>
        )}

        <button
          type="submit"
          disabled={submitting || !source}
          className="w-full rounded-lg bg-brand-600 py-2.5 text-sm font-semibold text-white transition hover:bg-brand-700 disabled:opacity-60"
        >
          {submitting ? "Sending…" : "Send transfer"}
        </button>
      </form>
    </div>
  );
}
