/* Eine Ablage wie `localStorage`, nur im Speicher (für Tests der Anmeldung). */
export class MemoryStorage implements Pick<Storage, "getItem" | "setItem" | "removeItem"> {
  readonly values = new Map<string, string>();

  /** Zum Testen eines gestörten Speichers: Jeder Zugriff scheitert. */
  broken = false;

  private check(): void {
    if (this.broken) throw new Error("Speicher gestört (Test)");
  }

  getItem(key: string): string | null {
    this.check();
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.check();
    this.values.set(key, value);
  }

  removeItem(key: string): void {
    this.check();
    this.values.delete(key);
  }
}
