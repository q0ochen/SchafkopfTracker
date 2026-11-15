// Tests für die Übersetzung der Android-Vektorgrafiken in SVG (scripts/generate-icons.mjs).
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { androidColor, appIconSvg, launcherBackground, monochromeSvg, SQUARE_CROP, vectorToSvg } from "./generate-icons.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const res = path.resolve(here, "../../app/src/main/res");

describe("Android-Farben", () => {
  it("übersetzt alle Schreibweisen", () => {
    expect(androidColor("#1F6B41")).toEqual({ color: "#1F6B41", opacity: 1 });
    expect(androidColor("#FF000000")).toEqual({ color: "#000000", opacity: 1 });
    expect(androidColor("#80FFFFFF")).toEqual({ color: "#FFFFFF", opacity: 0.502 });
    expect(androidColor("#fff")).toEqual({ color: "#FFFFFF", opacity: 1 });
    expect(androidColor("#8f00")).toEqual({ color: "#FF0000", opacity: 0.533 });
    expect(androidColor("#00000000")).toEqual({ color: "none", opacity: 1 });
    expect(androidColor(undefined)).toEqual({ color: "none", opacity: 1 });
    expect(() => androidColor("#12345")).toThrow("Unbekannte Farbe");
  });
});

describe("Vektorgrafik → SVG", () => {
  it("dreht Gruppen wie Android um den Drehpunkt und füllt nur, was eine Farbe hat", () => {
    const xml = `<vector android:viewportWidth="10" android:viewportHeight="20">
      <group android:pivotX="5" android:pivotY="6" android:rotation="90" android:scaleX="2" android:translateX="1">
        <path android:pathData="M0,0 h1" android:strokeColor="#FF112233" android:strokeWidth="1.5" android:strokeLineCap="round"/>
      </group>
      <path android:pathData="M1,1 h2" android:fillColor="#445566" android:fillType="evenOdd"/>
    </vector>`;
    const { viewBox, body } = vectorToSvg(xml);
    expect(viewBox).toEqual([0, 0, 10, 20]);
    expect(body).toContain('<g transform="translate(6 6) rotate(90) scale(2 1) translate(-5 -6)">');
    expect(body).toContain('<path d="M0,0 h1" fill="none" stroke="#112233" stroke-width="1.5" stroke-linecap="round"/>');
    expect(body).toContain('<path d="M1,1 h2" fill="#445566" fill-rule="evenodd"/>');
  });

  it("lehnt unbekannte Elemente und offene Gruppen ab", () => {
    expect(() => vectorToSvg('<vector android:viewportWidth="1" android:viewportHeight="1"><clip-path/></vector>')).toThrow();
    expect(() => vectorToSvg('<vector android:viewportWidth="1" android:viewportHeight="1"><group></vector>')).toThrow("Gruppen");
    expect(() => vectorToSvg("<path/>")).toThrow("<vector>");
  });

  it("übersetzt das App-Symbol und die Streichholzschachtel der Android-App", () => {
    const foreground = readFileSync(path.join(res, "drawable/ic_launcher_foreground.xml"), "utf8");
    const background = launcherBackground(readFileSync(path.join(res, "values/colors.xml"), "utf8"));
    expect(background).toBe("#1F6B41");
    const icon = appIconSvg(foreground, background, SQUARE_CROP, "rounded");
    expect(icon).toContain('viewBox="14 14 80 80"');
    expect(icon).toContain('rx="16" fill="#1F6B41"');
    expect(icon).toContain("rotate(-14)");
    expect(icon).toContain("rotate(8)");

    const matchbox = monochromeSvg(readFileSync(path.join(res, "drawable/ic_matchbox.xml"), "utf8"));
    expect(matchbox).not.toContain("#000000");
    expect(matchbox).toContain('fill-rule="evenodd"');
    expect(matchbox).toContain('stroke="currentColor"');
  });
});
