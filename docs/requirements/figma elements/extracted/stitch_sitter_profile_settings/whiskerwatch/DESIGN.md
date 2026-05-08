# Design System: High-End Editorial PWA

## 1. Overview & Creative North Star: "The Intuitive Concierge"
This design system moves away from the "utility-first" clutter of standard gig-economy apps, leaning instead into a high-end, editorial aesthetic. Our Creative North Star is **The Intuitive Concierge**: an interface that feels less like a database and more like a premium, personalized service.

We break the "standard template" look through **intentional white space**, **asymmetric header treatments**, and a **layered depth model**. By leveraging a sophisticated typography scale and a "no-line" philosophy, we create a digital environment that feels breathable, trustworthy, and distinctly high-end.

---

## 2. Colors & Tonal Depth
The system utilizes a dual-persona color strategy. **Sitter Mode** is driven by the energy of Amber, while **Client Mode** is anchored by the serenity of Blue.

### The "No-Line" Rule
Explicitly prohibited: 1px solid borders for sectioning. Structural boundaries must be defined solely through:
- **Background Color Shifts:** Placing a `surface-container-low` element against a `surface` background.
- **Tonal Transitions:** Using the hierarchy of surface tokens to imply containment without the "cage" of a stroke.

### Surface Hierarchy & Nesting
Treat the UI as a series of physical layers—like stacked sheets of fine, heavy-weight paper.
- **Base Layer:** `surface` (#f6f7f5)
- **Secondary Sections:** `surface-container-low` (#f0f1ef)
- **Interactive Cards:** `surface-container-lowest` (#ffffff)
- **High-Priority Modals:** `surface-container-high` (#e1e3e1)

### The Glass & Gradient Rule
To achieve "soul" in the UI, avoid flat blocks of color for primary actions.
- **Signature CTAs:** Use a subtle linear gradient (45deg) from `primary` to `primary-container`. This provides a tactile, "pill-like" quality.
- **Floating Elements:** Navigation bars and Bottom Sheets should utilize **Glassmorphism**: `surface` color at 80% opacity with a `backdrop-blur` of 12px.

---

## 3. Typography: Editorial Authority
We use a dual-typeface system to balance character with extreme readability.

*   **Display & Headlines (Plus Jakarta Sans):** Used for brand moments and screen titles. The wide apertures and modern geometric shapes provide an authoritative yet friendly tone.
    *   *Headline-LG (2rem):* For welcome screens and empty states.
*   **Body & Labels (Manrope):** A highly functional sans-serif optimized for task lists and logs. Its verticality ensures that cat-sitting logs remain legible even at small sizes.
    *   *Body-MD (0.875rem):* The workhorse for log entries and cat profiles.
    *   *Label-SM (0.6875rem):* Used exclusively for metadata (e.g., timestamps in the timeline).

---

## 4. Elevation & Depth
Hierarchy is achieved through **Tonal Layering** rather than structural lines.

- **The Layering Principle:** Depth is "stacked." To make a service order card feel interactive, place it (`surface-container-lowest`) on top of a `surface-container-low` feed. The slight shift in lightness creates a natural lift.
- **Ambient Shadows:** Standard drop shadows are forbidden. Use **Ambient Glows**: 
    - `Y: 8px, Blur: 24px, Color: on-surface (Opacity: 4%)`.
    - This mimics soft, natural light, making the UI feel integrated into the user's environment.
- **The "Ghost Border" Fallback:** If accessibility requires a container edge, use a "Ghost Border": `outline-variant` (#acadac) at **15% opacity**.
- **Bottom Sheets:** These must use the `xl` (3rem) corner radius on top edges to emphasize the "soft sheet" metaphor.

---

## 5. Components

### Cards & Service Orders
- **Style:** Forbid divider lines within cards. Use `spacing-4` (1rem) to separate the "Cat Name" from the "Service Time."
- **Visual Anchor:** Use a `primary-container` background for the status chip within the card to draw the eye without a heavy border.

### Bottom Navigation (Fixed)
- **Container:** `max-w-md` centered.
- **Style:** `surface-container-lowest` with a glassmorphism backdrop blur. No top border; use a subtle ambient shadow to separate it from the scrollable content.
- **Icons:** Friendly, rounded-stroke icons. The active state uses a `primary` color tint and a subtle `surface-variant` circular background.

### Timeline Logs
- **Structure:** A vertical line is permitted here, but it must be the `outline-variant` at 20% opacity. 
- **Nodes:** Use `tertiary` (#006b1b) for completed tasks to provide a "calm success" feeling.

### Interactive Forms & Textareas
- **Inputs:** `surface-container-low` background with an `sm` (0.5rem) radius.
- **Auto-resize Textareas:** As the user types their "Cat Visit Update," the container grows organically. Avoid scrollbars within the input; the entire page should move to accommodate the content.

---

## 6. Do’s and Don’ts

### Do:
- **Use Asymmetry:** Align the headline to the left and the "Add New" button to the far right with a significant horizontal gap to create an editorial feel.
- **Embrace White Space:** Use `spacing-8` (2rem) between major sections to let the design "breathe."
- **Contextual Theming:** Ensure the transition between Sitter (Amber) and Client (Blue) modes is smooth, utilizing the `surface-tint` to subtly shift the neutral greys toward the primary hue.

### Don’t:
- **Don’t use 100% Black:** Use `on-surface` (#2d2f2e) for text. It’s softer on the eyes and feels more premium.
- **Don’t use standard Modals:** All secondary actions and filters must live in **Bottom Sheets**. A center-screen popup breaks the mobile-first "thumb-zone" ergonomics.
- **Don’t use Grid Lines:** Never use a horizontal rule to separate list items. Use a `0.5px` shift in background color or simply `spacing-4`.