/* ============================================================
   OPX-Demon site — hash router, scroll reveal, counters
   ============================================================ */
(function () {
  "use strict";

  var ROUTES = ["", "modules", "screens", "download", "faq"];

  function $(sel, root) { return (root || document).querySelector(sel); }
  function $all(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }

  /* ---------- Router ---------- */
  var views = {
    "": $("#view-home"),
    modules: $("#view-modules"),
    screens: $("#view-screens"),
    download: $("#view-download"),
    faq: $("#view-faq")
  };

  var pathEl = $("#rb-path");
  var current = null;

  function routeFromHash() {
    var h = location.hash.replace(/^#\/?/, "");
    return ROUTES.indexOf(h) !== -1 ? h : "";
  }

  function markActive(route) {
    $all(".rb-nav a").forEach(function (a) {
      a.classList.toggle("active", a.getAttribute("data-route") === route);
    });
    if (pathEl) {
      pathEl.textContent = route ? route + "/" : "";
    }
  }

  function revealInView(scope) {
    $all(".reveal", scope).forEach(function (el) {
      var r = el.getBoundingClientRect();
      var visible = r.top < window.innerHeight * 0.92;
      if (visible && !el.classList.contains("in")) {
        setTimeout(function () { el.classList.add("in"); }, 60);
        el.dataset.revealed = "1";
      } else if (!visible && !el.dataset.revealed) {
        /* not yet visible: leave hidden for scroll reveal */
      }
      if (visible) { el.dataset.revealed = "1"; }
    });
  }

  function startCounters(scope) {
    $all("[data-count]", scope).forEach(function (el) {
      if (el.dataset.done) { return; }
      el.dataset.done = "1";
      var target = parseInt(el.getAttribute("data-count"), 10);
      var prefix = el.getAttribute("data-prefix") || "";
      var suffix = el.getAttribute("data-suffix") || "";
      if (isNaN(target)) { return; }
      var dur = 1400;
      var start = null;
      function tick(ts) {
        if (!start) { start = ts; }
        var p = Math.min((ts - start) / dur, 1);
        var eased = 1 - Math.pow(1 - p, 3);
        el.textContent = prefix + Math.round(target * eased) + suffix;
        if (p < 1) { requestAnimationFrame(tick); }
      }
      requestAnimationFrame(tick);
    });
    /* run only when visible */
  }

  function onScrollReveal() {
    $all(".reveal:not(.in)").forEach(function (el) {
      var r = el.getBoundingClientRect();
      if (r.top < window.innerHeight * 0.92) {
        el.classList.add("in");
      }
    });
  }

  function applyRoute() {
    var route = routeFromHash();
    if (route === current) {
      markActive(route);
      return;
    }
    var from = views[current];
    var to = views[route];

    if (from) {
      from.classList.add("leaving");
      setTimeout(function () {
        from.classList.remove("active", "leaving");
        enter(to);
      }, 260);
    } else {
      enter(to);
    }
    current = route;
    markActive(route);
  }

  function enter(view) {
    if (!view) { return; }
    view.classList.remove("leaving");
    view.classList.add("active");
    window.scrollTo({ top: 0, behavior: "auto" });
    /* Re-arm reveals inside the entering view */
    $all(".reveal", view).forEach(function (el) { el.classList.remove("in"); });
    revealInView(view);
    startCounters(view);
    setTimeout(onScrollReveal, 400);
  }

  window.addEventListener("hashchange", applyRoute);
  window.addEventListener("DOMContentLoaded", applyRoute);
  if (document.readyState !== "loading") { applyRoute(); }

  /* Reload button spins and replays the view */
  var reloadBtn = $("#rb-reload");
  if (reloadBtn) {
    reloadBtn.addEventListener("click", function () {
      var from = views[current];
      if (from) {
        from.classList.remove("leaving");
        /* force reflow so the view animation restarts */
        void from.offsetWidth;
        from.classList.add("active");
        $all(".reveal", from).forEach(function (el) { el.classList.remove("in"); });
        revealInView(from);
        startCounters(from);
      }
    });
  }

  /* ---------- Scroll reveal ---------- */
  window.addEventListener("scroll", onScrollReveal, { passive: true });
  setTimeout(onScrollReveal, 350);

  /* ---------- Spotlight follows cursor on module cards ---------- */
  document.addEventListener("mousemove", throttle(function (e) {
    $all(".module").forEach(function (card) {
      var r = card.getBoundingClientRect();
      card.style.setProperty("--mx", ((e.clientX - r.left) / r.width * 100) + "%");
      card.style.setProperty("--my", ((e.clientY - r.top) / r.height * 100) + "%");
    });
  }, 60));

  function throttle(fn, ms) {
    var last = 0;
    return function () {
      var now = Date.now();
      if (now - last >= ms) {
        last = now;
        fn.apply(null, arguments);
      }
    };
  }

  /* ---------- Tilt on hero orb ---------- */
  var orb = $(".logo-orb");
  if (orb) {
    var wrap = orb.parentElement;
    wrap.addEventListener("mousemove", function (e) {
      var r = wrap.getBoundingClientRect();
      var dx = (e.clientX - r.left) / r.width - 0.5;
      var dy = (e.clientY - r.top) / r.height - 0.5;
      orb.style.transform = "perspective(900px) rotateY(" + (dx * 14) + "deg) rotateX(" + (-dy * 14) + "deg)";
    });
    wrap.addEventListener("mouseleave", function () {
      orb.style.transform = "";
    });
  }

  /* ---------- Live GitHub Releases ---------- */
  var GH_API = "https://api.github.com/repos/OPX-Aminul/OPX-Demon/releases?per_page=30";
  var GH_LATEST_PAGE = "https://github.com/OPX-Aminul/OPX-Demon/releases/latest";
  var SKIP_TAGS = { "chroot-main": 1, "all-core-file": 1 };

  function isAppRelease(rel) {
    if (!rel || rel.draft) { return false; }
    var tag = (rel.tag_name || "").toLowerCase();
    if (SKIP_TAGS[tag]) { return false; }
    if (/^chroot/i.test(rel.tag_name || "")) { return false; }
    return true;
  }

  function pickApk(rel) {
    var assets = (rel && rel.assets) || [];
    var i, a, releaseApk = null, anyApk = null;
    for (i = 0; i < assets.length; i++) {
      a = assets[i];
      if (!a || !/\.apk$/i.test(a.name || "")) { continue; }
      if (/release/i.test(a.name) && !/debug/i.test(a.name)) {
        releaseApk = a;
        break;
      }
      if (!anyApk) { anyApk = a; }
    }
    return releaseApk || anyApk || null;
  }

  function fmtSize(bytes) {
    var n = Number(bytes) || 0;
    if (n >= 1048576) { return (n / 1048576).toFixed(1) + " MB"; }
    if (n >= 1024) { return (n / 1024).toFixed(0) + " KB"; }
    return n + " B";
  }

  function fmtDate(iso) {
    if (!iso) { return ""; }
    var d = new Date(iso);
    if (isNaN(d.getTime())) { return ""; }
    return d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
  }

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function changelogHtml(body) {
    var text = String(body || "").replace(/\r\n/g, "\n").trim();
    if (!text) { return ""; }
    var lines = text.split("\n").filter(function (ln) { return ln.trim(); }).slice(0, 8);
    return lines.map(function (ln) {
      return "<li>" + esc(ln.replace(/^[-*]\s*/, "")) + "</li>";
    }).join("");
  }

  function setText(id, value) {
    var el = document.getElementById(id);
    if (el) { el.textContent = value; }
  }

  function setHref(id, url) {
    var el = document.getElementById(id);
    if (el && url) { el.href = url; }
  }

  function applyLatest(rel, apk) {
    var tag = rel.tag_name || "latest";
    var name = rel.name || tag;
    var apkUrl = apk ? apk.browser_download_url : (rel.html_url || GH_LATEST_PAGE);
    var size = apk ? fmtSize(apk.size) : "";
    var when = fmtDate(rel.published_at);
    var badge = tag + " · LATEST RELEASE OUT NOW";
    var verLine = tag + (when ? " · " + when : "") + (size ? " · " + size : "");
    var notes = changelogHtml(rel.body);

    setText("hero-version", badge);
    setHref("hero-dl", apkUrl);
    var heroDl = document.getElementById("hero-dl");
    if (heroDl) { heroDl.textContent = "Download " + tag + " APK — Free"; }

    setText("cta-version", "Download OPX-Demon " + tag);
    setHref("cta-dl", apkUrl);

    setText("dl-version", verLine);
    setText("dl-note", name + (apk ? " · " + apk.name : "") + ". Signed APK from GitHub Releases. On first launch the in-app installer fetches the Debian trixie arm64 core (~1 GB) — Wi-Fi is recommended.");
    setHref("dl-main", apkUrl);
    var dlMain = document.getElementById("dl-main");
    if (dlMain) { dlMain.textContent = "Download " + tag + " APK — Free"; }

    var log = document.getElementById("dl-changelog");
    if (log) {
      log.innerHTML = notes
        ? "<ul>" + notes + "</ul>"
        : "<p>See the full notes on GitHub Releases.</p>";
    }

    $all("a.dl-link").forEach(function (a) {
      if (a.id !== "hero-dl" && a.id !== "cta-dl" && a.id !== "dl-main") {
        a.href = apkUrl;
      }
    });

    var ld = document.getElementById("ld-app");
    if (ld) {
      try {
        var data = JSON.parse(ld.textContent);
        data.softwareVersion = tag.replace(/^v/i, "");
        data.downloadUrl = apkUrl;
        if (apk && apk.size) { data.fileSize = fmtSize(apk.size); }
        ld.textContent = JSON.stringify(data);
      } catch (e) { /* leave existing JSON-LD */ }
    }
  }

  function renderList(releases) {
    var host = document.getElementById("release-list");
    if (!host) { return; }
    if (!releases.length) {
      host.innerHTML = '<div class="release-skel glass">No app releases found. <a href="' + GH_LATEST_PAGE + '" target="_blank" rel="noopener">Open GitHub Releases</a></div>';
      return;
    }
    host.innerHTML = releases.map(function (rel, idx) {
      var apk = pickApk(rel);
      var tag = esc(rel.tag_name || "");
      var title = esc(rel.name || rel.tag_name || "");
      var when = esc(fmtDate(rel.published_at));
      var apkUrl = apk ? esc(apk.browser_download_url) : esc(rel.html_url || GH_LATEST_PAGE);
      var size = apk ? esc(fmtSize(apk.size)) : "";
      var apkName = apk ? esc(apk.name) : "Open release";
      var latest = idx === 0 ? '<span class="rel-badge">LATEST</span>' : "";
      var notes = changelogHtml(rel.body);
      return (
        '<article class="release-card glass reveal in">' +
          '<div class="rel-top">' +
            '<div>' +
              '<h3>' + title + latest + '</h3>' +
              '<div class="rel-meta"><span>' + tag + '</span>' +
                (when ? '<span>' + when + '</span>' : '') +
                (size ? '<span>' + size + '</span>' : '') +
              '</div>' +
            '</div>' +
            '<a class="btn btn-primary rel-dl" href="' + apkUrl + '">' + apkName + '</a>' +
          '</div>' +
          (notes ? '<ul class="rel-notes">' + notes + '</ul>' : '') +
        '</article>'
      );
    }).join("");
  }

  function loadReleases() {
    fetch(GH_API, { headers: { Accept: "application/vnd.github+json" }, cache: "no-store" })
      .then(function (res) {
        if (!res.ok) { throw new Error("GitHub API " + res.status); }
        return res.json();
      })
      .then(function (data) {
        var list = (data || []).filter(isAppRelease);
        var withApk = list.filter(function (rel) { return pickApk(rel); });
        var latest = withApk[0] || list[0];
        if (latest) { applyLatest(latest, pickApk(latest)); }
        renderList(list.slice(0, 12));
      })
      .catch(function () {
        setText("hero-version", "Latest · see GitHub");
        setText("dl-version", "open GitHub Releases");
        setText("dl-note", "Could not reach the GitHub API from this browser. Use the button below — it always points at the newest release page.");
        var log = document.getElementById("dl-changelog");
        if (log) { log.textContent = "Live fetch unavailable. Download still works via GitHub."; }
        var host = document.getElementById("release-list");
        if (host) {
          host.innerHTML = '<div class="release-skel glass">Could not load the live list. <a href="' + GH_LATEST_PAGE + '" target="_blank" rel="noopener">All releases on GitHub</a></div>';
        }
      });
  }

  loadReleases();
})();
