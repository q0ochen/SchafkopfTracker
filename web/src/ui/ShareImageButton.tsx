/*
 * Der Knopf „Als Bild teilen“ (docs/ARCHITEKTUR.md, 4.4) für „Abend teilen“ und die Statistik: zeichnet das Bild und
 * öffnet das Teilen-Menü im selben Takt wie das Antippen (Safari verlangt das), sonst lädt er das Bild herunter.
 */
import { useRef } from "preact/hooks";
import { Icon } from "./components";
import { useServices } from "./context";
import { mayStartSharing, shareImageFile, type ShareImage } from "./shareImage";
import { createShareImageFile } from "./shareImageCanvas";

export function ShareImageButton({
  image,
  fileName,
  onMessage,
}: {
  /** Liefert den Inhalt erst beim Antippen, damit er zum aktuellen Stand passt. */
  image: () => ShareImage;
  fileName: string;
  /** Meldung nach dem Teilen; `null` beim Antippen und wenn es nichts zu melden gibt. */
  onMessage: (message: string | null) => void;
}) {
  const { platform } = useServices();
  // Seit wann ein Teilen unterwegs ist; ein zweites Antippen währenddessen öffnet kein zweites Teilen-Menü.
  const sharingSince = useRef<number | null>(null);

  const share = () => {
    const now = Date.now();
    if (!mayStartSharing(sharingSince.current, now)) return;
    sharingSince.current = now;
    onMessage(null);
    // Ohne `await` davor: Bild zeichnen und `navigator.share` aufrufen geschieht noch in diesem Klick.
    void shareImageFile(platform, () => createShareImageFile(image(), fileName)).then((message) => {
      if (sharingSince.current === now) sharingSince.current = null;
      onMessage(message);
    });
  };

  return (
    <button type="button" class="button primary main full-width spaced-top" onClick={share}>
      <Icon name="share" />
      Als Bild teilen
    </button>
  );
}
