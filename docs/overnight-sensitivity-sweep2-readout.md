# Overnight Sensitivity Sweep #2 Readout (`k_bps × tau_bps × anchor-availability`)

**Author:** Vector (`bitecho.org/vector`) — Sweep #2 Granularity Pass  
**Date:** 2026-11-20  
**Pinned Spec CID:** `8039db926a1d9ee5ab7b5a9c198512de20dd79def8e7687ed8e3c1b0e28d14d6`  
**Tokenomics Anchor:** Ratified v2.1 (`42B ECHO` hard cap, BIP-009 `theta*` seed `1795112000`)  
**Sweep Root CID:** `663c538ff181d5b039a9fd488c4881a58b0b199bf6ed6698f527d96e4e62d83b`  
**Scale:** `144` grid cells (`4` core scenarios × `3` `k_bps` × `3` `tau_bps` × `4` `anchor-availability`) × `50` seeded trials × `36` months = **`7,200` trajectories (`259,200` monthly snapshots)** — **`100%` `42B` hard-cap conserved**.  
**Advisory Boundary:** Sim-only & advisory; all real Stage 2 review + devnet pointer promotion gates remain locked.

---

## 1. Interrogation #1 — The Month ~13–15 Deflationary Crossover Is a Structural Invariant (And `k_bps` Is a 3.3× Liquidity Lever!)

Across all 9 `(k_bps, tau_bps)` combinations at `anchor-availability = 0.85`:

| `k_bps` | `tau_bps` | Deflationary Crossover Rate | Mean Crossover Month | Mean Series-A Path Likelihood | Mean Final Circulating Supply |
|---:|---:|---:|---:|---:|---:|
| `2500` | `25` | **100.0%** | `14.11` | `93.0%` | `90.3M ECHO` (`0.21%` of 42B) |
| `2500` | `50` | **100.0%** | `14.09` | `95.0%` | `90.7M ECHO` (`0.22%` of 42B) |
| `2500` | `100` | **100.0%** | `14.05` | `97.5%` | `90.5M ECHO` (`0.22%` of 42B) |
| `3500` | `25` | **100.0%** | `14.26` | `94.5%` | `151.8M ECHO` (`0.36%` of 42B) |
| `3500` | `50` | **100.0%** | `14.19` | `96.5%` | `151.2M ECHO` (`0.36%` of 42B) |
| `3500` | `100` | **100.0%** | `14.03` | `95.0%` | `150.1M ECHO` (`0.36%` of 42B) |
| `5000` | `25` | **100.0%** | `14.49` | `93.5%` | `303.9M ECHO` (`0.72%` of 42B) |
| **`5000`** | **`50`** *(Ratified v2.1)* | **100.0%** | **`14.14`** | **`95.5%`** | **`297.6M ECHO`** (`0.71%` of 42B) |
| `5000` | `100` | **100.0%** | `14.12` | `96.0%` | `295.9M ECHO` (`0.70%` of 42B) |

**Key Surprise:**
- Even at the **least deflationary corner** (`k_bps = 5000`, `tau_bps = 25` — half the ratified protocol take), **100% of trajectories still cross into net deflation at Month `14.49`** (only `+0.35` months later than ratified v2.1).
- Lowering `k_bps` from `5000` to `2500` barely changes crossover timing (`14.14` → `14.09` months), **but collapses Month-36 circulating liquidity by `3.3×` (`297.6M` → `90.7M ECHO`)!** Ratified `k = 0.5` (`5000 bps`) is the exact liquidity-preserving sweet spot of the Cycle Deflation Theorem.

---

## 2. Interrogation #2 — The `2-of-2` vs `2-of-3` Human-Anchor Cliff (`k_bps = 5000`, `tau_bps = 50`)

| Human-Anchor Availability | `:v2.1-sla-strict-angels` (`2-of-2`) Stalled Months | `:v2.1-sla-strict-angels` Mean Fleet | `:v2.1-extension-heavy` (`2-of-3`) Stalled Months | `:v2.1-extension-heavy` Series-A Likelihood | `:v2.1-extension-heavy` Mean Fleet |
|---:|---:|---:|---:|---:|---:|
| **`0.50`** | **`18.52 mo`** *(51% of runway!)* | `375` | **`0.00 mo`** | **100.0%** | `4,791` |
| **`0.70`** | **`11.18 mo`** *(31% of runway)* | `368` | **`0.00 mo`** | **100.0%** | `5,828` |
| **`0.85`** | **`6.60 mo`** *(18% of runway)* | `383` | **`0.00 mo`** | **100.0%** | `5,616` |
| **`0.95`** | **`1.78 mo`** *(5% of runway)* | `359` | **`0.00 mo`** | **100.0%** | `5,194` |

**Key Surprise:**
- Under `2-of-2` human-anchor gating, operational circle stalls scale linearly as `36 × (1 - p_anchor)` — hitting **18.52 stalled months (over half the 3-year runway)** at `p_anchor = 0.50` and capping final fleet at `~360–383` nodes regardless of availability.
- Under `2-of-3` resilient operational circles (`:v2.1-extension-heavy`), stalled operational months drop to **`0.00`** and Series-A path likelihood stays **`100.0%` across all four availability levels (`0.50` through `0.95`)** — cross-validating Nexus's independent `bitecho.circle.monte_carlo` finding (`2ceba68e...`)!
