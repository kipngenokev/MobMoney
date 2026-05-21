"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import useSWR, { useSWRConfig } from "swr";
import { api } from "@/lib/api";
import { clearSession, getUsername, isAuthenticated } from "@/lib/auth";
import { Account } from "@/types";
import { BalanceWidget } from "@/components/BalanceWidget";
import { TransferForm } from "@/components/TransferForm";
import { TransactionHistory } from "@/components/TransactionHistory";

export default function DashboardPage() {
  const router = useRouter();
  const { mutate } = useSWRConfig();
  const [selected, setSelected] = useState<string>("");

  useEffect(() => {
    if (!isAuthenticated()) router.replace("/login");
  }, [router]);

  const { data: accounts, error } = useSWR<Account[]>("accounts", api.listAccounts);

  // Default to the first INTERNAL account once they load.
  useEffect(() => {
    if (accounts && accounts.length > 0 && !selected) {
      const first = accounts.find((a) => a.type === "INTERNAL") ?? accounts[0];
      setSelected(first.accountNumber);
    }
  }, [accounts, selected]);

  function signOut() {
    clearSession();
    router.replace("/login");
  }

  // After a transfer, immediately refresh balance + history for the active account.
  function refreshActiveAccount() {
    mutate(["account", selected]);
    mutate(["history", selected]);
  }

  const internalAccounts = accounts?.filter((a) => a.type === "INTERNAL") ?? [];

  return (
    <main className="mx-auto min-h-screen max-w-5xl px-4 py-8">
      <header className="mb-8 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-brand-700">MobMoney</h1>
          <p className="text-sm text-slate-500">Signed in as {getUsername()}</p>
        </div>
        <button
          onClick={signOut}
          className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-100"
        >
          Sign out
        </button>
      </header>

      {error && (
        <p className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
          Could not load accounts. Is the transfer service running?
        </p>
      )}

      {internalAccounts.length > 0 && (
        <div className="mb-6">
          <label className="mb-1 block text-sm font-medium text-slate-700">Account</label>
          <select
            value={selected}
            onChange={(e) => setSelected(e.target.value)}
            className="rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          >
            {internalAccounts.map((a) => (
              <option key={a.accountNumber} value={a.accountNumber}>
                {a.accountNumber} ({a.currency})
              </option>
            ))}
          </select>
        </div>
      )}

      {selected && (
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
          <div className="space-y-6 lg:col-span-1">
            <BalanceWidget accountNumber={selected} />
            <TransferForm
              accounts={accounts ?? []}
              sourceAccountNumber={selected}
              onCompleted={refreshActiveAccount}
            />
          </div>
          <div className="lg:col-span-2">
            <TransactionHistory accountNumber={selected} />
          </div>
        </div>
      )}
    </main>
  );
}
