# SECUREVAULT - DESIGN SPECIFICATION DOCUMENT

---

## 1. Design Principles

These design principles are specifically formulated to resolve engineering and design conflicts within the offline-first, highly secure paradigm of SecureVault:

### 1. Offline-First Security Priority
* **Statement**: All core security operations, credential creation, and local access paths must function independently of any network connectivity.
* **Design Conflict Resolution**: If a feature can be implemented using either a client-side library or an online API (e.g. password strength calculation, favicon caching), the client-side/caching-proxy method must be selected. The application UI must never display blocking progress states waiting for network validation during database access or credential rendering.

### 2. Friction by Design for Destructive Actions
* **Statement**: We intentionally introduce interaction hurdles for operations that result in loss of access or key regeneration.
* **Design Conflict Resolution**: Permanent deletions (e.g. purging trash, revoking session keys, deleting accounts) must never be bound to swift gestures like swipes. They must be gatekept behind confirmation modals and explicit security challenges (like security questions), sacrificing speed to prevent accidental data loss.

### 3. Progressive Exposure of Secrets
* **Statement**: Secrets are encrypted at rest and must remain visually masked in the interface by default, exposed only via deliberate, temporary user actions.
* **Design Conflict Resolution**: In-memory parameters (such as passwords, backup codes, security answers) are masked (`••••••`) on load. Plaintext representation is limited to a discrete user toggle (e.g. eye icon click) or clipboard copy event, both of which automatically auto-clear/expire after 30 seconds.

### 4. Continuous Integrity Accountability
* **Statement**: Visual alerts regarding synchronization states, device limits, or host device compromises must be prominent and persistent on primary views rather than hidden within submenu directories.
* **Design Conflict Resolution**: If system root is detected (for developers), or sync queues are backlogged, or the maximum active session limit is reached, a sticky, high-contrast banner must anchor to the dashboard header rather than leaving the information inside nested Settings.

---

## 2. Visual Identity

SecureVault adopts the **Android Material You (Material 3)** system to support dynamic color-theming and scalable layouts on mobile environments.

### 2.1 Material 3 Color Tokens
The following hex color tokens are calibrated for default high-contrast dark theme mode (conforming to security readability requirements):

| Token | Hex Value | Description |
| :--- | :---: | :--- |
| `primary` | `#D0BCFF` | Light lavender; standard Material 3 brand highlight. |
| `primary-variant` | `#4F378B` | Deep purple; container background for primary elements. |
| `secondary` | `#CCC2DC` | Muted lavender; text outlines and tertiary cards. |
| `surface` | `#1C1B1F` | Dark charcoal; primary background for cards and bottom sheets. |
| `surface-variant` | `#49454F` | Medium gray; input fields and divider borders. |
| `background` | `#141218` | Almost black; main full-screen window background. |
| `error` | `#F2B8B5` | Soft red; validation errors and destructive buttons. |
| `warning` | `#E3A857` | Soft amber; lockout warnings and sync alerts. |
| `success` | `#81C784` | Muted green; successful sync and strong password banners. |
| `info` | `#64B5F6` | Soft blue; tooltip information and dynamic suggestions. |
| `on-primary` | `#381E72` | Dark purple; text/icons placed on top of `primary` buttons. |
| `on-surface` | `#E6E1E5` | Light gray; main readable text on cards/dialogs. |
| `on-background` | `#E6E1E5` | Light gray; main readable text on full screen backgrounds. |
| `on-error` | `#601410` | Dark red; text placed on top of error dialog buttons. |

### 2.2 Typography Scale
SecureVault uses standard native platform type scales:

| Role | Typeface | Size | Weight | Line Height | Usage |
| :--- | :--- | :---: | :--- | :---: | :--- |
| **Display Large** | Roboto | 57sp | Regular | 64sp | Main numeric lockout counts and onboarding headers. |
| **Headline Medium**| Roboto | 28sp | SemiBold | 36sp | Pin entry/unlock titles and key settings headers. |
| **Title Large** | Roboto | 22sp | Medium | 28sp | Action bar title text, dialog prompts. |
| **Body Large** | Roboto | 16sp | Regular | 24sp | User list items, text inputs, instructions copy. |
| **Label Large** | Roboto | 14sp | Medium | 20sp | Button text labels, chip filters, action keys. |
| **Code Monospace** | Roboto Mono | 14sp | Bold | 20sp | Plaintext backup codes and generated passwords. |

