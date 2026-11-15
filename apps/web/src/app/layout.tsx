import type { Metadata, Viewport } from "next";
import Link from "next/link";
import "./globals.css";
import styles from "./layout.module.css";

export const metadata: Metadata = {
  title: "openrun",
  description: "매주 열리는 오픈런 예매",
};

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko">
      <body>
        <header className={styles.header}>
          <Link href="/" className={styles.brand}>
            openrun
          </Link>
          <span className={styles.tag}>주간 오픈런 예매</span>
        </header>
        {children}
      </body>
    </html>
  );
}
