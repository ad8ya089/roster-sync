import { LockKeyhole, Pencil } from 'lucide-react';
export function OwnerTag({ owner }: { owner: 'HR' | 'TRANSPORT' }) {
  const Icon = owner === 'HR' ? LockKeyhole : Pencil;
  return <span className={`owner owner-${owner.toLowerCase()}`}><Icon aria-hidden="true" />{owner === 'HR' ? 'HR owned' : 'Transport owned'}</span>;
}