### 2.3 Spacing System
All margins, padding, and layout bounds are multiples of **8dp**:
* **4dp (XS)**: Dense element spacing, text label line spacing.
* **8dp (S)**: Internal cell paddings, element-to-label margins.
* **16dp (M)**: Standard layout margins, card internal padding, list gutters.
* **24dp (L)**: Section separations, outer container borders.
* **32dp (XL)**: Lock screen margins, header-to-form buffers.
* **48dp (XXL)**: Large structural separations (e.g. title to content blocks).

### 2.4 Border Radius Tokens
* `radius-none` (0dp): Edge-to-edge listing dividers, full-bleed screen edges.
* `radius-sm` (4dp): Notification badges, micro-indicators.
* `radius-md` (8dp): Text inputs, strength meter blocks, tooltips.
* `radius-lg` (16dp): Cards, Dialog containers, Bottom Sheets.
* `radius-full` (9999dp): Buttons, Floating Action Buttons (FAB), search bars, active pills.

### 2.5 Elevation & Shadow Tokens
Following Material 3 elevation overlays (implemented via surface opacity tints):
* **Elevation 0** (0dp): Flat base window (`background` color).
* **Elevation 1** (1dp / 5% tint): Base search bars, unselected tabs.
* **Elevation 2** (3dp / 8% tint): Default cards, navigation bars.
* **Elevation 3** (6dp / 11% tint): Active dragging list cards, Floating Action Buttons (FAB).
* **Elevation 4** (8dp / 12% tint): Modal Dialog boxes.
* **Elevation 5** (12dp / 14% tint): Bottom Sheets.

### 2.6 Icon Library
* **Library**: Material Symbols (Outlined variant by default; Filled variant indicates active selection states).
* **Grid Bounds**:
  * `18dp`: Dense listing info (e.g. lock icons, clipboard copy visual cues).
  * `24dp`: Primary user actions (e.g. edit, stars, trash bin, search).
  * `32dp`: Status illustrations, onboarding icon indicators.
  * `48dp`: System warnings (e.g. environment warning screen top alerts).

---

## 3. Component Library

This catalog details the visual definitions, interactive states, usage rules, and accessibility constraints for all UI components.

### 3.1 Standard Components

#### 1. Button (Primary & Secondary)
* **Visual Spec**: Min height 48dp. Horizontal padding 24dp, vertical 12dp. Rounded pill shape (`radius-full`).
* **States**:
  * *Default*: Background `primary` (on-primary text).
  * *Pressed*: Elevation increases by 1 step; primary color overlay changes to 12% black.
  * *Focused*: Primary color boundary with a 2dp border outer ring.
  * *Disabled*: 38% opacity surface color; text matches background.
  * *Error*: Red background (for destructive action buttons).
  * *Loading*: Replaces text label with centered infinite spinner (matching primary text color).
* **Usage Rule**: Primary buttons represent the single main action on a view (e.g. "Save", "Unlock"). Secondary outline buttons represent alternatives (e.g. "Cancel", "Go Back").
* **Accessibility**: Touch targets >= 48dp. Contrast ratio >= 4.5:1.

#### 2. Input Field
* **Visual Spec**: Height 56dp. Padding 16dp horizontal, 8dp vertical. Rounded rectangle (`radius-md`).
* **States**:
  * *Default*: Thin border (`surface-variant`), placeholder visible.
  * *Pressed / Active*: Focus border animates to 2dp width.
  * *Focused*: Primary color border active, label moves to top container boundary.
  * *Disabled*: 38% greyed border and text fill.
  * *Error*: Red outline border; error description label appears directly underneath in `error` red.
  * *Loading*: Readonly variant displaying shimmer overlay.
* **Usage Rule**: Used for text and numeric inputs. Never use for select-only menus.
* **Accessibility**: Always use floating labels. Contrast >= 4.5:1. Screen reader reads helper tags.

