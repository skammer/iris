import "./navigation.js";
import "./tabs.js";
import "./chat-scroll.js";
import "./composer.js";
import "./math.js";
import "./connection.js";
import { assetUrl } from "./assets.js";

// Initialize component lifetimes and client/controller globals before Datastar
// processes data-init. The standalone catalogue needs no Datastar runtime.
if (!document.body.classList.contains("ui-catalog-page")) {
  import(assetUrl("/public/vendor/datastar-1.0.0-RC.8.js"));
}
