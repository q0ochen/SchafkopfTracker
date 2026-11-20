/*
 * Gerätefunktionen hinter einer kleinen Schnittstelle (für Tests austauschbar): Bildschirm anlassen, Dateien und
 * Texte teilen, dauerhaften Speicher erbitten.
 */

export type ShareOutcome = "shared" | "copied" | "downloaded" | "cancelled" | "failed";

export interface Platform {
  /** Lässt den Bildschirm an, soweit der Browser das erlaubt. Gibt eine Funktion zum Freigeben zurück. */
  keepScreenOn(): () => void;
  /** Datei weitergeben: am iPhone über „Teilen“ (zum Beispiel „In Dateien sichern“), sonst als Download. */
  saveFile(file: File): Promise<ShareOutcome>;
  /** Text teilen oder, wenn das nicht geht, in die Zwischenablage kopieren. */
  shareText(title: string, text: string): Promise<ShareOutcome>;
  /** Ob der Browser ein „Teilen“ für Text anbietet (am Handy ja, am Desktop oft nicht). */
  canShareText(): boolean;
  /** Text in die Zwischenablage kopieren; `false`, wenn der Browser das nicht zulässt. */
  copyText(text: string): Promise<boolean>;
  /** Bittet den Browser, die Daten nicht bei Platzmangel zu löschen. Wirft nie. */
  requestPersistentStorage(): Promise<boolean>;
}

interface ShareNavigator {
  canShare?(data: ShareData): boolean;
  share?(data: ShareData): Promise<void>;
}

/** Ob der Browser diese Datei über „Teilen“ weitergeben kann (am iPhone ja, am Desktop meist nicht). */
function canShareFiles(share: ShareNavigator, file: File): boolean {
  try {
    return typeof share.share === "function" && typeof share.canShare === "function" && share.canShare({ files: [file] });
  } catch {
    return false;
  }
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

export function browserPlatform(win: Window = window): Platform {
  const nav = win.navigator;
  const doc = win.document;

  function download(file: File): void {
    const url = URL.createObjectURL(file);
    const link = doc.createElement("a");
    link.href = url;
    link.download = file.name;
    link.rel = "noopener";
    doc.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }

  return {
    keepScreenOn() {
      let sentinel: WakeLockSentinel | null = null;
      let released = false;
      const acquire = async (): Promise<void> => {
        if (released || doc.visibilityState !== "visible" || !("wakeLock" in nav)) return;
        try {
          const lock = await nav.wakeLock.request("screen");
          if (released) await lock.release();
          else sentinel = lock;
        } catch {
          // Nicht erlaubt (zum Beispiel Energiesparmodus): dann eben nicht.
        }
      };
      const onVisibility = (): void => {
        if (doc.visibilityState === "visible" && (sentinel === null || sentinel.released)) void acquire();
      };
      doc.addEventListener("visibilitychange", onVisibility);
      void acquire();
      return () => {
        released = true;
        doc.removeEventListener("visibilitychange", onVisibility);
        const lock = sentinel;
        sentinel = null;
        if (lock && !lock.released) lock.release().catch(() => {});
      };
    },

    async saveFile(file) {
      const share = nav as ShareNavigator;
      if (canShareFiles(share, file)) {
        try {
          await share.share?.({ files: [file], title: file.name });
          return "shared";
        } catch (error) {
          if (isAbort(error)) return "cancelled";
          // Teilen ging schief (zum Beispiel ohne Nutzeraktion): dann herunterladen.
        }
      }
      try {
        download(file);
        return "downloaded";
      } catch {
        return "failed";
      }
    },

    async shareText(title, text) {
      const share = nav as ShareNavigator;
      if (typeof share.share === "function") {
        try {
          await share.share({ title, text });
          return "shared";
        } catch (error) {
          if (isAbort(error)) return "cancelled";
        }
      }
      try {
        await nav.clipboard.writeText(text);
        return "copied";
      } catch {
        return "failed";
      }
    },

    canShareText() {
      return typeof (nav as ShareNavigator).share === "function";
    },

    async copyText(text) {
      try {
        await nav.clipboard.writeText(text);
        return true;
      } catch {
        return false;
      }
    },

    async requestPersistentStorage() {
      try {
        const storage = nav.storage as StorageManager | undefined;
        if (!storage?.persist) return false;
        if (await storage.persisted?.()) return true;
        return (await storage.persist()) === true;
      } catch {
        return false;
      }
    },
  };
}
