// Einstieg der Web-App. Die Oberfläche folgt; bis dahin zeigt die Seite nur den Titel.
import { render } from "preact";

const root = document.getElementById("app");
if (root) render(<main class="content"><h1>Schafkopf Tracker</h1></main>, root);
