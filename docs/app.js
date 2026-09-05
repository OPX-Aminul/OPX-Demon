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
})();
