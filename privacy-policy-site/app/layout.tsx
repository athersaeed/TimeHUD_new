import type { Metadata } from 'next';
import './globals.css';
export const metadata: Metadata = {
  title: 'TimeHUD Privacy Policy | Boring Utils',
  description: 'How TimeHUD handles goals, app usage, calendar access, accessibility information, and local storage.',
};
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
