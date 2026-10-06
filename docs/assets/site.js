// Progressive enhancement for recly.dev. Every link works without this file: download buttons
// point at the latest release until its asset URLs replace them.
(function () {
  "use strict";

  var ko = document.documentElement.lang === "ko";

  function copyText(text, done) {
    if (!navigator.clipboard) return done(false);
    navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
  }

  document.querySelectorAll("[data-copy]").forEach(function (button) {
    var label = button.textContent;
    button.addEventListener("click", function () {
      copyText(document.getElementById(button.dataset.copy).textContent.trim(), function (ok) {
        button.textContent = ok ? button.dataset.done : button.dataset.fail;
        setTimeout(function () { button.textContent = label; }, 1600);
      });
    });
  });

  // The guides' code blocks get the same Copy button as the landing page's commands.
  document.querySelectorAll(".prose pre").forEach(function (pre) {
    var block = document.createElement("div");
    block.className = "code-block";
    pre.parentNode.insertBefore(block, pre);
    block.appendChild(pre);
    var button = document.createElement("button");
    var label = ko ? "복사" : "Copy";
    button.type = "button";
    button.className = "btn btn-quiet copy";
    button.textContent = label;
    button.addEventListener("click", function () {
      copyText(pre.textContent.replace(/\n$/, ""), function (ok) {
        button.textContent = ok ? (ko ? "복사됨 ✓" : "Copied ✓") : (ko ? "복사 실패" : "Copy failed");
        setTimeout(function () { button.textContent = label; }, 1600);
      });
    });
    block.appendChild(button);
  });

  // The language menu and the small-screen menu are plain <details>; this only closes them on an
  // outside click or Escape.
  document.querySelectorAll("details.lang, details.menu").forEach(function (menu) {
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
  // A section link in the menu scrolls the page; close the menu behind it.
  document.querySelectorAll(".menu-panel a").forEach(function (link) {
    link.addEventListener("click", function () { link.closest("details").open = false; });
  });

  // The hero demo autoplays; with reduced motion asked for, it stops and offers its controls.
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    document.querySelectorAll(".demo video").forEach(function (video) {
      video.pause();
      video.controls = true;
    });
  }

  // The download card for the visitor's own device comes first. Without a match, the order stays.
  var downloads = document.querySelector(".downloads[data-yours-label]");
  if (downloads) {
    var ua = navigator.userAgent || "";
    var platform =
      /Android/i.test(ua) ? "android" :
      /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1) ? "ios" :
      /Macintosh|Mac OS X/.test(ua) ? "mac" :
      /Windows/.test(ua) ? "windows" : null;
    var card = platform && downloads.querySelector('[data-platform="' + platform + '"]');
    if (card) {
      downloads.insertBefore(card, downloads.firstElementChild);
      card.classList.add("is-yours");
      var yours = document.createElement("p");
      yours.className = "yours";
      yours.textContent = downloads.dataset.yoursLabel;
      card.insertBefore(yours, card.firstChild);
    }
  }

  var slots = document.querySelectorAll("[data-asset]");
  var versions = document.querySelectorAll("[data-version]");
  if (!slots.length && !versions.length) return;

  function megabytes(bytes) {
    return (bytes / 1048576).toFixed(bytes < 10485760 ? 1 : 0) + " MB";
  }

  fetch("https://api.github.com/repos/rokrokss/recly/releases?per_page=20", {
    headers: { Accept: "application/vnd.github+json" }
  })
    .then(function (response) { return response.ok ? response.json() : Promise.reject(response.status); })
    .then(function (releases) {
      // App releases are tagged v<version>-build.<n>; the events-v… releases are recly-events.
      var apps = releases.filter(function (release) { return !release.draft && /^v\d/.test(release.tag_name); });
      var stable = apps.filter(function (release) { return !release.prerelease; });
      if (stable.length) apps = stable;
      if (!apps.length) return;

      // Each file comes from the newest app release that carries one.
      slots.forEach(function (slot) {
        var pattern = new RegExp(slot.dataset.asset);
        for (var i = 0; i < apps.length; i++) {
          var asset = apps[i].assets.find(function (a) { return pattern.test(a.name); });
          if (!asset) continue;
          slot.href = asset.browser_download_url;
          var size = slot.querySelector(".size");
          if (size) size.textContent = megabytes(asset.size);
          return;
        }
      });

      var latest = apps[0];
      versions.forEach(function (el) {
        el.textContent = latest.tag_name;
        if (el.tagName === "A") el.href = latest.html_url;
      });
    })
    .catch(function () { /* keep the static links */ });
})();
