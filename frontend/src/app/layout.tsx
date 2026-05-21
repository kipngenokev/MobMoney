import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "MobMoney",
  description: "Mobile money transfers",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
