(() => {
  "use strict";

  const TIER_ICONS = {
    mini: "🚕",
    ev: "⚡",
    sedan: "🚘",
    suv: "🚙",
  };

  const TIER_COLORS = {
    mini: "#38bdf8",
    ev: "#10b981",
    sedan: "#a855f7",
    suv: "#f59e0b",
  };

  const appState = {
    city: null,
    tiers: {},
    sim: null,
    prevCabs: {},
    currCabs: {},
    lastFetchTs: performance.now(),
    pollIntervalMs: 250,
    pickupId: "N2_3", // Central Station
    dropoffId: "N0_6", // Skyline Airport
    selectedTier: "mini",
    paymentMethod: "wallet",
    promoCode: "",
    estimate: null,
    pickMode: "pickup",
    hoveredNode: null,
    selectedStars: 5,
    selectedTags: new Set(),
    selectedTip: 0,
    lastChatCount: -1,
    lastRideStatus: null,
    lastRideId: null,
  };

  // DOM Elements
  const canvas = document.getElementById("cityCanvas");
  const ctx = canvas.getContext("2d");
  const tooltip = document.getElementById("mapTooltip");

  const hudClock = document.getElementById("hudClock");
  const hudFleet = document.getElementById("hudFleet");
  const hudTraffic = document.getElementById("hudTraffic");
  const hudSurge = document.getElementById("hudSurge");
  const hudWallet = document.getElementById("hudWallet");

  const pickupSelect = document.getElementById("pickupSelect");
  const dropoffSelect = document.getElementById("dropoffSelect");
  const pickModePickup = document.getElementById("pickModePickup");
  const pickModeDropoff = document.getElementById("pickModeDropoff");

  const bookingView = document.getElementById("bookingView");
  const activeRideView = document.getElementById("activeRideView");
  const receiptView = document.getElementById("receiptView");

  const tiersList = document.getElementById("tiersList");
  const bookRideBtn = document.getElementById("bookRideBtn");
  const bookingError = document.getElementById("bookingError");

  // Helpers
  async function api(url, options = {}) {
    const res = await fetch(url, {
      headers: { "Content-Type": "application/json" },
      ...options,
    });
    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.detail || "Request failed");
    }
    return data;
  }

  let toastTimer = null;
  function showToast(msg) {
    const el = document.getElementById("toast");
    el.textContent = msg;
    el.classList.remove("hidden");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.add("hidden"), 2800);
  }

  function formatMoney(val) {
    return `$${Number(val || 0).toFixed(2)}`;
  }

  function initials(name) {
    return (name || "DR")
      .split(" ")
      .map((p) => p[0])
      .join("")
      .slice(0, 2)
      .toUpperCase();
  }

  // Initialization
  async function init() {
    const data = await api("/api/init");
    appState.city = data.city;
    appState.tiers = data.tiers;
    populateLocationSelects();
    applySimState(data.state);
    bindEvents();
    await refreshEstimate();
    requestAnimationFrame(renderLoop);
    setInterval(pollState, appState.pollIntervalMs);
  }

  function populateLocationSelects() {
    const nodes = [...appState.city.nodes].sort((a, b) => {
      if (a.is_landmark !== b.is_landmark) return a.is_landmark ? -1 : 1;
      return a.name.localeCompare(b.name);
    });

    const buildOpts = (sel) => {
      sel.innerHTML = "";
      nodes.forEach((n) => {
        const opt = document.createElement("option");
        opt.value = n.id;
        opt.textContent = n.is_landmark ? `★ ${n.name}` : n.name;
        sel.appendChild(opt);
      });
    };

    buildOpts(pickupSelect);
    buildOpts(dropoffSelect);
    pickupSelect.value = appState.pickupId;
    dropoffSelect.value = appState.dropoffId;
  }

  function bindEvents() {
    // Tabs
    document.querySelectorAll(".tab-btn").forEach((btn) => {
      btn.addEventListener("click", () => switchTab(btn.dataset.tab));
    });

    document.getElementById("walletPillBtn").addEventListener("click", () => switchTab("history"));

    // Speed controls
    document.querySelectorAll(".speed-btn").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const speed = Number(btn.dataset.speed);
        const st = await api("/api/sim/controls", {
          method: "POST",
          body: JSON.stringify({ speed }),
        });
        applySimState(st);
      });
    });

    // Map pick mode
    pickModePickup.addEventListener("click", () => setPickMode("pickup"));
    pickModeDropoff.addEventListener("click", () => setPickMode("dropoff"));

    // Location selectors
    pickupSelect.addEventListener("change", () => {
      appState.pickupId = pickupSelect.value;
      refreshEstimate();
    });
    dropoffSelect.addEventListener("change", () => {
      appState.dropoffId = dropoffSelect.value;
      refreshEstimate();
    });

    document.getElementById("swapLocBtn").addEventListener("click", () => {
      const tmp = appState.pickupId;
      appState.pickupId = appState.dropoffId;
      appState.dropoffId = tmp;
      pickupSelect.value = appState.pickupId;
      dropoffSelect.value = appState.dropoffId;
      refreshEstimate();
    });

    // Quick routes
    document.querySelectorAll(".route-chip").forEach((chip) => {
      chip.addEventListener("click", () => {
        appState.pickupId = chip.dataset.from;
        appState.dropoffId = chip.dataset.to;
        pickupSelect.value = appState.pickupId;
        dropoffSelect.value = appState.dropoffId;
        refreshEstimate();
      });
    });

    // Promo
    document.getElementById("applyPromoBtn").addEventListener("click", () => {
      appState.promoCode = document.getElementById("promoInput").value.trim().toUpperCase();
      refreshEstimate();
    });

    document.querySelectorAll(".promo-tag").forEach((tag) => {
      tag.addEventListener("click", () => {
        const code = tag.dataset.code;
        document.getElementById("promoInput").value = code;
        appState.promoCode = code;
        refreshEstimate();
      });
    });

    // Payment pills
    document.querySelectorAll(".pay-pill").forEach((pill) => {
      pill.addEventListener("click", () => {
        document.querySelectorAll(".pay-pill").forEach((p) => p.classList.remove("active"));
        pill.classList.add("active");
        appState.paymentMethod = pill.dataset.pay;
      });
    });

    // Book Ride
    bookRideBtn.addEventListener("click", async () => {
      bookingError.classList.add("hidden");
      try {
        const res = await api("/api/rides/book", {
          method: "POST",
          body: JSON.stringify({
            pickup_node_id: appState.pickupId,
            dropoff_node_id: appState.dropoffId,
            tier: appState.selectedTier,
            payment_method: appState.paymentMethod,
            promo_code: appState.promoCode || null,
          }),
        });
        applySimState(res.state);
        showToast("Ride requested! Searching nearby drivers...");
      } catch (err) {
        bookingError.textContent = err.message;
        bookingError.classList.remove("hidden");
      }
    });

    // OTP Verify
    document.getElementById("verifyOtpBtn").addEventListener("click", async () => {
      const ride = appState.sim?.active_ride;
      if (!ride) return;
      const otpErr = document.getElementById("otpError");
      otpErr.classList.add("hidden");
      try {
        const otp = document.getElementById("otpInput").value.trim();
        const res = await api(`/api/rides/${ride.id}/verify-otp`, {
          method: "POST",
          body: JSON.stringify({ otp }),
        });
        document.getElementById("otpInput").value = "";
        applySimState(res.state);
        showToast("Trip started! Enjoy your ride.");
      } catch (err) {
        otpErr.textContent = err.message;
        otpErr.classList.remove("hidden");
      }
    });

    document.getElementById("autoFillOtpBtn").addEventListener("click", async () => {
      const ride = appState.sim?.active_ride;
      if (!ride) return;
      document.getElementById("otpInput").value = ride.otp;
      document.getElementById("verifyOtpBtn").click();
    });

    // Chat
    document.getElementById("sendChatBtn").addEventListener("click", sendChatMessage);
    document.getElementById("chatInput").addEventListener("keydown", (e) => {
      if (e.key === "Enter") sendChatMessage();
    });
    document.querySelectorAll(".quick-msg-btn").forEach((btn) => {
      btn.addEventListener("click", () => sendChatMessage(btn.dataset.msg));
    });

    // Active ride actions
    document.getElementById("shareRideBtn").addEventListener("click", () => {
      const ride = appState.sim?.active_ride;
      if (ride) {
        showToast(`Live tracking link copied for ${ride.id} (${ride.pickup_name} → ${ride.dropoff_name})`);
      }
    });

    document.getElementById("sosBtn").addEventListener("click", () => {
      showToast("SOS Alert simulated: Emergency contact & SwiftCab Safety Team notified.");
    });

    document.getElementById("cancelRideBtn").addEventListener("click", async () => {
      const ride = appState.sim?.active_ride;
      if (!ride) return;
      if (ride.cancel_fee_applies && !confirm("Driver is nearby or waiting. Cancelling now incurs a $2.50 fee. Continue?")) {
        return;
      }
      try {
        const res = await api(`/api/rides/${ride.id}/cancel`, {
          method: "POST",
          body: JSON.stringify({ reason: "Cancelled by rider" }),
        });
        applySimState(res.state);
        showToast("Ride cancelled.");
      } catch (err) {
        showToast(err.message);
      }
    });

    // Rating & Tip
    document.querySelectorAll(".star-btn").forEach((btn) => {
      btn.addEventListener("click", () => {
        appState.selectedStars = Number(btn.dataset.star);
        document.querySelectorAll(".star-btn").forEach((s) => {
          s.classList.toggle("active", Number(s.dataset.star) <= appState.selectedStars);
        });
      });
    });

    document.querySelectorAll(".feedback-tag").forEach((btn) => {
      btn.addEventListener("click", () => {
        const tag = btn.dataset.tag;
        if (appState.selectedTags.has(tag)) {
          appState.selectedTags.delete(tag);
          btn.classList.remove("active");
        } else {
          appState.selectedTags.add(tag);
          btn.classList.add("active");
        }
      });
    });

    document.querySelectorAll(".tip-pill").forEach((btn) => {
      btn.addEventListener("click", () => {
        document.querySelectorAll(".tip-pill").forEach((p) => p.classList.remove("active"));
        btn.classList.add("active");
        appState.selectedTip = Number(btn.dataset.tip);
      });
    });

    document.getElementById("submitRatingBtn").addEventListener("click", async () => {
      const ride = appState.sim?.active_ride;
      if (!ride) return;
      try {
        await api(`/api/rides/${ride.id}/rate`, {
          method: "POST",
          body: JSON.stringify({
            stars: appState.selectedStars,
            tags: [...appState.selectedTags],
            tip: appState.selectedTip,
          }),
        });
        const cleared = await api("/api/rides/clear", { method: "POST" });
        applySimState(cleared);
        await refreshEstimate();
        showToast("Thank you for your feedback!");
      } catch (err) {
        showToast(err.message);
      }
    });

    document.getElementById("bookAnotherBtn").addEventListener("click", async () => {
      const cleared = await api("/api/rides/clear", { method: "POST" });
      applySimState(cleared);
      await refreshEstimate();
    });

    // Simulator Controls
    document.querySelectorAll("#trafficControls .seg-btn").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const st = await api("/api/sim/controls", {
          method: "POST",
          body: JSON.stringify({ traffic_level: btn.dataset.traffic }),
        });
        applySimState(st);
        refreshEstimate();
      });
    });

    document.querySelectorAll("#surgeControls .seg-btn").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const st = await api("/api/sim/controls", {
          method: "POST",
          body: JSON.stringify({ surge_mode: btn.dataset.surge }),
        });
        applySimState(st);
        refreshEstimate();
      });
    });

    document.getElementById("spawnCabBtn").addEventListener("click", async () => {
      const res = await api("/api/sim/spawn-cab", {
        method: "POST",
        body: JSON.stringify({}),
      });
      applySimState(res.state);
      refreshEstimate();
      showToast(`Spawned ${res.cab.id} (${res.cab.vehicle_model})`);
    });

    document.getElementById("spawnAtPickupBtn").addEventListener("click", async () => {
      const res = await api("/api/sim/spawn-cab", {
        method: "POST",
        body: JSON.stringify({ tier: appState.selectedTier, node_id: appState.pickupId }),
      });
      applySimState(res.state);
      refreshEstimate();
      showToast(`Spawned ${res.cab.id} right at Pickup!`);
    });

    document.getElementById("toggleDeclineBtn").addEventListener("click", async () => {
      const next = !appState.sim?.simulate_driver_decline;
      const st = await api("/api/sim/controls", {
        method: "POST",
        body: JSON.stringify({ simulate_driver_decline: next }),
      });
      applySimState(st);
    });

    document.getElementById("resetSimBtn").addEventListener("click", async () => {
      const st = await api("/api/sim/reset", { method: "POST" });
      applySimState(st);
      refreshEstimate();
      showToast("Simulation world reset.");
    });

    // Wallet top-up
    document.querySelectorAll(".topup-btn").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const amount = Number(btn.dataset.amount);
        const res = await api("/api/wallet/topup", {
          method: "POST",
          body: JSON.stringify({ amount }),
        });
        applySimState(res.state);
        showToast(`Added ${formatMoney(amount)} to Wallet`);
      });
    });

    // Canvas interaction
    canvas.addEventListener("mousemove", handleCanvasHover);
    canvas.addEventListener("mouseleave", () => {
      appState.hoveredNode = null;
      tooltip.classList.add("hidden");
    });
    canvas.addEventListener("click", handleCanvasClick);
  }

  function switchTab(tabName) {
    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.toggle("active", b.dataset.tab === tabName));
    document.querySelectorAll(".tab-content").forEach((c) => c.classList.toggle("active", c.id === `tab-${tabName}`));
  }

  function setPickMode(mode) {
    appState.pickMode = mode;
    pickModePickup.classList.toggle("active-pickup", mode === "pickup");
    pickModeDropoff.classList.toggle("active-dropoff", mode === "dropoff");
  }

  async function sendChatMessage(overrideText) {
    const ride = appState.sim?.active_ride;
    if (!ride) return;
    const input = document.getElementById("chatInput");
    const msg = typeof overrideText === "string" ? overrideText : input.value.trim();
    if (!msg) return;
    if (typeof overrideText !== "string") input.value = "";
    try {
      const res = await api(`/api/rides/${ride.id}/chat`, {
        method: "POST",
        body: JSON.stringify({ message: msg }),
      });
      if (appState.sim) {
        appState.sim.active_ride = res.ride;
        renderRideView();
      }
    } catch (err) {
      showToast(err.message);
    }
  }

  async function refreshEstimate() {
    if (appState.sim?.active_ride) return;
    if (appState.pickupId === appState.dropoffId) {
      bookingError.textContent = "Pickup and drop-off locations must be different.";
      bookingError.classList.remove("hidden");
      bookRideBtn.disabled = true;
      return;
    }
    bookingError.classList.add("hidden");
    bookRideBtn.disabled = false;

    try {
      const est = await api("/api/estimate", {
        method: "POST",
        body: JSON.stringify({
          pickup_node_id: appState.pickupId,
          dropoff_node_id: appState.dropoffId,
          promo_code: appState.promoCode || null,
        }),
      });
      appState.estimate = est;
      renderEstimate();
    } catch (err) {
      bookingError.textContent = err.message;
      bookingError.classList.remove("hidden");
    }
  }

  function renderEstimate() {
    const est = appState.estimate;
    if (!est) return;

    document.getElementById("routeSummaryStrip").classList.remove("hidden");
    document.getElementById("estDistance").textContent = `${est.distance_km.toFixed(2)} km`;
    document.getElementById("estDuration").textContent = `${est.duration_min.toFixed(0)} min`;
    document.getElementById("estSurge").textContent = `${est.surge_multiplier.toFixed(2)}x`;

    const surgeNotice = document.getElementById("surgeNotice");
    if (est.surge_multiplier > 1.05) {
      surgeNotice.textContent = `⚡ ${est.surge_multiplier.toFixed(2)}x Surge`;
      surgeNotice.classList.remove("hidden");
    } else {
      surgeNotice.classList.add("hidden");
    }

    tiersList.innerHTML = "";
    est.tiers.forEach((tier) => {
      const card = document.createElement("div");
      card.className = `tier-card ${appState.selectedTier === tier.id ? "selected" : ""}`;
      const etaText = tier.pickup_eta_min !== null ? `${tier.pickup_eta_min} min away` : "No cabs free";
      card.innerHTML = `
        <div class="tier-left">
          <div class="tier-icon" style="background:${tier.color}22; border:1px solid ${tier.color}55;">
            ${TIER_ICONS[tier.id] || "🚕"}
          </div>
          <div>
            <div class="tier-name">
              ${tier.name}
              <span class="tier-seats">👤 ${tier.seats}</span>
            </div>
            <div class="tier-meta">${tier.tagline} • ${tier.available_cabs} nearby</div>
          </div>
        </div>
        <div class="tier-right">
          <div class="tier-price mono">${formatMoney(tier.estimated_fare)}</div>
          <div class="tier-eta ${tier.pickup_eta_min === null ? "none" : ""}">${etaText}</div>
        </div>
      `;
      card.addEventListener("click", () => {
        appState.selectedTier = tier.id;
        renderEstimate();
      });
      tiersList.appendChild(card);
    });

    const chosen = est.tiers.find((t) => t.id === appState.selectedTier) || est.tiers[0];
    bookRideBtn.textContent = `Book ${chosen.name} — ${formatMoney(chosen.estimated_fare)}`;
  }

  async function pollState() {
    try {
      const st = await api("/api/state");
      applySimState(st);
    } catch {
      // Transient fetch failure ignored during polling
    }
  }

  function applySimState(st) {
    // Store cab snapshots for 60fps interpolation
    appState.prevCabs = { ...appState.currCabs };
    const nextCabs = {};
    st.cabs.forEach((c) => {
      nextCabs[c.id] = c;
      if (!appState.prevCabs[c.id]) {
        appState.prevCabs[c.id] = c;
      }
    });
    appState.currCabs = nextCabs;
    appState.lastFetchTs = performance.now();
    appState.sim = st;

    // HUD
    hudClock.textContent = st.sim_time;
    hudFleet.textContent = `${st.fleet_summary.available}/${st.fleet_summary.total}`;
    hudTraffic.textContent = st.traffic_level.toUpperCase();
    hudTraffic.className = `hud-value badge-${st.traffic_level}`;

    const maxSurge = st.surge_zones.length
      ? Math.max(...st.surge_zones.map((z) => z.multiplier))
      : 1.0;
    hudSurge.textContent =
      st.surge_mode === "flat"
        ? "1.0x FLAT"
        : maxSurge > 1.0
        ? `⚡ up to ${maxSurge.toFixed(1)}x`
        : "1.0x NORMAL";
    hudWallet.textContent = formatMoney(st.wallet_balance);

    // Speed buttons
    document.querySelectorAll(".speed-btn").forEach((b) => {
      b.classList.toggle("active", Number(b.dataset.speed) === st.sim_speed);
    });

    // Simulator tab controls
    document.querySelectorAll("#trafficControls .seg-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.traffic === st.traffic_level);
    });
    document.querySelectorAll("#surgeControls .seg-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.surge === st.surge_mode);
    });
    const declineBtn = document.getElementById("toggleDeclineBtn");
    declineBtn.textContent = `Simulate Next Decline: ${st.simulate_driver_decline ? "ON" : "OFF"}`;

    renderRideView();
    renderSimTab();
    renderHistoryTab();
  }

  function renderRideView() {
    const ride = appState.sim?.active_ride;
    if (!ride) {
      bookingView.classList.remove("hidden");
      activeRideView.classList.add("hidden");
      receiptView.classList.add("hidden");
      appState.lastRideId = null;
      appState.lastRideStatus = null;
      appState.lastChatCount = -1;
      return;
    }

    if (ride.status === "COMPLETED" || ride.status === "CANCELLED") {
      bookingView.classList.add("hidden");
      activeRideView.classList.add("hidden");
      receiptView.classList.remove("hidden");
      renderReceiptView(ride);
      return;
    }

    bookingView.classList.add("hidden");
    activeRideView.classList.remove("hidden");
    receiptView.classList.add("hidden");

    const titleEl = document.getElementById("rideStatusTitle");
    const subEl = document.getElementById("rideStatusSub");
    const meterEl = document.getElementById("rideLiveMeter");
    const progressEl = document.getElementById("rideProgressBar");
    const leftMetric = document.getElementById("activeMetricLeft");
    const rightMetric = document.getElementById("activeMetricRight");
    const searchingBox = document.getElementById("searchingBox");
    const driverCard = document.getElementById("driverCard");
    const otpVerifyBox = document.getElementById("otpVerifyBox");

    document.getElementById("activePickupLbl").textContent = ride.pickup_name;
    document.getElementById("activeDropoffLbl").textContent = ride.dropoff_name;
    meterEl.textContent = formatMoney(ride.live_fare);

    if (ride.status === "SEARCHING") {
      titleEl.textContent = "Searching for drivers...";
      subEl.textContent = `Matching nearest ${ride.tier_name}`;
      progressEl.style.width = "12%";
      leftMetric.textContent = "Dispatching...";
      rightMetric.textContent = `${ride.estimated_distance_km.toFixed(1)} km trip`;
      searchingBox.classList.remove("hidden");
      document.getElementById("searchingTierName").textContent = ride.tier_name;
      driverCard.classList.add("hidden");
      otpVerifyBox.classList.add("hidden");
    } else if (ride.status === "DRIVER_ASSIGNED") {
      titleEl.textContent = "Driver is on the way";
      subEl.textContent = `${ride.cab.driver_name} • ${ride.pickup_eta_min.toFixed(1)} min away`;
      progressEl.style.width = `${Math.max(5, ride.pickup_progress * 100)}%`;
      leftMetric.textContent = `Pickup ETA: ${ride.pickup_eta_min.toFixed(1)} min`;
      rightMetric.textContent = `${ride.pickup_distance_km.toFixed(2)} km away`;
      searchingBox.classList.add("hidden");
      driverCard.classList.remove("hidden");
      otpVerifyBox.classList.add("hidden");
    } else if (ride.status === "ARRIVED") {
      titleEl.textContent = "Driver has arrived!";
      subEl.textContent = `Share PIN ${ride.otp} with ${ride.cab.driver_name}`;
      progressEl.style.width = "100%";
      leftMetric.textContent = "Waiting at Pickup";
      rightMetric.textContent = `PIN: ${ride.otp}`;
      searchingBox.classList.add("hidden");
      driverCard.classList.remove("hidden");
      otpVerifyBox.classList.remove("hidden");
    } else if (ride.status === "IN_TRIP") {
      titleEl.textContent = "On trip to destination";
      subEl.textContent = `Heading to ${ride.dropoff_name}`;
      progressEl.style.width = `${Math.max(4, ride.trip_progress * 100)}%`;
      const remKm = Math.max(0, ride.estimated_distance_km - ride.distance_covered_km);
      leftMetric.textContent = `Elapsed: ${ride.trip_elapsed_min.toFixed(1)} min`;
      rightMetric.textContent = `${remKm.toFixed(2)} km left`;
      searchingBox.classList.add("hidden");
      driverCard.classList.remove("hidden");
      otpVerifyBox.classList.add("hidden");
    }

    if (ride.cab) {
      document.getElementById("driverAvatar").textContent = initials(ride.cab.driver_name);
      document.getElementById("driverName").textContent = ride.cab.driver_name;
      document.getElementById("driverRating").textContent = `★ ${ride.cab.driver_rating.toFixed(2)}`;
      document.getElementById("driverVehicle").textContent = `${ride.cab.vehicle_model} • ${ride.cab.license_plate}`;
      document.getElementById("rideOtpDisplay").textContent = ride.otp;
    }

    const cancelBtn = document.getElementById("cancelRideBtn");
    cancelBtn.classList.toggle("hidden", ride.status === "IN_TRIP");

    // Chat messages
    if (appState.lastChatCount !== ride.chat_messages.length || appState.lastRideId !== ride.id) {
      appState.lastChatCount = ride.chat_messages.length;
      const chatBox = document.getElementById("chatMessages");
      chatBox.innerHTML = "";
      ride.chat_messages.forEach((m) => {
        const div = document.createElement("div");
        div.className = `chat-msg ${m.sender}`;
        div.textContent = m.text;
        chatBox.appendChild(div);
      });
      chatBox.scrollTop = chatBox.scrollHeight;
    }

    appState.lastRideId = ride.id;
    appState.lastRideStatus = ride.status;
  }

  function renderReceiptView(ride) {
    const banner = document.getElementById("receiptBanner");
    const isCancel = ride.status === "CANCELLED";
    banner.classList.toggle("cancelled", isCancel);
    document.getElementById("receiptIcon").textContent = isCancel ? "✕" : "✓";
    document.getElementById("receiptTitle").textContent = isCancel ? "Ride Cancelled" : "Trip Completed!";
    document.getElementById("receiptSubtitle").textContent = isCancel
      ? ride.cancellation_reason || "Ride was cancelled"
      : `${ride.tier_name} • Paid via ${ride.payment_method.toUpperCase()}`;

    document.getElementById("recPickup").textContent = ride.pickup_name;
    document.getElementById("recDropoff").textContent = ride.dropoff_name;

    const bd = ride.fare_breakdown || {};
    const breakdownEl = document.getElementById("receiptBreakdown");
    if (isCancel) {
      breakdownEl.innerHTML = `
        <div class="receipt-line"><span>Cancellation Fee</span><span class="mono">${formatMoney(ride.cancellation_fee)}</span></div>
        <div class="receipt-line total"><span>Total Charged</span><span class="mono">${formatMoney(ride.cancellation_fee)}</span></div>
      `;
    } else {
      breakdownEl.innerHTML = `
        <div class="receipt-line"><span>Base Fare</span><span class="mono">${formatMoney(bd.base_fare)}</span></div>
        <div class="receipt-line"><span>Distance (${ride.estimated_distance_km.toFixed(2)} km)</span><span class="mono">${formatMoney(bd.distance_fare)}</span></div>
        <div class="receipt-line"><span>Time Charge</span><span class="mono">${formatMoney(bd.time_fare)}</span></div>
        ${bd.surge_amount > 0 ? `<div class="receipt-line"><span>Surge (${ride.surge_multiplier}x)</span><span class="mono">${formatMoney(bd.surge_amount)}</span></div>` : ""}
        ${bd.waiting_fee > 0 ? `<div class="receipt-line"><span>Waiting Fee</span><span class="mono">${formatMoney(bd.waiting_fee)}</span></div>` : ""}
        ${bd.promo_discount > 0 ? `<div class="receipt-line"><span>Promo (${ride.promo_code})</span><span class="mono">-${formatMoney(bd.promo_discount)}</span></div>` : ""}
        <div class="receipt-line total"><span>Total Fare</span><span class="mono">${formatMoney(bd.total_fare)}</span></div>
      `;
    }

    const showRating = !isCancel && ride.rating_given === null;
    document.getElementById("ratingSection").classList.toggle("hidden", !showRating);
    document.getElementById("bookAnotherBtn").classList.toggle("hidden", showRating);
  }

  function renderSimTab() {
    const st = appState.sim;
    if (!st) return;
    document.getElementById("fleetCountLbl").textContent = st.cabs.length;

    const fleetEl = document.getElementById("fleetList");
    fleetEl.innerHTML = "";
    st.cabs.forEach((c) => {
      const item = document.createElement("div");
      item.className = "fleet-item";
      item.innerHTML = `
        <div>
          <strong class="mono">${c.id}</strong>
          <span style="color:${TIER_COLORS[c.tier]}">• ${c.tier.toUpperCase()}</span>
          <div style="color:#94a3b8;font-size:0.7rem">${c.driver_name} • ${c.vehicle_model}</div>
        </div>
        <span class="fleet-status st-${c.status}">${c.status.replace(/_/g, " ")}</span>
      `;
      fleetEl.appendChild(item);
    });

    const logEl = document.getElementById("eventLogList");
    logEl.innerHTML = "";
    st.event_logs.forEach((ev) => {
      const row = document.createElement("div");
      row.className = "log-entry";
      row.innerHTML = `<span class="mono">${ev.time}</span><span class="log-cat">[${ev.category}]</span><span>${ev.message}</span>`;
      logEl.appendChild(row);
    });
  }

  function renderHistoryTab() {
    const st = appState.sim;
    if (!st) return;
    document.getElementById("walletCardBalance").textContent = formatMoney(st.wallet_balance);

    const histEl = document.getElementById("historyList");
    if (!st.ride_history.length) {
      histEl.innerHTML = '<div class="empty-state">No rides completed or cancelled yet. Book your first simulated cab!</div>';
      return;
    }

    histEl.innerHTML = "";
    st.ride_history.forEach((r) => {
      const card = document.createElement("div");
      card.className = "history-card";
      const total = r.status === "CANCELLED" ? r.cancellation_fee : r.live_fare + (r.tip || 0);
      card.innerHTML = `
        <div class="history-top">
          <span>${r.tier_name} • <span class="mono">${r.id}</span></span>
          <span class="mono">${formatMoney(total)}</span>
        </div>
        <div style="color:#cbd5e1">${r.pickup_name} → ${r.dropoff_name}</div>
        <div style="display:flex;justify-content:space-between;color:#94a3b8;font-size:0.72rem">
          <span>${r.status}${r.rating_given ? ` • ${"★".repeat(r.rating_given)}` : ""}</span>
          <span>${r.created_at} • ${r.payment_method.toUpperCase()}</span>
        </div>
      `;
      histEl.appendChild(card);
    });
  }

  // Canvas Geometry & Coordinate Mapping
  function getTransform() {
    const rect = canvas.getBoundingClientRect();
    const w = rect.width || 800;
    const h = rect.height || 600;
    const lw = appState.city.width;
    const lh = appState.city.height;
    const pad = 28;
    const scale = Math.min((w - pad * 2) / lw, (h - pad * 2) / lh);
    const offsetX = (w - lw * scale) / 2;
    const offsetY = (h - lh * scale) / 2;
    return { w, h, scale, offsetX, offsetY };
  }

  function toScreen(x, y, tr) {
    return { x: tr.offsetX + x * tr.scale, y: tr.offsetY + y * tr.scale };
  }

  function findNodeAtScreenPos(sx, sy) {
    if (!appState.city) return null;
    const tr = getTransform();
    let best = null;
    let bestDist = 22;
    for (const n of appState.city.nodes) {
      const p = toScreen(n.x, n.y, tr);
      const d = Math.hypot(p.x - sx, p.y - sy);
      if (d < bestDist) {
        bestDist = d;
        best = n;
      }
    }
    return best;
  }

  function handleCanvasHover(e) {
    const rect = canvas.getBoundingClientRect();
    const sx = e.clientX - rect.left;
    const sy = e.clientY - rect.top;
    const node = findNodeAtScreenPos(sx, sy);
    appState.hoveredNode = node;
    if (node) {
      const surgeZone = (appState.sim?.surge_zones || []).find((z) => z.node_id === node.id);
      tooltip.innerHTML = `<strong>${node.name}</strong>${surgeZone ? ` <span style="color:#fbbf24">⚡${surgeZone.multiplier}x</span>` : ""}`;
      tooltip.style.left = `${sx + 14}px`;
      tooltip.style.top = `${sy - 12}px`;
      tooltip.classList.remove("hidden");
    } else {
      tooltip.classList.add("hidden");
    }
  }

  function handleCanvasClick(e) {
    if (appState.sim?.active_ride) return;
    const rect = canvas.getBoundingClientRect();
    const node = findNodeAtScreenPos(e.clientX - rect.left, e.clientY - rect.top);
    if (!node) return;

    if (appState.pickMode === "pickup") {
      appState.pickupId = node.id;
      pickupSelect.value = node.id;
      setPickMode("dropoff");
    } else {
      appState.dropoffId = node.id;
      dropoffSelect.value = node.id;
      setPickMode("pickup");
    }
    refreshEstimate();
  }

  // Main 60fps Canvas Render Loop
  function renderLoop(now) {
    if (appState.city) {
      drawCity(now);
    }
    requestAnimationFrame(renderLoop);
  }

  function drawCity(now) {
    const dpr = window.devicePixelRatio || 1;
    const rect = canvas.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return;
    const targetW = Math.floor(rect.width * dpr);
    const targetH = Math.floor(rect.height * dpr);
    if (canvas.width !== targetW || canvas.height !== targetH) {
      canvas.width = targetW;
      canvas.height = targetH;
    }

    ctx.save();
    ctx.scale(dpr, dpr);
    const tr = getTransform();

    // Background
    ctx.fillStyle = "#070c17";
    ctx.fillRect(0, 0, tr.w, tr.h);

    // Subtle city blocks grid
    ctx.strokeStyle = "rgba(30, 41, 59, 0.35)";
    ctx.lineWidth = 1;
    for (let x = 0; x < tr.w; x += 40) {
      ctx.beginPath();
      ctx.moveTo(x, 0);
      ctx.lineTo(x, tr.h);
      ctx.stroke();
    }
    for (let y = 0; y < tr.h; y += 40) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(tr.w, y);
      ctx.stroke();
    }

    // Parks
    appState.city.parks.forEach((pk) => {
      const p = toScreen(pk.x, pk.y, tr);
      ctx.fillStyle = "rgba(16, 185, 129, 0.12)";
      ctx.strokeStyle = "rgba(16, 185, 129, 0.25)";
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.roundRect(p.x, p.y, pk.w * tr.scale, pk.h * tr.scale, 10 * tr.scale);
      ctx.fill();
      ctx.stroke();
    });

    // River
    const river = appState.city.river;
    if (river.length > 1) {
      ctx.save();
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      ctx.strokeStyle = "rgba(14, 116, 144, 0.42)";
      ctx.lineWidth = 34 * tr.scale;
      ctx.beginPath();
      river.forEach((pt, idx) => {
        const sp = toScreen(pt.x, pt.y, tr);
        if (idx === 0) ctx.moveTo(sp.x, sp.y);
        else ctx.lineTo(sp.x, sp.y);
      });
      ctx.stroke();

      ctx.strokeStyle = "rgba(56, 189, 248, 0.25)";
      ctx.lineWidth = 14 * tr.scale;
      ctx.stroke();
      ctx.restore();
    }

    // Surge heat zones
    const pulse = 0.85 + 0.15 * Math.sin(now / 360);
    (appState.sim?.surge_zones || []).forEach((sz) => {
      const sp = toScreen(sz.x, sz.y, tr);
      const r = 74 * tr.scale * pulse;
      const grad = ctx.createRadialGradient(sp.x, sp.y, 4, sp.x, sp.y, r);
      grad.addColorStop(0, "rgba(245, 158, 11, 0.34)");
      grad.addColorStop(0.6, "rgba(244, 63, 94, 0.14)");
      grad.addColorStop(1, "rgba(244, 63, 94, 0)");
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.arc(sp.x, sp.y, r, 0, Math.PI * 2);
      ctx.fill();
    });

    // Roads
    const nodesById = {};
    appState.city.nodes.forEach((n) => (nodesById[n.id] = n));

    appState.city.edges.forEach((e) => {
      const a = toScreen(nodesById[e.u].x, nodesById[e.u].y, tr);
      const b = toScreen(nodesById[e.v].x, nodesById[e.v].y, tr);
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      if (e.is_bridge) {
        ctx.strokeStyle = "#334155";
        ctx.lineWidth = 10 * tr.scale;
        ctx.stroke();
        ctx.strokeStyle = "#64748b";
        ctx.lineWidth = 5 * tr.scale;
        ctx.stroke();
      } else if (e.is_expressway) {
        ctx.strokeStyle = "#24324a";
        ctx.lineWidth = 7 * tr.scale;
        ctx.stroke();
      } else {
        ctx.strokeStyle = "#192338";
        ctx.lineWidth = 5 * tr.scale;
        ctx.stroke();
      }
    });

    // Route paths (either active ride or preview estimate)
    const activeRide = appState.sim?.active_ride;
    if (activeRide && activeRide.trip_path?.length > 1) {
      drawRoutePolyline(activeRide.trip_path, "#38bdf8", false, tr, now);
      if (activeRide.status === "DRIVER_ASSIGNED" && activeRide.pickup_path?.length > 1) {
        drawRoutePolyline(activeRide.pickup_path, "#f59e0b", true, tr, now);
      }
    } else if (appState.estimate?.path?.length > 1) {
      drawRoutePolyline(appState.estimate.path, "#38bdf8", true, tr, now);
    }

    // Nodes & Landmarks
    const selPickup = activeRide ? activeRide.pickup_node_id : appState.pickupId;
    const selDropoff = activeRide ? activeRide.dropoff_node_id : appState.dropoffId;

    appState.city.nodes.forEach((n) => {
      const p = toScreen(n.x, n.y, tr);
      const isHovered = appState.hoveredNode?.id === n.id;

      if (n.is_landmark) {
        ctx.fillStyle = isHovered ? "#f8fafc" : "#94a3b8";
        ctx.beginPath();
        ctx.arc(p.x, p.y, (isHovered ? 6 : 4.2) * tr.scale, 0, Math.PI * 2);
        ctx.fill();

        ctx.fillStyle = "rgba(226, 232, 240, 0.78)";
        ctx.font = `600 ${Math.max(9.5, 10.5 * tr.scale)}px Inter, sans-serif`;
        ctx.textAlign = "center";
        ctx.fillText(n.name, p.x, p.y - 9 * tr.scale);
      } else {
        ctx.fillStyle = isHovered ? "#38bdf8" : "#334155";
        ctx.beginPath();
        ctx.arc(p.x, p.y, (isHovered ? 4.5 : 2.6) * tr.scale, 0, Math.PI * 2);
        ctx.fill();
      }
    });

    // Surge multiplier pills
    (appState.sim?.surge_zones || []).forEach((sz) => {
      const p = toScreen(sz.x, sz.y, tr);
      ctx.fillStyle = "rgba(245, 158, 11, 0.9)";
      ctx.font = `700 ${Math.max(9, 10 * tr.scale)}px JetBrains Mono, monospace`;
      ctx.textAlign = "center";
      ctx.fillText(`⚡${sz.multiplier}x`, p.x, p.y + 17 * tr.scale);
    });

    // Pickup & Drop-off Pins
    if (nodesById[selPickup]) drawPin(nodesById[selPickup], "A", "#10b981", tr, now);
    if (nodesById[selDropoff]) drawPin(nodesById[selDropoff], "B", "#f43f5e", tr, now);

    // Cabs (smoothly interpolated between server ticks)
    const t = Math.min(1.0, (now - appState.lastFetchTs) / appState.pollIntervalMs);
    const assignedCabId = activeRide?.cab?.id;

    Object.values(appState.currCabs).forEach((cab) => {
      const prev = appState.prevCabs[cab.id] || cab;
      const ix = prev.x + (cab.x - prev.x) * t;
      const iy = prev.y + (cab.y - prev.y) * t;
      const sp = toScreen(ix, iy, tr);
      const isMine = cab.id === assignedCabId;

      ctx.save();
      ctx.translate(sp.x, sp.y);

      if (isMine) {
        ctx.strokeStyle = "rgba(56, 189, 248, 0.85)";
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.arc(0, 0, 16 * tr.scale, 0, Math.PI * 2);
        ctx.stroke();
      }

      ctx.rotate(cab.heading);
      const cw = 15 * tr.scale;
      const ch = 8.5 * tr.scale;
      const color = TIER_COLORS[cab.tier] || "#38bdf8";

      // Headlight glow
      ctx.fillStyle = "rgba(254, 240, 138, 0.22)";
      ctx.beginPath();
      ctx.moveTo(cw * 0.4, -ch * 0.3);
      ctx.lineTo(cw * 1.15, -ch * 0.65);
      ctx.lineTo(cw * 1.15, ch * 0.65);
      ctx.lineTo(cw * 0.4, ch * 0.3);
      ctx.closePath();
      ctx.fill();

      // Car body
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.roundRect(-cw / 2, -ch / 2, cw, ch, 2.5 * tr.scale);
      ctx.fill();

      // Windshield
      ctx.fillStyle = "#08101e";
      ctx.fillRect(cw * 0.05, -ch * 0.34, cw * 0.22, ch * 0.68);
      ctx.restore();

      if (isMine) {
        ctx.fillStyle = "#f8fafc";
        ctx.font = `700 ${Math.max(9.5, 10.5 * tr.scale)}px Inter, sans-serif`;
        ctx.textAlign = "center";
        ctx.fillText(`YOUR CAB (${cab.id})`, sp.x, sp.y - 18 * tr.scale);
      }
    });

    ctx.restore();
  }

  function drawRoutePolyline(points, color, dashed, tr, now) {
    ctx.save();
    ctx.strokeStyle = color;
    ctx.lineWidth = (dashed ? 3.2 : 4.6) * tr.scale;
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    if (dashed) {
      ctx.setLineDash([8 * tr.scale, 6 * tr.scale]);
      ctx.lineDashOffset = -(now / 45);
    } else {
      ctx.shadowColor = color;
      ctx.shadowBlur = 10;
    }
    ctx.beginPath();
    points.forEach((pt, idx) => {
      const sp = toScreen(pt.x, pt.y, tr);
      if (idx === 0) ctx.moveTo(sp.x, sp.y);
      else ctx.lineTo(sp.x, sp.y);
    });
    ctx.stroke();
    ctx.restore();
  }

  function drawPin(node, label, color, tr, now) {
    const p = toScreen(node.x, node.y, tr);
    const ringR = (11 + 3 * Math.sin(now / 260)) * tr.scale;

    ctx.save();
    ctx.strokeStyle = color;
    ctx.globalAlpha = 0.5;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(p.x, p.y, ringR, 0, Math.PI * 2);
    ctx.stroke();

    ctx.globalAlpha = 1;
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.arc(p.x, p.y, 9.5 * tr.scale, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = "#041019";
    ctx.font = `800 ${Math.max(9.5, 10.5 * tr.scale)}px Inter, sans-serif`;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(label, p.x, p.y + 0.5);
    ctx.restore();
  }

  init().catch((err) => {
    console.error("Failed to initialize SwiftCab:", err);
    showToast("Failed to connect to simulator backend.");
  });
})();