#### 3. Card
* **Visual Spec**: Minimum width matches parent layout limits. Padding 16dp. Rounded corners (`radius-lg`).
* **States**:
  * *Default*: Elevation-1 background.
  * *Pressed*: Elevation increases to Elevation-3; 8% overlay tint triggers ripple.
  * *Focused*: Thin primary outline border.
  * *Disabled*: Flat base background.
* **Usage Rule**: Groups related data points (e.g. details of single password, active sessions).
* **Accessibility**: Group elements logically so screen reader reads card info continuously.

#### 4. List Item
* **Visual Spec**: Height 72dp (two-line title/subtitle) or 56dp (one-line header). Horizontal padding 16dp.
* **States**:
  * *Default*: Flat background, separator border at bottom.
  * *Pressed*: Light grey surface overlay with touch ripple.
  * *Focused*: Outline overlay.
  * *Disabled*: Muted text; non-interactive click hooks.
* **Usage Rule**: Display repeating structural information (such as password folders or category lists).
* **Accessibility**: Read title followed by actions (e.g. "Google, edit button").

#### 5. Bottom Sheet
* **Visual Spec**: Max width 640dp (tablet center-aligned). Height dynamic (max 80% screen height). Top corners rounded (`radius-lg`).
* **States**: Collapsed, Expanded, Hidden. Uses standard 8dp dragging handlebar at the top boundary.
* **Usage Rule**: Use for secondary, transient contextual actions (such as selecting category folders or configuring generator settings).
* **Accessibility**: Focus is trapped inside bottom sheet on open. Modal overlay blocks background clicks.

#### 6. Dialog
* **Visual Spec**: Min width 280dp, max width 560dp. Content padding 24dp, button layout padding 8dp. Rounded corners (`radius-lg`).
* **States**: Modal elevation-4. Blocks all touch events outside parent area.
* **Usage Rule**: Only use for highly disruptive or irreversible confirmations (e.g. purging trash, account deletion).
* **Accessibility**: Focus traps on entry. Esc or Back button triggers cancel.

#### 7. Toast
* **Visual Spec**: Height 48dp. Max width 344dp. Horizontal padding 16dp, vertical 12dp. Rounded (`radius-md`).
* **States**: Entrance fade-in, Dismiss fade-out. Displays flat on top of all screens.
* **Usage Rule**: Temporary system notifications that require no action (e.g. "PIN Changed", "Syncing...").
* **Accessibility**: Auto-announces immediately (ARIA polite). Minimum screen duration 3 seconds.

#### 8. Badge
* **Visual Spec**: Width/height 16dp (with text) or 8dp (dot-only). Rounded pill (`radius-full`).
* **States**: Active (high-contrast red/warning), Muted (default gray).
* **Usage Rule**: Notification badge representing counts or status alerts (e.g. showing active session counts).
* **Accessibility**: Must have a text equivalent read by screen readers (e.g., "3 items").

#### 9. Tab
* **Visual Spec**: Height 48dp. Horizontal padding 16dp. Bottom active line indicator 2dp thickness.
* **States**:
  * *Selected*: Text `primary` color; bottom indicator active.
  * *Unselected*: Text secondary color.
  * *Pressed*: Highlight overlay.
* **Usage Rule**: Toggle views within a single navigation boundary.
* **Accessibility**: Swiping shifts focus. Tab items mapped to Accessibility Actions.

#### 10. Navigation Bar
* **Visual Spec**: Height 80dp. Material 3 bottom-aligned icons with labels.
* **States**: Active (icon container dynamic pill background), Unselected.
* **Usage Rule**: Main system navigation panel at the bottom of the screen.
* **Accessibility**: Click targets min 48dp. Text labels must always remain visible.

#### 11. Empty State
* **Visual Spec**: Centered layout with 32dp outer padding. Icon size 32dp.
* **States**: Standard flat representation.
* **Usage Rule**: Rendered when lists contain zero database elements (e.g., empty search query or empty trash).
* **Accessibility**: Screen reader reads heading, body, and then moves to the central CTA button.

