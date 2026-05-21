export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  username: string;
}

export interface Account {
  accountNumber: string;
  ownerUsername: string;
  currency: string;
  balance: string; // BigDecimal serialized as string to preserve precision
  type: "INTERNAL" | "EXTERNAL";
}

export type TransferStatus = "PENDING" | "COMPLETED" | "FAILED";

export interface Transfer {
  reference: string;
  sourceAccountNumber: string;
  destinationAccountNumber: string;
  amount: string;
  currency: string;
  type: "INTERNAL" | "EXTERNAL";
  status: TransferStatus;
  failureReason: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}
