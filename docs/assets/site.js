// Progressive enhancement for recly.dev. Every link works without this file: download buttons
// point at the Releases page until the newest release's asset URLs replace them.
(function () {
  "use strict";

  document.querySelectorAll("[data-copy]").forEach(function (button) {
    var label = button.textContent;
    button.addEventListener("click", function () {
      var text = document.getElementById(button.dataset.copy).textContent.trim();
      var done = function (ok) {
        button.textContent = ok ? button.dataset.done : button.dataset.fail;
        setTimeout(function () { button.textContent = label; }, 1600);
      };
      if (!navigator.clipboard) return done(false);
      navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
    });
  });

  // The language menu is a plain <details>; this only closes it on an outside click or Escape.
  document.querySelectorAll("details.lang").forEach(function (menu) {
    document.addEventListener("click", function (event) {
      if (menu.open && !menu.contains(event.target)) menu.open = false;
    });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && menu.open) {
        menu.open = false;
        menu.querySelector("summary").focus();
      }
    });
  });

  var slots = document.querySelectorAll("[data-asset]");
  var versions = document.querySelectorAll("[data-version]");
  if (!slots.length && !versions.length) return;

  function megabytes(bytes) {
    return (bytes / 1048576).toFixed(bytes < 10485760 ? 1 : 0) + " MB";
  }

  fetch("https://api.github.com/repos/rokrokss/recly/releases?per_page=10", {
    headers: { Accept: "application/vnd.github+json" }
  })
    .then(function (response) { return response.ok ? response.json() : Promise.reject(response.status); })
    .then(function (releases) {
      releases = releases.filter(function (release) { return !release.draft; });
      if (!releases.length) return;

      // Each platform takes its file from the newest release that carries one.
      slots.forEach(function (slot) {
        var pattern = new RegExp(slot.dataset.asset);
        for (var i = 0; i < releases.length; i++) {
          var asset = releases[i].assets.find(function (a) { return pattern.test(a.name); });
          if (!asset) continue;
          slot.href = asset.browser_download_url;
          var size = slot.querySelector(".size");
          if (size) size.textContent = megabytes(asset.size);
          return;
        }
      });

      var latest = releases[0];
      versions.forEach(function (el) {
        el.textContent = latest.tag_name;
        if (el.tagName === "A") el.href = latest.html_url;
      });
    })
    .catch(function () { /* keep the static links */ });
})();
