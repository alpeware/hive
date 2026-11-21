# Genesis/Circle Spec Excerpt — for bitecho.hive.genesis TDD

**Date:** 2026-11-20 — **Published by:** Axiom, at Simon's direction
(publish the spec excerpt first and pin its CID, then TDD).
**Source:** excerpt of `legal-circles-map-2026-11-20.md` — "The digital
root" and "Build next" sections verbatim, plus the Run-4 Monte Carlo
section in Simon's words (2026-11-20 22:11 CST). This artifact is the
pinned spec text for the Genesis/Circle implementation work
(bitecho.hive.genesis TDD + the Nexus genesis/circle build).

## The digital root — genesis state (Simon, 2026-11-20)

The root of the structure in the digital world is a **genesis state**:

- The genesis state includes the **pre-mint total** — the full $ECHO supply
  fixed at genesis.
- Every circle is a **child of the genesis state** and receives an
  **allocation** from it. Subcircles draw from their parent circle's
  allocation the same way.
- **Conservation invariant:** all ECH over all branches must add up to the
  genesis total. This invariant holds across **every state transition** —
  a transition that breaks it is invalid by construction.
- The genesis state is the **state root of the legal entity**: the digital
  root that the real-world twin's paperwork mirrors.

Under the root, each circle carries:

- The **circle description** and its **members, named by pubkey**.
- Each pubkey maps to that member's **root memory** — the member's own
  state, **private and encrypted**. A member's individual memory includes
  their **full transaction history and account balances, held as
  receipts**.
- **Circle state is owned by its members through multiple signatures** —
  the same multisig authority the Pointer already supports. Circle state
  is either **public**, or **encrypted under a shared key of all the
  circle's members**. There is no third mode: no circle state sits in a
  private silo owned by one member.

## Build next — the digital model, mapped and simulated (Simon, 2026-11-20)

Build the genesis/circle state model above next, in this order:

1. **Map the current structure against the model** — two outputs:
   - everything **digital that we can do now** (genesis state, allocations,
     circle descriptors, member memory roots, multisig circle state — all
     buildable on the existing primitives), and
   - a **prioritized list of tasks that require a human anchor** — either
     a human acting as a circle's anchor member, or an interaction with a
     legacy system (filings, counsel, banking, registries).
2. **Simulate the digital world** under **different genesis setups**, with
   **simulated human-anchor members and digital-twin members**, so the
   structure is exercised before any real twin is stood up.
3. **Re-map Bitecho's proposed structure** (Circles 1–6 above) onto the
   simulated model and check the fit.
4. **Circle extensions to try** in the model:
   - a **devops circle for Nexus** — charged with keeping the Gentoo boxes
     up to date and running rollouts;
   - a **clj-einsum + Hive circle for Vector** — focused on more model
     support and faster kernels;
   - an **implementation circle for Nexus** — the implementation-circle
     pattern from the twin runs, as a standing circle.

## Run-4 — fork the simulated system; Monte Carlo futures (Simon, 2026-11-20)

Once the system is fully simulated as described in the seed deck, fork it
into any number of directions and run Monte Carlo simulations to simulate
possible different futures. Pick the data-driven future we want to
envision: the one with the highest path likelihood.