#### 12. Skeleton
* **Visual Spec**: Matches dimensions of the component placeholder. No borders by default.
* **States**: Pulse animation (alpha goes 30% -> 70% -> 30% at 1.2s intervals).
* **Usage Rule**: Placeholder rendering during initial database reads to avoid page layout shifts.
* **Accessibility**: Screen reader reads: "Loading view data...".

#### 13. Chip
* **Visual Spec**: Height 32dp. Padding 12dp horizontal. Rounded pill shape (`radius-full`).
* **States**: Default, Selected (primary tint background), Pressed, Disabled.
* **Usage Rule**: Filter selectors or tags for categorizing credentials.
* **Accessibility**: Act as checkbox or toggle button widgets.

---

## 3.2 Custom Components

#### 1. Onboarding Slides Carousel
* **Visual Spec**: Full screen container, 320dp height image area, bottom horizontal dots.
* **States**: Default, Dragging, Swiping.
* **Usage Rule**: Used exclusively in onboarding (`SCR-ONB-01`) to preview application value.
* **Accessibility**: Screen readers allow swiping left/right to navigate slides, reading slide text sequentially.

#### 2. Numeric Keypad
* **Visual Spec**: 3x4 grid alignment of keys (0-9, delete, clear). Spacing 16dp. Circle size 64dp diameter.
* **States**: Default (surface-variant container), Pressed (active ripple), Disabled.
* **Usage Rule**: Secure PIN inputs during lockscreen challenges (`SCR-ATH-02`) or creation (`SCR-ONB-03`).
* **Accessibility**: Keys explicitly labeled with numerical values ("Button 9", "Delete digit").

#### 3. PIN Dot Indicators
* **Visual Spec**: Horizontal row of 6 dots. Dot size 12dp, spacing 8dp.
* **States**:
  * *Empty*: Muted gray outline border.
  * *Filled*: Animates scaling (120%) and fills with `primary` purple color.
* **Usage Rule**: Used directly above the numeric keypad to represent entered PIN length securely.
* **Accessibility**: Announces "4 of 6 digits entered". Never announces the value of digits.

#### 4. Password Strength Meter
* **Visual Spec**: Horizontal segmented layout, height 6dp. Divided into 4 distinct blocks.
* **States**:
  * *Weak*: Block 1 red.
  * *Medium*: Blocks 1 & 2 amber.
  * *Good*: Blocks 1, 2, & 3 blue.
  * *Strong*: All 4 blocks green.
* **Usage Rule**: Placed under the password generator input to preview safety.
* **Accessibility**: Reads current text status (e.g., "Strength: Strong").

#### 5. Floating Action Button (FAB)
* **Visual Spec**: Circular layout, 56dp diameter. Positioned bottom-right of dashboard with 16dp margins. Rounded corners (`radius-lg` with Material 3 standard).
* **States**: Default (Elevation-3), Pressed (Elevation-5), Disabled.
* **Usage Rule**: Floating overlay button on `SCR-VLT-01` to add new credentials.
* **Accessibility**: Screen reader reads: "Add new credential entry". Touch target 56dp.

---

## 4. UX Patterns

SecureVault enforces uniform user experience patterns across all modules:

### 4.1 Form Validation
* **Plaintext Input Fields**: Validation occurs **on blur (focus loss)**. We avoid showing instant errors while the user is actively typing to reduce visual distraction. Errors display in red helper text directly below the input field.
* **PIN Entry**: Validation occurs **on submission** (once the 6-digit threshold is reached). Mismatches clear inputs and show standard toasts or inline alerts.
* **Debounced Local Search**: Input changes in the Search Bar trigger local database queries after a **100ms debounce** limit, preventing key-by-key UI lag.

### 4.2 Loading states
* **Skeleton Cards**: Applied on major structural lists (e.g. Dashboard lists, session logs) where structural layouts are static. This preserves placement and avoids layout shift.
* **Spinners / Progress Indicators**: Applied inside action buttons during processing transitions (e.g., clicking "Save") or full-screen overlays when compiling file exports.

### 4.3 Empty States
All empty listings must render a center-aligned block containing:
1. **System Icon**: 32dp size, muted `surface-variant` color.
2. **Title**: Title Large font, `on-surface` color (e.g., "No passwords found").
3. **Body Text**: Body Large, secondary color (e.g., "Save new items to start organizing").
4. **Primary CTA**: A standard primary action button if the user is authorized to resolve the state (e.g. "Add Entry").

