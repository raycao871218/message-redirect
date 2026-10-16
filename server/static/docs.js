"use strict";
window.addEventListener("DOMContentLoaded", () => {
  window.ui = SwaggerUIBundle({ url: "/openapi.json", dom_id: "#swagger-ui", deepLinking: true,
    persistAuthorization: false, validatorUrl: null, displayRequestDuration: true,
    presets: [SwaggerUIBundle.presets.apis], layout: "BaseLayout" });
});
