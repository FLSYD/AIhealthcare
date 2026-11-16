const guards = new Set()

export function registerNavigationGuard(guard) {
  guards.add(guard)
  return () => guards.delete(guard)
}

export async function confirmNavigation() {
  for (const guard of guards) if (!await guard()) return false
  return true
}