### 4.4 Error Handling
To keep error notifications clear and actionable:
* **Toasts**: Used for background sync failures or minor connection drops (e.g., "Cloud sync offline. Changes will update later.").
* **Inline Helper Text**: Used for simple form inputs (e.g., "Email format incorrect", "Answer is empty").
* **Full-Page Block / Dialogs**: Used for blocking security conditions (e.g., PIN Lockout Active, Root environment warnings, VMK decryption mismatch).

### 4.5 Destructive Actions
* **Definition**: Any actions that lead to permanent deletion of vault data, recovery pathways, or device sync keys (e.g. deleting accounts, emptying trash, permanent purging, revoking device keys).
* **Pattern**: Destructive buttons use `error` red backgrounds. Actions must launch a modal dialog requesting confirmation. In addition, administrative changes require the user to pass a security question challenge first.

### 4.6 Pull to Refresh
* **Allowed**: Only on Dashboard (`SCR-VLT-01`) and Active Devices (`SCR-DEV-01`) lists to allow manual sync overrides.
* **Blocked**: Disabled on all form editors, lock screens, and onboarding setups.

---

## 5. Accessibility Standards

SecureVault requires strict compliance with international accessibility standards to support all user groups:

* **WCAG Compliance**: Minimum compliance matching **WCAG 2.1 AA** standards.
* **Touch Targets**: All interactive controls (buttons, navigation elements, inputs, list items) must maintain a minimum bounding size of **48dp x 48dp** on screen.
* **Contrast Ratios**:
  * Standard text sizes (under 18pt): Minimum contrast ratio of **4.5:1** against backgrounds.
  * Large headings (18pt and above): Minimum contrast ratio of **3.0:1** against backgrounds.
  * Interactive borders, components, and focus indicators: Minimum contrast ratio of **3.0:1** against backgrounds.
* **Dynamic Type Support**: Layout containers must utilize flexible height constraints (wrap_content) to support dynamic system font resizing up to **200%** without text overlap or clipping.
* **Screen Readers**: All graphic elements, icon buttons, and badges must contain an explicit, localized `contentDescription` parameter. Actions (e.g., Star icons, edit tools) must clearly define state changes to the Accessibility Framework.

---

## 6. Motion Tokens

Transitions and animations are designed to feel highly responsive, natural, and low-latency while honoring system battery constraints.

### 6.1 Motion Durations
* **Instant** (`0ms`): Used for clipboard clears, immediate data switches, and security state locks.
* **Fast** (`100ms`): Focus states, keyboard pop-ups, ripple effects, hover states.
* **Standard** (`200ms`): Standard screen pushes, navigation panel sliding drawers, modal dialog entries.
* **Slow** (`300ms`): Bottom Sheet entries, onboarding carousel slide transitions.

### 6.2 Easing Curves
* **Standard / Decelerate**: `cubic-bezier(0.2, 0.0, 0.0, 1.0)`
  * *Usage*: Elements entering the screen view (e.g. dialog fade-in, lists loading).
* **Accelerate**: `cubic-bezier(0.3, 0.0, 0.8, 0.15)`
  * *Usage*: Elements exiting the screen view (e.g. closing drawer, removing item).
* **Sharp / Standard**: `cubic-bezier(0.4, 0.0, 0.6, 1.0)`
  * *Usage*: Elements moving inside screen bounds (e.g. tabs shifting, cards expanding).

### 6.3 Reduced Motion Behavior
If the user enables system-level "Reduce Motion" features:
1. **Fades instead of Slides**: Slide transitions (such as bottom sheets sliding up or navigation panels shifting in) collapse to a standard opacity fade of `150ms`.
2. **Instant Carousel Changes**: Carousel sliding animations are replaced with a fast opacity transition (`100ms`).
3. **Static Skeletons**: Pulsing skeleton animations stop animating and display a flat, static gray color block.
4. **Immediate Popups**: Dialog entries and popup menus transition instantly (`0ms`) without scaling.
