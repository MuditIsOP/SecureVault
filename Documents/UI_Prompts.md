# SECUREVAULT - UI PROMPTS SPECIFICATION DOCUMENT

---

## 1. Onboarding Screen Module (ONB)

### SCR-ONB-01 — Onboarding & Google Sign-In Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "SecureVault Onboarding & Google Sign-in" (SCR-ONB-01) for standard mobile smartphone users. 
Visual Style: Dark Theme, high-contrast. Background color: background (#141218).
Layout Structure:
1. Top Section: Centered product logo and title "SecureVault" using Title Large (Roboto Medium, 22sp, line height 28sp, color on-background #E6E1E5). Margin top: 64dp.
2. Central Carousel Section: Slides Carousel component (320dp height, width 328dp, margin horizontal 16dp, rounded corners radius-lg 16dp).
   - Display a graphic card with illustration area, a title "Keep Passwords Local & Encrypted" (Headline Medium, Roboto SemiBold, 28sp, color on-background #E6E1E5), and subtitle "All database records are protected locally by Room and SQLCipher encryption." (Body Large, Roboto Regular, 16sp, color secondary #CCC2DC).
   - Bottom page indicator: 3 dots centered horizontally (spacing 8dp, size 8dp), with active dot colored primary (#D0BCFF) and others colored surface-variant (#49454F).
3. Bottom Action Section:
   - Primary Action Button: "Sign in with Google" pill button (width 328dp, height 48dp, rounded radius-full 9999dp, background color primary #D0BCFF, text color on-primary #381E72, Label Large Roboto Medium 14sp). Place Google logo icon (18dp size) to the left of the text.
   - Micro-disclaimer link at bottom center: "SecureVault is offline-first. Your Google Sign-In is used to fetch a master verification key." (Body Small, Roboto Regular, 12sp, color secondary #CCC2DC, alignment center, margin bottom 24dp).
Include interaction states: Default, hover on sign-in button, and loading state (text replaced by centered circular spinner).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "SecureVault Onboarding & Google Sign-in" (SCR-ONB-01).
File Path: src/components/onboarding/OnboardingLogin.tsx
Props Interface:
```typescript
interface OnboardingLoginProps {
  onLoginSuccess: (sessionToken: string, isRegistered: boolean) => void;
  onLoginFailure: (errorMessage: string) => void;
}
```
Visual Theme (Tailwind classes matched to CSS variables):
- `background`: `#141218`
- `primary`: `#D0BCFF`
- `on-primary`: `#381E72`
- `secondary`: `#CCC2DC`
- `surface`: `#1C1B1F`
- `surface-variant`: `#49454F`
Implement a local state machine: `state: 'idle' | 'loading' | 'error'`.
Carousel slides text:
1. Slide 1: "Keep Passwords Local & Encrypted" / "Protected locally by Room & SQLCipher."
2. Slide 2: "Synchronized Securely" / "Automatic background queue sync using Android WorkManager."
3. Slide 3: "Autofill Integration" / "Input credentials directly into WebViews and native fields."
Render a swipeable slide container with absolute navigation dots.
Render a large Google Sign-in Button:
- Trigger calls REST API: `POST /v1/auth/login`
- Request body shape: `{ googleIdToken: string, deviceId: string, deviceName: string, androidVersion: string }`
- Expected response shape: `{ firebaseToken: string, refreshToken: string, registered: boolean }`
Handle loading states inside the button by displaying a centered Tailwind spinner.
Ensure touch targets are 48px minimum height. All images/buttons require screen reader aria-labels.
```

#### Gemini / Image Generation (Mockup)
```
A high-fidelity mockup of an Android smartphone frame showing the "SecureVault Onboarding" screen (SCR-ONB-01). 
Dark mode design. The background is a solid deep black-gray (#141218).
At the top, a clean, vector logo of a shield with a keyhole in light lavender purple (#D0BCFF) is centered above the title text "SecureVault" in a crisp light-gray sans-serif font.
In the center, there is a smooth dark-surface card (#1C1B1F) with rounded corners. The card displays a stylized graphic of a padlock icon and a Room-Database block diagram, followed by the text: "Offline-First Security" in white and "Your passwords never leave your device unencrypted" in a muted lavender gray.
Below the card, three pagination dots are visible: the first dot is highlighted in bright purple (#D0BCFF) while the other two are muted gray.
At the bottom, a prominent, pill-shaped button is centered. The button is light lavender (#D0BCFF) with dark purple text that reads "Sign in with Google", accompanied by a small Google logo icon.
```

---

### SCR-ONB-02 — Security Question Setup Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Security Question Setup" (SCR-ONB-02).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: Back navigation arrow (24dp, color on-background #E6E1E5) at top-left. Screen Title "Setup Security Question" (Headline Medium, 28sp, color on-background #E6E1E5) aligned left, margin top 24dp, horizontal margins 16dp.
2. Description Block: "This question will be challenged whenever you log in on a new device or perform administrative tasks. Choose an answer you will not forget." (Body Large, 16sp, color secondary #CCC2DC, margin top 16dp).
3. Input Form Block:
   - Exposed Dropdown Component (width 328dp, height 56dp, rounded radius-md 8dp, border color surface-variant #49454F). Label: "Select Security Question" (Label Large, 14sp). Default selected value text: "What was the name of your first pet?". Dropdown arrow icon on the right side.
   - Text Input Field (width 328dp, height 56dp, border surface-variant #49454F). Label: "Your Answer" (Label Large, 14sp). Placeholder: "Enter answer here...". Support validation error state (red border error #F2B8B5, helper message "Answer cannot be blank").
4. Bottom Section:
   - Primary Action Button: "Continue" pill button (width 328dp, height 48dp, background primary #D0BCFF, text on-primary #381E72, rounded radius-full 9999dp, margin bottom 24dp).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Security Question Setup Screen" (SCR-ONB-02).
File Path: src/components/onboarding/SecurityQuestionSetup.tsx
Props Interface:
```typescript
interface SecurityQuestionSetupProps {
  userIdToken: string;
  onSetupComplete: () => void;
}
```
Visual Theme:
- Background: `#141218`
- Primary Button: `#D0BCFF`
- Text Input: Border `#49454F`, Text `#E6E1E5`
State variables: `selectedQuestionId: string`, `answerText: string`, `validationError: string`, `isLoading: boolean`.
Render a native selector containing 5 sample security questions.
Input field must validate `onBlur` and `onSubmit` that length > 0.
On form submit:
- Hash the `answerText` using SHA-256 (client-side mock).
- Submit POST to `/v1/auth/security-question/setup` with headers: `Authorization: Bearer [userIdToken]`
- Request body shape: `{ questionId: string, answerHash: string }`
- On HTTP 200/201, trigger `onSetupComplete()`.
Make sure input field uses floating labels and includes accessibility dynamic type styling.
```

#### Gemini / Image Generation (Mockup)
```
An Android phone screen mockup displaying the "Security Question Setup" screen (SCR-ONB-02).
Background color is very dark charcoal (#141218).
At the top left is a simple left-pointing arrow icon in light gray. Below it, large text reads "Security Question".
A paragraph explains: "Choose a security question to lock your decryption keys."
In the middle of the screen, there is a dropdown field with a border, showing the selected option: "What was your childhood nickname?".
Directly beneath it is a text input box with the label "Your Answer" and the typed text "Shadow" in white, showing a green checkmark on the right edge.
At the bottom, a rounded lavender button (#D0BCFF) spans the width with bold text: "Continue".
```

---

### SCR-ONB-03 — PIN Creation Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "PIN Creation Screen" (SCR-ONB-03).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: Screen Title: "Create App PIN" (Headline Medium, 28sp, color on-background #E6E1E5), followed by subtitle "Set a 6-digit numeric passcode to quickly unlock your local vault." (Body Large, 16sp, color secondary #CCC2DC), margins 16dp.
2. Display Indicators Section: Centered row of 6 PIN Dot Indicators.
   - Dot diameter: 12dp. Spacing: 8dp.
   - Rendering: Show 4 dots filled with primary (#D0BCFF) and 2 dots represented as outlined circles (color surface-variant #49454F).
3. Keyboard Grid Section: Numeric Keypad component aligned to bottom.
   - Grid layout: 3 columns x 4 rows.
   - Keys: 1-9 in order, bottom-left is empty, bottom-center is "0", bottom-right is "Delete" (arrow-back icon).
   - Button styling: Circle bounds 64dp, background color surface-variant (#49454F), text/icon color on-surface (#E6E1E5), Label Large 14sp.
Horizontal and vertical margins between keys: 16dp.
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "PIN Creation Screen" (SCR-ONB-03).
File Path: src/components/onboarding/PinCreation.tsx
Props Interface:
```typescript
interface PinCreationProps {
  onPinSaved: (hashedPin: string) => void;
}
```
Implement custom keypad interaction handling.
State variables: `pin: string` (max length 6), `confirmMode: boolean` (step 2), `tempPin: string`, `errorMsg: string`.
Logics:
- Key taps append digits to `pin`.
- When `pin` length reaches 6:
  - If `confirmMode === false`: save `tempPin = pin`, clear `pin`, set `confirmMode = true`, show prompt: "Re-enter PIN to confirm".
  - If `confirmMode === true`: check if `pin === tempPin`. If matches, perform SHA-256 hash on client side, trigger `onPinSaved(hashedPin)`. If mismatch, show error "PINs do not match. Restarting.", clear inputs, and reset to step 1.
Render 6 circles whose CSS classes check: `index < pin.length ? 'bg-[#D0BCFF]' : 'border-2 border-[#49454F]'`.
Numeric keypad keys must be accessible buttons with screen reader labels (e.g. `aria-label="Digit 5"`).
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone mock showing the "PIN Creation Screen" (SCR-ONB-03).
The screen is dark gray (#141218). At the top, in large font, is the title "Create App PIN" and subtitle "Choose a 6-digit PIN".
In the center, six circular indicators are aligned horizontally; four are solid lavender purple (#D0BCFF), and two are simple dark gray rings.
Below them, a large grid of rounded circular buttons (digits 1 to 9, 0, and a delete key icon) represents the lockscreen keypad. The buttons are circular with a dark surface background (#49454F) and bold white numbers.
```

---

### SCR-ONB-04 — Backup Code Generation Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Backup Code Generation" (SCR-ONB-04).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: Centered header "Save Recovery Codes" (Headline Medium, 28sp, color on-background #E6E1E5), subtitle: "If you forget your security answer or lose your device, these backup codes will allow you to reset your PIN." (Body Large, 16sp, color secondary #CCC2DC), margins 16dp.
2. Code Box Block: A centralized Card (width 328dp, height 120dp, background surface #1C1B1F, rounded radius-lg 16dp).
   - Inner contents: Two horizontal rows displaying alphanumeric backup codes (e.g. "AB7K-XP92" and "L9M2-R4T1") using Code Monospace typography role (Roboto Mono Bold, 18sp, color primary #D0BCFF, centered).
   - "Copy Codes" button centered below codes (Label Large, icon outline copy 18dp, color primary #D0BCFF).
3. Bottom Action Block:
   - Primary Action Button: "I Have Saved These Codes" pill button (width 328dp, height 48dp, background primary #D0BCFF, text on-primary #381E72, rounded radius-full 9999dp).
   - Warning banner: "Never store these codes in plain text on your device." (Body Small, 12sp, color error #F2B8B5, alignment center, margin bottom 24dp).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Backup Code Generation Screen" (SCR-ONB-04).
File Path: src/components/onboarding/BackupCodes.tsx
Props Interface:
```typescript
interface BackupCodesProps {
  userIdToken: string;
  onAcknowledgeComplete: () => void;
}
```
On component mount, generate two random 8-character codes formatted as `XXXX-XXXX`.
State variables: `codes: string[]`, `isCopied: boolean`, `isLoading: boolean`.
Clicking "Copy Codes" writes them to `navigator.clipboard` and toggles `isCopied` state for 3 seconds.
Clicking "I Have Saved These Codes":
- Hash the backup codes using SHA-256.
- Send POST to `/v1/auth/backup-codes/regenerate` with headers: `Authorization: Bearer [userIdToken]`
- Request body shape: `{ hashedBackupCodes: string[] }`
- On success, trigger `onAcknowledgeComplete()`.
Accessibility requirements: Ensure the monospace code text uses high-contrast colors, and copy buttons announce copy feedback to screen readers.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame showing the "Save Recovery Codes" screen (SCR-ONB-04).
Background is black (#141218). Large header text: "Save Recovery Codes".
A dark charcoal box (#1C1B1F) in the center displays two distinct codes in high-contrast purple text: "A83B-KJ92" and "91FF-PL03" in monospace font.
Below the codes, a button with a clipboard icon reads "Copy to Clipboard".
At the bottom, a bright purple pill-shaped button contains the white text: "I Have Saved These Codes".
A red text warning below it warns: "Keep these safe to avoid lockout."
```

---

## 2. Authentication Screen Module (ATH)

### SCR-ATH-01 — Security Question Challenge Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Security Question Challenge" (SCR-ATH-01).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: Centered header "Security Challenge" (Headline Medium, 28sp, color on-background #E6E1E5). Subtitle: "New device login detected. Verify your identity to download your encryption VMK." (Body Large, 16sp, color secondary #CCC2DC), margins 16dp.
2. Question Prompt Box: A Card (width 328dp, height 80dp, background surface #1C1B1F, rounded radius-lg 16dp). Displays the question string: "What was the name of your first pet?" (Body Large, Roboto Bold, 16sp, color on-surface #E6E1E5, centered).
3. Answer Input Field: Text Input Component (width 328dp, height 56dp, rounded radius-md 8dp, border surface-variant #49454F). Label: "Answer" (Label Large, 14sp). Support error states (red border error #F2B8B5, helper text "Incorrect answer. 2 attempts remaining").
4. Bottom Section:
   - Primary Action Button: "Verify Answer" pill button (width 328dp, height 48dp, background primary #D0BCFF, text on-primary #381E72, rounded radius-full 9999dp).
   - "Use Emergency Backup Code" link (Body Large, color primary #D0BCFF, decoration underline, alignment center, margin bottom 24dp).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Security Question Challenge" (SCR-ATH-01).
File Path: src/components/auth/SecurityQuestionChallenge.tsx
Props Interface:
```typescript
interface SecurityQuestionChallengeProps {
  userIdToken: string;
  challengeQuestion: string;
  onVerificationSuccess: (encryptedVmk: string) => void;
  onFallbackToBackupCode: () => void;
}
```
State variables: `answer: string`, `errorMessage: string`, `remainingAttempts: number` (default 3), `isLoading: boolean`.
On submit button click, send POST to `/v1/auth/vmk` with headers: `Authorization: Bearer [userIdToken]`
- Request body shape: `{ securityQuestionAnswer: string }`
- Expected responses:
  - **200 OK**: `{ encryptedVmk: string }` -> Calls `onVerificationSuccess(encryptedVmk)`.
  - **401 Unauthorized**: `{ error: string, attemptsRemaining: number }` -> Decrements counter, displays `errorMessage`.
  - **403 Forbidden**: Redirection to lockout path.
Accessibility: Input field requires standard floating labels and validation announcement tags.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame showing the "Security Challenge" screen (SCR-ATH-01).
The background is dark gray (#141218). At the top, the title text reads "Security Challenge".
In the center, a dark card (#1C1B1F) contains the text: "What was the name of your first pet?".
Below it, an input text box contains the typed answer "Buddy" in white. A red outline border is active on the text box, showing helper text below: "Incorrect answer. 2 attempts remaining."
At the bottom, a prominent purple pill button (#D0BCFF) says "Verify Answer". Below the button is a gray link: "Use Emergency Backup Code".
```

---

### SCR-ATH-02 — PIN Unlock Screen & Variant SCR-ATH-02-P

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "PIN Unlock" (SCR-ATH-02) and "Enterprise PIN Unlock" (SCR-ATH-02-P).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section:
   - For Standard View: Screen Title: "Enter PIN" (Headline Medium, 28sp, color on-background #E6E1E5), followed by subtitle "Unlock your SecureVault database" (Body Large, 16sp, color secondary #CCC2DC).
   - For Enterprise Variant (SCR-ATH-02-P): Include top header banner: "Session Protected. Active devices: 2/3. Session expires in 5 minutes background." (Label Large, 14sp, color warning #E3A857, background surface #1C1B1F, padding 12dp).
2. PIN dot Indicators: Centered row of 6 dots (diameter 12dp, spacing 8dp).
3. Keyboard Grid Section: Numeric Keypad centered at bottom. Bottom-right contains Biometrics trigger icon (face/fingerprint icon, 24dp, color primary #D0BCFF) instead of empty slot.
4. Emergency link: "Reset PIN using Backup Code" (Body Small, color secondary #CCC2DC, alignment center, margin bottom 16dp).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "PIN Unlock Screen" (SCR-ATH-02) supporting variant (SCR-ATH-02-P).
File Path: src/components/auth/PinUnlock.tsx
Props Interface:
```typescript
interface PinUnlockProps {
  userRole: 'General' | 'Student' | 'Professional' | 'Developer';
  activeDevicesCount: number;
  onUnlockSuccess: () => void;
  onLockoutTriggered: () => void;
}
```
State variables: `enteredPin: string`, `failedAttempts: number` (default 0), `isLoading: boolean`.
Keypad actions append digits to `enteredPin`. Once `enteredPin.length === 6`, verify locally against hashed SQLCipher key stored in Keystore (mock comparison).
- If validation succeeds, trigger `onUnlockSuccess()`.
- If validation fails, increment `failedAttempts`.
- If `failedAttempts` reaches 5:
  - Make API POST to `/v1/auth/lockout` with body `{ lockoutDuration: number }`.
  - Trigger `onLockoutTriggered()`.
If `userRole === 'Professional'`, render a dynamic header warning indicating the 5-minute background session expiry logic.
Include a clickable Biometric action key in the bottom-right keypad slot.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame mock displaying the "Enterprise PIN Unlock" screen (SCR-ATH-02-P).
Background color is black (#141218).
At the very top, a dark surface banner (#1C1B1F) features amber warning text: "Session Protected. Active devices: 2/3. Session expires in 5 minutes background."
Below the banner is the title "Enter PIN" and six indicators (five solid purple, one gray ring).
The bottom half shows a circular numeric keypad (1-9, 0, delete arrow). In the bottom-left slot, a small fingerprint graphic in light purple (#D0BCFF) is displayed.
```

---

### SCR-ATH-03 — Lockout Warning Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Lockout Warning Screen" (SCR-ATH-03).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: Large exclamation mark icon (48dp, color error #F2B8B5) centered. Header text: "Account Locked" (Headline Medium, 28sp, color on-background #E6E1E5, centered), subtitle: "Too many failed PIN attempts. Your local database has been temporarily locked to prevent brute-force attacks." (Body Large, 16sp, color secondary #CCC2DC, alignment center), margins 24dp.
2. Timer Section: Centered countdown clock display (Display Large, Roboto Regular, 57sp, color error #F2B8B5, alignment center). Display content: "01:45:20".
3. Bottom Action Block:
   - Primary Action Button: "Reset PIN with Backup Code" pill button (width 328dp, height 48dp, background primary #D0BCFF, text on-primary #381E72, rounded radius-full 9999dp).
   - Micro-disclaimer: "You can reset your lock immediately by providing one of your 8-digit recovery codes." (Body Small, 12sp, color secondary #CCC2DC, alignment center, margin bottom 24dp).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Lockout Warning Screen" (SCR-ATH-03).
File Path: src/components/auth/LockoutWarning.tsx
Props Interface:
```typescript
interface LockoutWarningProps {
  userIdToken: string;
  initialLockoutSeconds: number;
  onLockoutExpired: () => void;
  onResetSuccess: () => void;
}
```
State variables: `timeLeft: number` (initialized with `initialLockoutSeconds`), `backupCode: string`, `showBackupDialog: boolean`, `validationError: string`.
Implement a 1-second interval timer. Decrement `timeLeft` every second. When `timeLeft === 0`, trigger `onLockoutExpired()`.
Format `timeLeft` as `hh:mm:ss` inside a centered display block.
On clicking "Reset PIN with Backup Code", render a modal Dialog component containing a text Input field for the 8-digit recovery code.
Submit recovery code request:
- POST `/v1/auth/backup-codes/verify` with headers: `Authorization: Bearer [userIdToken]`
- Request body shape: `{ backupCode: string }`
- Expected Response: `200 OK` `{ resetToken: string }` -> Calls `onResetSuccess()`.
Ensure the Back button is intercepted and disabled. Keyboard focus should trap within the recovery modal.
```

#### Gemini / Image Generation (Mockup)
```
An Android phone frame displaying the "Account Locked" screen (SCR-ATH-03).
Background is black (#141218).
Near the top, a prominent red warning triangle icon is centered. Below it, white text reads "Account Locked".
A large countdown clock in bright red numbers (#F2B8B5) displays "00:29:45" in the middle of the screen.
At the bottom, a light lavender button (#D0BCFF) says "Reset PIN with Backup Code".
A line of muted gray text below reads: "Use a recovery code to bypass this lockout."
```

---

### SCR-ATH-05 — Insecure Host Warning Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Insecure Host Warning Screen" (SCR-ATH-05) for Developers.
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: Centered alert icon (48dp, color warning #E3A857). Title "Compromised Environment" (Headline Medium, 28sp, color on-background #E6E1E5, centered).
2. Warning Card Box: Card (width 328dp, background surface #1C1B1F, rounded radius-lg 16dp, padding 16dp).
   - Display a list of detected threats:
     - Icon warning, text "Root access binaries detected on host OS." (Body Large, color on-surface #E6E1E5).
     - Icon warning, text "USB debugging mode is currently enabled." (Body Large, color on-surface #E6E1E5).
   - Warning explanation: "Running SecureVault on a rooted device exposes memory registers and clipboard histories to other root-enabled apps. Screen capture or key logging may occur." (Body Large, color secondary #CCC2DC, margin top 16dp).
3. Bottom Action Block:
   - Primary Action Button: "Exit Application" pill button (width 328dp, height 48dp, background error #F2B8B5, text on-error #601410, rounded radius-full 9999dp).
   - Secondary Link: "Bypass & Continue Anyway" (Label Large, color warning #E3A857, decoration underline, alignment center, margin bottom 24dp).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Insecure Host Warning" (SCR-ATH-05).
File Path: src/components/auth/InsecureHostWarning.tsx
Props Interface:
```typescript
interface InsecureHostWarningProps {
  detectedThreats: string[]; // e.g. ["Root detected", "USB Debugging active"]
  onExitApp: () => void;
  onBypassWarning: () => void;
}
```
State variables: `isBypassed: boolean`.
Render a vertical list of threats using card containers with custom warning icons (#E3A857).
Render a red "Exit Application" button (calls `onExitApp()`).
Render a yellow text link "Bypass & Continue Anyway" (calls `onBypassWarning()`).
Ensure high-contrast ratios >= 4.5:1. Aria-labels must explain the risk state and bypass controls.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame showing the "Compromised Environment" screen (SCR-ATH-05).
The screen is dark gray (#141218).
A large orange warning shield icon (#E3A857) sits at the top. Below it, the title reads "Security Alert".
A dark central card (#1C1B1F) lists two items with orange exclamation marks: "1. Device is rooted" and "2. USB Debugging is ON".
At the bottom, there is a red button (#F2B8B5) labeled "Exit Application", and below it, an orange link text reads: "Bypass & Continue Anyway".
```

---

## 3. Password Vault Screen Module (VLT)

### SCR-VLT-01 — Main Dashboard Password Listing

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Main Dashboard Password Listing" (SCR-VLT-01).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: App bar containing Profile Image (32dp circle, top-right) and Title "SecureVault" (Title Large, 22sp, color on-background #E6E1E5). Directly below is a Search Bar input field (width 328dp, height 48dp, rounded radius-full 9999dp, background surface #1C1B1F, search icon on left, sync status indicator badge on right).
2. Filter chips row: 4 horizontal scrollable Chips: "All" (selected, background primary #D0BCFF, text on-primary #381E72), "Personal", "Work", "Banking" (unselected, background surface #1C1B1F, text secondary #CCC2DC).
3. Credentials List: Vertical list container showing 4 list items (height 72dp each):
   - Item 1: Favicon circle container, title "Google", subtitle "user@gmail.com", favorite star icon (active, yellow #E3A857).
   - Item 2: Favicon circle container, title "GitHub", subtitle "dev_account", favorite star icon (inactive, gray #49454F).
   - Item 3: Favicon circle container, title "Chase Bank", subtitle "banking_login", favorite star icon (inactive).
   - Item 4: Favicon circle container, title "Netflix", subtitle "family_stream", favorite star icon (active).
4. Bottom Navigation Bar: Height 80dp, containing 4 navigation tabs: "Vault" (active, colored primary #D0BCFF), "Categories", "Generator", "Settings" (inactive, colored secondary #CCC2DC).
5. Floating Action Button (FAB): Rounded square button (56dp diameter, background primary #D0BCFF, plus icon, elevation-3) positioned bottom-right (16dp margin from navigation bar top).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Vault Dashboard" (SCR-VLT-01).
File Path: src/components/vault/VaultDashboard.tsx
Props Interface:
```typescript
interface CredentialSummary {
  id: string;
  name: string;
  usernameEmail: string;
  favorite: boolean;
  categoryName: string;
}

interface VaultDashboardProps {
  userIdToken: string;
  onAddClick: () => void;
  onCredentialSelect: (id: string) => void;
  onTabSelect: (tabName: string) => void;
}
```
State variables: `credentials: CredentialSummary[]`, `filterCategory: string`, `searchQuery: string`, `syncState: 'synced' | 'syncing' | 'offline'`, `isLoading: boolean`.
On mount, perform sync by querying GET `/v1/vault` (with Bearer Token).
expected response: `200 OK` `CredentialSummary[]`.
Implement a 100ms debounced filter logic matching `searchQuery` against `name` and `usernameEmail` locally.
Render the filter chips and list items. Favorite star clicks update local state and queue operations on `Sync Queue`.
Render the Material 3 Bottom Navigation Bar and Floating Action Button (FAB).
Ensure all list items are keyboard focusable with aria-roles.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame mockup of the "SecureVault Dashboard" screen (SCR-ONB-01/SCR-VLT-01).
Dark mode design. At the top is the search bar containing a search magnifying glass icon and a green synced icon at the right edge.
Below the search bar, horizontal pill chips show "All" in lavender purple, followed by "Work" and "Banking" in dark gray.
A vertical list of items shows credential cards:
- Google (colored logo, username "user@gmail.com", highlighted gold star).
- GitHub (black cat logo, username "git_dev", gray outline star).
- Netflix (red N logo, username "streaming", highlighted gold star).
A circular purple Floating Action Button with a white plus sign rests in the bottom right corner.
At the very bottom, a navigation bar shows active "Vault" in purple, and inactive "Categories", "Generator", "Settings" icons.
```

---

### SCR-VLT-02 — Add/Edit Password Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Add/Edit Password" (SCR-VLT-02).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: Back navigation arrow (top-left). Screen Title: "Add Password" (Headline Medium, 28sp, color on-background #E6E1E5), aligned left, margins 16dp.
2. Input Fields Column: Vertical stack of inputs (each 56dp height, spacing 16dp):
   - Text Input Field: Label "Title / Service Name" (e.g. "Google").
   - Text Input Field: Label "Username / Email" (e.g. "user@gmail.com").
   - Text Input Field (Password Mode): Label "Password". Contains masked dots (`••••••••`), reveal eye icon on the right edge, and a small key shortcut icon to trigger generator.
   - Text Input Field: Label "Website URL" (e.g. "https://accounts.google.com").
   - Dropdown Menu Selector: Label "Category". Current selected option: "Personal".
3. Password Strength Box: Directly below password input. Render strength progress bar (height 6dp, 4 segments, green color success #81C784 showing "Strong" text next to it).
4. Bottom Action Buttons:
   - Primary Action Button: "Save Entry" pill button (width 328dp, height 48dp, background primary #D0BCFF, text on-primary #381E72, rounded radius-full 9999dp).
   - Secondary Button: "Cancel" outline button (width 328dp, height 48dp, border surface-variant #49454F, text secondary #CCC2DC, rounded radius-full 9999dp).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Add/Edit Password Screen" (SCR-VLT-02).
File Path: src/components/vault/CredentialEditor.tsx
Props Interface:
```typescript
interface CredentialEditorProps {
  userIdToken: string;
  credentialId?: string; // If empty, is Add mode.
  onSaveSuccess: () => void;
  onCancel: () => void;
}
```
State variables: `name: string`, `username: string`, `passwordPlain: string`, `url: string`, `categoryId: string`, `isReveal: boolean`, `isSubmitting: boolean`.
If `credentialId` is present, fetch current details on mount from GET `/v1/vault/{id}`.
Expected response: `{ name, usernameEmail, encryptedPassword, websiteUrl, categoryId }` (Client decrypts in-memory).
Implement strength check logic (zxcvbn library mock). Update strength meter UI.
On Form Save submit:
- Encrypt password plain text using in-memory key (mock).
- If Add mode: POST `/v1/vault` with body: `{ name, usernameEmail, encryptedPassword, websiteUrl, categoryId }`.
- If Edit mode: PUT `/v1/vault/{id}` with same body structure.
- Trigger `onSaveSuccess()`.
Ensure back action is intercepted with confirmation popup if form is dirty.
```

#### Gemini / Image Generation (Mockup)
```
An Android phone frame showing the "Add Password" screen (SCR-VLT-02).
Background is dark charcoal (#141218).
At the top, "Add Password" is shown next to an arrow pointing left.
A form displays input boxes with labels and text:
- Title: "Google"
- Username/Email: "user@gmail.com"
- Password: "••••••••••••" (the eye icon is crossed out, and a small key generator icon is on the right)
- Website URL: "https://google.com"
Below the password field, a horizontal progress bar has 4 green blocks filled, with the green label: "Strong".
At the bottom, a primary purple button reads "Save Entry", and a gray outlined button below it reads "Cancel".
```

---

### SCR-VLT-03 — Password Details Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Password Details Screen" (SCR-VLT-03).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: App bar containing back arrow (top-left), edit pencil icon (top-right, 24dp, color primary #D0BCFF), and delete garbage can icon (top-right, 24dp, color error #F2B8B5). Title: "Credential Details" (Title Large, 22sp, color on-background #E6E1E5).
2. Detail Summary Box: Centered large favicon image (64dp circle) followed by Title "Google" (Headline Medium, 28sp, color on-background #E6E1E5) and category tag chip "Personal" (background surface-variant #49454F, text secondary #CCC2DC).
3. Detail Information Rows: Card (width 328dp, background surface #1C1B1F, rounded radius-lg 16dp) displaying rows:
   - Row 1: Label "Username / Email", value "user@gmail.com" with copy icon button on the right.
   - Row 2: Label "Password", value masked dots `••••••••••••` with reveal eye icon and copy icon button on the right.
   - Row 3: Label "Website URL", value "https://accounts.google.com" with open-link icon button.
4. Password History Subsection: Title "Password History" (Title Large, 22sp, color on-background #E6E1E5) followed by a list of 2 old passwords (value masked `••••••••` with date subtitle e.g. "Changed June 1, 2026").
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Password Details Screen" (SCR-VLT-03).
File Path: src/components/vault/CredentialDetails.tsx
Props Interface:
```typescript
interface PasswordHistoryEntry {
  id: string;
  encryptedPasswordVal: string;
  createdAt: number;
}

interface CredentialDetailsProps {
  userIdToken: string;
  credentialId: string;
  onEditClick: (id: string) => void;
  onDeleted: () => void;
}
```
State variables: `name: string`, `username: string`, `passwordDecrypted: string`, `url: string`, `categoryName: string`, `history: PasswordHistoryEntry[]`, `isRevealed: boolean`, `isCopied: boolean`.
Fetch details on mount from GET `/v1/vault/{id}`.
Implement clipboard copy logic for Username and Password. Clipboard password copy triggers a 30-second setTimeout countdown to wipe the clipboard data.
Delete button triggers a Dialog component for confirmation. If confirmed, call DELETE `/v1/vault/{id}` and trigger `onDeleted()`.
Make sure clipboard operations are accessible and announce visual and text updates to screen readers.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame showing the "Credential Details" screen (SCR-VLT-03).
Deep charcoal background (#141218).
At the top, a back arrow is adjacent to the title "Google". An edit pencil icon and a red trash can icon sit at the top-right.
In the upper half, a large colorful Google logo is displayed above the title "Google" and a gray tag: "Personal".
Below it, a dark gray container (#1C1B1F) contains detail fields:
- Username: "user@gmail.com" (with a copy icon)
- Password: "••••••••" (with an open eye icon and a copy icon)
- URL: "https://accounts.google.com" (with a small external link icon)
At the bottom, under "Password History", two rows show "••••••••" next to dates: "June 2026" and "March 2026".
```

---

### SCR-VLT-04 — Categories Management Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Categories Management Screen" (SCR-VLT-04).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: App bar with title "Categories" (Title Large, 22sp, color on-background #E6E1E5). Subtitle: "Organize your vault items into custom folders." (Body Large, 16sp, color secondary #CCC2DC), margins 16dp.
2. Add Category Input: Form block at top (width 328dp, height 56dp) containing text input field with placeholder "New Category Name..." and an Add plus button nested on the right edge of the input.
3. Category List: Vertical column of list items (height 56dp each):
   - Item 1: Folder icon, text "Personal (12 entries)", delete trash icon on right (disabled for default categories).
   - Item 2: Folder icon, text "Work (8 entries)", delete trash icon on right.
   - Item 3: Folder icon, text "Banking (4 entries)", delete trash icon on right.
   - Item 4: Folder icon, text "Custom Gaming (2 entries)", active delete trash icon on right.
4. Bottom Navigation Bar: Height 80dp, containing 4 navigation tabs: "Vault" (inactive), "Categories" (active, colored primary #D0BCFF), "Generator" (inactive), "Settings" (inactive).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Categories Management" (SCR-VLT-04).
File Path: src/components/vault/CategoriesManager.tsx
Props Interface:
```typescript
interface CategoryItem {
  id: string;
  name: string;
  count: number;
  isDefault: boolean;
}

interface CategoriesManagerProps {
  userIdToken: string;
  onTabSelect: (tabName: string) => void;
}
```
State variables: `categories: CategoryItem[]`, `newCategoryName: string`, `isSubmitting: boolean`, `errorMsg: string`.
On mount, fetch data from GET `/v1/categories` (response: `{ categories: CategoryItem[] }`).
Add Category handler: validates `newCategoryName` is not duplicate, sends POST `/v1/categories` with body `{ name: string }`.
Delete Category handler: opens confirmation dialog, on confirm sends DELETE `/v1/categories/{id}`.
Ensure default categories (Personal, Work, Banking, Shopping, Social) cannot be deleted.
Aria roles: The list items should announce folder counts and delete button safety.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame showing the "Categories" screen (SCR-VLT-04).
Background is black (#141218).
At the top, "Categories" is shown in title font. Below is an input field showing "New Category Name..." with a purple "+" button inside.
A list of category rows displays:
- Personal (12 items) - greyed lock icon next to it.
- Work (8 items) - greyed lock icon.
- Shopping (5 items) - greyed lock icon.
- Gaming (2 items) - red garbage can icon at the right edge.
At the bottom, the navigation bar highlights "Categories" in purple.
```

---

## 4. Password Generator Screen Module (GEN)

### SCR-GEN-01 — Password Generator Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Password Generator Screen" (SCR-GEN-01).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: App bar with title "Password Generator" (Title Large, 22sp, color on-background #E6E1E5).
2. Password Output Box: Card (width 328dp, height 100dp, background surface #1C1B1F, rounded radius-lg 16dp).
   - Inner content: High-entropy string "K9#m$P2!zQ91-vB" (Code Monospace, Roboto Mono Bold, 18sp, color primary #D0BCFF, centered).
   - Refresh circular arrows icon (24dp, color primary #D0BCFF) on top-right of card.
   - Password strength progress bar directly below (Success green #81C784, 4 blocks filled).
3. Settings Slider:
   - Slider control: Label "Password Length: 15 characters" (Body Large, 16sp). Horizontal slider bar with primary colored drag indicator.
4. Parameter Toggles List: Vertical list of toggle switches (spacing 12dp):
   - Switch 1: "Include Uppercase (A-Z)" - Toggle ON (primary color #D0BCFF).
   - Switch 2: "Include Lowercase (a-z)" - Toggle ON.
   - Switch 3: "Include Numbers (0-9)" - Toggle ON.
   - Switch 4: "Include Special Symbols (!@#$)" - Toggle ON.
   - Switch 5: "Exclude Ambiguous (O, 0, l, 1)" - Toggle OFF.
5. Bottom Section:
   - Primary Action Button: "Copy Password" pill button (width 328dp, height 48dp, background primary #D0BCFF, text on-primary #381E72, rounded radius-full 9999dp).
   - For shortcut overlay mode: Add "Use Password" button variant.
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Password Generator Screen" (SCR-GEN-01).
File Path: src/components/generator/PasswordGenerator.tsx
Props Interface:
```typescript
interface PasswordGeneratorProps {
  isModalMode?: boolean;
  onUsePassword?: (generated: string) => void;
  onTabSelect?: (tabName: string) => void;
}
```
State variables: `length: number` (default 12), `uppercase: boolean` (true), `lowercase: boolean` (true), `numbers: boolean` (true), `symbols: boolean` (true), `excludeAmbiguous: boolean` (false), `generatedVal: string`, `strength: 'Weak' | 'Medium' | 'Good' | 'Strong'`.
Implement a local random generation algorithm on state change. Calculate strength (mock logic).
Copy button handler copies `generatedVal` to system clipboard.
If `isModalMode === true`, render a secondary button "Use Password" which calls `onUsePassword(generatedVal)`.
Ensure touch target elements are >= 48dp (including switches and slider handles).
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone mock showing the "Password Generator" screen (SCR-GEN-01).
The screen is dark (#141218).
A dark central card (#1C1B1F) displays a generated password in a bold purple monospace font: "gK9!wQ#2vL99-xP".
Below the password, a 4-segmented bar shows full green.
Underneath, a slider bar displays a handle pointing to "Length: 15".
Four toggles display a purple switch set to ON for: "Uppercase", "Lowercase", "Numbers", and "Symbols".
At the bottom, a primary purple button says "Copy Password".
```

---

### SCR-GEN-02 — Password Health Dashboard Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Password Health Dashboard" (SCR-GEN-02).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: App bar with back navigation arrow and title "Password Health" (Title Large, 22sp, color on-background #E6E1E5). Subtitle: "Audit checks on your local credentials vault." (Body Large, 16sp, color secondary #CCC2DC), margins 16dp.
2. Metrics Cards Row: Horizontal scrolling cards (width 120dp each, height 100dp):
   - Card 1: Centered big number "4" (Display Large, error red #F2B8B5), text label "Weak Passwords".
   - Card 2: Centered big number "2" (Display Large, warning amber #E3A857), text label "Reused Passwords".
   - Card 3: Centered big number "24" (Display Large, success green #81C784), text label "Strong Passwords".
3. Security Recommendations Listing: Title "Security Recommendations" (Title Large, 22sp). Vertical list of 2 warnings:
   - Warning Card 1: Icon red warning, title "Google password reuse", body "This password is reused across Netflix. Change it immediately." (Body Large, color on-surface #E6E1E5).
   - Warning Card 2: Icon amber warning, title "GitHub strength check", body "Your GitHub password is under 8 characters long." (Body Large, color on-surface #E6E1E5).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Password Health Dashboard" (SCR-GEN-02).
File Path: src/components/generator/PasswordHealth.tsx
Props Interface:
```typescript
interface HealthMetrics {
  weakCount: number;
  reusedCount: number;
  strongCount: number;
  recommendations: Array<{ id: string; title: string; desc: string; type: 'error' | 'warning' }>;
}

interface PasswordHealthProps {
  userIdToken: string;
  onRecommendationSelect: (id: string) => void;
  onBackClick: () => void;
}
```
State variables: `metrics: HealthMetrics | null`, `isLoading: boolean`.
On mount, perform vault audits. Loop through SQLCipher database entries locally (mock logic processing) to calculate counts and compile recommendation cards.
Render horizontal scrollable dashboard widgets.
Render list items for recommendations. Clicking items redirects to details or editor view (triggers `onRecommendationSelect`).
Implement screen reader announcements for health status levels.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame showing the "Password Health" screen (SCR-GEN-02).
Background is black (#141218).
The top displays metrics cards side-by-side:
- A red card showing "4" with the subtitle "Weak".
- An orange card showing "2" with the subtitle "Reused".
- A green card showing "24" with the subtitle "Strong".
Below the metrics, a vertical list contains recommendation items:
- A card with a red shield says: "Google Password Reused". Subtitle: "Reused across 2 other accounts."
- A card with an orange shield says: "GitHub Password Weak". Subtitle: "Length is 6 characters."
```

---

## 5. Settings Screen Module (SET)

### SCR-SET-01 — Settings / Profile Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Settings / Profile Screen" (SCR-SET-01).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: App bar with title "Settings" (Title Large, 22sp, color on-background #E6E1E5).
2. User Profile Card: Card (width 328dp, height 80dp, background surface #1C1B1F, rounded radius-lg 16dp).
   - Inner contents: Profile Image circle (48dp, left aligned), text block (right of image) containing User Name "John Doe" (Title Large, 22sp, color on-surface #E6E1E5) and Google email "john.doe@gmail.com" (Body Large, 16sp, color secondary #CCC2DC).
3. Settings Item List: Vertical list of navigation items (height 56dp each):
   - Item 1: Security Shield icon, text "Security Settings" (Body Large).
   - Item 2: Devices icon, text "Active Devices (2 active)" (Body Large).
   - Item 3: Trash icon, text "Trash Folder (3 items)" (Body Large).
   - Item 4: PDF document icon, text "Export Vault to Secure PDF" (Body Large).
   - Item 5: CSV document icon, text "Export Vault to CSV" (Body Large).
   - Item 6: Logout icon, text "Logout Account" (Body Large, color error #F2B8B5).
4. Bottom Navigation Bar: Height 80dp, containing 4 navigation tabs: "Vault" (inactive), "Categories" (inactive), "Generator" (inactive), "Settings" (active, colored primary #D0BCFF).
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Settings / Profile Screen" (SCR-SET-01).
File Path: src/components/settings/SettingsProfile.tsx
Props Interface:
```typescript
interface UserProfile {
  name: string;
  email: string;
  avatarUrl: string;
}

interface SettingsProfileProps {
  userIdToken: string;
  profile: UserProfile;
  onNavigate: (routeId: string) => void;
  onLogout: () => void;
}
```
State variables: `isExporting: boolean`, `exportError: string`, `showChallengeModal: boolean`, `pendingAction: 'pdf' | 'csv' | null`.
Render settings list items.
Logout item clears in-memory VMK and calls `onLogout()`.
Export items trigger a security verification overlay:
- Render Security Question Challenge Modal on export trigger.
- Verification passes -> triggers local PDF or CSV export generation functions.
Ensure minimum touch target is 48px. Standard accessibility props (aria-labels) must be bound.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone mock showing the "Settings" screen (SCR-SET-01).
Background is black (#141218).
At the top, a card shows a circular user photo next to the name "John Doe" and email "john.doe@gmail.com".
A list of option rows below shows:
- Security Settings (shield icon)
- Active Devices (phone icon, badge showing "2")
- Trash Folder (trash icon, badge showing "3")
- Export Vault to PDF (file icon)
- Export Vault to CSV (file icon)
- Logout Account (exit icon in red)
At the bottom, the navigation bar highlights "Settings" in purple.
```

---

### SCR-SET-02 — Security Settings Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Security Settings Screen" (SCR-SET-02).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: App bar with back navigation arrow and title "Security Settings" (Title Large, 22sp, color on-background #E6E1E5). Margins 16dp.
2. Toggle Settings List: Vertical stack of setting control rows (spacing 16dp):
   - Row 1: Text block "Enable Biometrics" (Title: Body Large, Description: Body Small "Unlock local vault using fingerprint or face verification"), Switch Component on the right (Toggle ON, primary color #D0BCFF).
   - Row 2: Text block "Autofill Integration" (Title: Body Large, Description: Body Small "Link SecureVault credentials to Android Autofill Framework"), Switch Component on the right (Toggle OFF, color surface-variant #49454F).
3. Action List Block: Card (width 328dp, background surface #1C1B1F, rounded radius-lg 16dp) containing menu actions:
   - Action item 1: Text "Change Local App PIN", key arrow-forward icon on right.
   - Action item 2: Text "Regenerate Backup Recovery Codes", key arrow-forward icon on right.
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Security Settings" (SCR-SET-02).
File Path: src/components/settings/SecuritySettings.tsx
Props Interface:
```typescript
interface SecuritySettingsProps {
  userIdToken: string;
  initialBiometricState: boolean;
  initialAutofillState: boolean;
  onBackClick: () => void;
  onPinChangeRequest: () => void;
  onBackupRegenRequest: () => void;
}
```
State variables: `biometricsEnabled: boolean`, `autofillEnabled: boolean`, `isLoading: boolean`.
Switch toggles update local device SharedPreferences configurations.
Regenerate codes and change PIN items trigger security checks before navigating (trigger `onPinChangeRequest` and `onBackupRegenRequest` callbacks).
Ensure touch targets are >= 48dp. Switches must have visible state labels and toggle roles for screen readers.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame showing the "Security Settings" screen (SCR-SET-02).
Background is black (#141218).
The top features a back arrow next to "Security Settings".
A list of configurations is displayed:
- Biometric Unlock: Toggle switch is ON (purple color).
- Android Autofill: Toggle switch is OFF (gray color).
Below, a dark box (#1C1B1F) contains two menu choices with right arrow indicators:
- "Change Local App PIN"
- "Regenerate Backup Recovery Codes"
```

---

### SCR-SET-03 — Trash Management Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Trash Management Screen" (SCR-SET-03).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: App bar with back navigation arrow and title "Trash Folder" (Title Large, 22sp, color on-background #E6E1E5). On the top-right, a prominent "Empty Trash" button (Label Large, 14sp, color error #F2B8B5).
2. Info Banner: "Passwords here will be permanently deleted after 30 days." (Body Small, 12sp, color secondary #CCC2DC, margin 16dp).
3. Soft-deleted Items List: Vertical column of list items (height 72dp each):
   - Item 1: Favicon circle, text "Facebook", subtitle "deleted 5 days ago (25 days left)", restore arrow-counter-clockwise icon (24dp, color primary #D0BCFF), purge delete icon (24dp, color error #F2B8B5) on right.
   - Item 2: Favicon circle, text "Adobe Cloud", subtitle "deleted 12 days ago (18 days left)", restore icon, purge icon.
   - Item 3: Favicon circle, text "Slack Workspace", subtitle "deleted 28 days ago (2 days left)", restore icon, purge icon.
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Trash Management Screen" (SCR-SET-03).
File Path: src/components/settings/TrashManagement.tsx
Props Interface:
```typescript
interface DeletedCredential {
  id: string;
  name: string;
  usernameEmail: string;
  deletedAt: number;
  daysRemaining: number;
}

interface TrashManagementProps {
  userIdToken: string;
  onBackClick: () => void;
}
```
State variables: `trashItems: DeletedCredential[]`, `showEmptyConfirm: boolean`, `showItemPurgeConfirm: boolean`, `selectedItemId: string`, `isLoading: boolean`.
On mount, fetch trash from GET `/v1/vault` (with query parameter `trash=true`).
Restore handler: Update record locally (remove `deletedAt`) and queue sync. Remove from local list.
Purge handler: Launches Dialog. On confirm, send DELETE `/v1/vault/{id}` (permanent flag).
Empty Trash handler: Launches Dialog. On confirm, send DELETE `/v1/vault/trash/empty` (expected response: `200 OK`).
Ensure Dialog overlays are accessible, traps keyboard focus, and announces deletion state.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone mock showing the "Trash Folder" screen (SCR-SET-03).
Background is black (#141218).
At the top, "Trash Folder" is adjacent to a left pointing arrow. A red button at the top right says "Empty Trash".
A banner reads: "Items are kept for 30 days before purging."
Below, the list of deleted items shows:
- Facebook (blue icon, subtitle "deleted 5 days ago", restore arrow icon, red delete cross icon).
- Adobe (red logo, subtitle "deleted 12 days ago", restore icon, red delete cross).
- Slack (colored logo, subtitle "deleted 28 days ago", restore icon, red delete cross).
```

---

## 6. Device Sessions Screen Module (DEV)

### SCR-DEV-01 — Active Devices Screen

#### Figma AI
```
Generate an Android mobile screen layout (360x800 dp) for "Active Devices Screen" (SCR-DEV-01).
Visual Style: Dark Theme. Background: background (#141218).
Layout Structure:
1. Top Section: App bar with back navigation arrow and title "Active Devices" (Title Large, 22sp, color on-background #E6E1E5). Subtitle: "Manage device session logs. Vault is limited to a maximum of 3 concurrent sessions." (Body Large, 16sp, color secondary #CCC2DC), margins 16dp.
2. Active Sessions List: Vertical column of session cards (width 328dp, height 96dp):
   - Card 1 (Current Device): Phone icon, title "Pixel 7 Pro (Current Device)" (Title Large, 22sp, color primary #D0BCFF), subtitle "Android 14 • Active now" (Body Large, 16sp, color secondary #CCC2DC). No action button.
   - Card 2: Tablet icon, title "Pixel Tablet", subtitle "Android 13 • Last active June 10, 2026", logout action button (Label Large, 14sp, color error #F2B8B5) positioned right.
   - Card 3: Phone icon, title "Samsung Galaxy S22", subtitle "Android 12 • Last active May 24, 2026", logout action button positioned right.
```

#### Bolt / Lovable / v0 (Component Code)
```
Create a React 18 TypeScript component for "Active Devices Screen" (SCR-DEV-01).
File Path: src/components/settings/ActiveDevices.tsx
Props Interface:
```typescript
interface DeviceSession {
  id: string;
  deviceName: string;
  androidVersion: string;
  lastActiveTime: number;
  isCurrent: boolean;
}

interface ActiveDevicesProps {
  userIdToken: string;
  onBackClick: () => void;
  onSessionRevoked: (revokedId: string) => void;
}
```
State variables: `sessions: DeviceSession[]`, `showRevokeConfirm: boolean`, `selectedSessionId: string`, `isLoading: boolean`.
On mount, fetch active sessions from GET `/v1/devices` (expected response: `200 OK` `{ sessions: DeviceSession[] }`).
Revoke session click triggers confirmation Dialog. On confirm, send DELETE `/v1/devices/{id}` (Bearer Token headers).
- On success, remove from `sessions` list locally and trigger `onSessionRevoked(id)`.
Accessibility: Active device cards require high-contrast outlines and clear logout button aria-labels.
```

#### Gemini / Image Generation (Mockup)
```
An Android smartphone frame showing the "Active Devices" screen (SCR-DEV-01).
Background is black (#141218).
At the top, "Active Devices" title with a left arrow.
A list of device session widgets displays:
- Pixel 7 Pro: A badge reads "Current Device". Subtitle: "Android 14 • Active now".
- Pixel Tablet: Subtitle "Android 13 • Active 2 hours ago". A red button on the right says "Logout".
- Samsung Galaxy S22: Subtitle "Android 12 • Active 3 days ago". A red button says "Logout".
```

---

## 7. Global Component Prompts

### Button Component Prompt
```
Create a React 18 TypeScript Button component named "VaultButton" supporting Material 3 states.
Props:
```typescript
interface VaultButtonProps {
  label: string;
  variant: 'primary' | 'secondary' | 'destructive';
  disabled?: boolean;
  loading?: boolean;
  icon?: React.ReactNode;
  onClick: () => void;
}
```
Implement full visual styles matching:
- primary: `#D0BCFF` background, `#381E72` text.
- secondary: Transparent background, 1px `#CCC2DC` border, `#CCC2DC` text.
- destructive: `#F2B8B5` background, `#601410` text.
Support disabled state (38% opacity, non-interactive) and loading state (replaces text with dynamic Tailwind loading spinner). Min touch target 48px.
```

### Input Field Component Prompt
```
Create a React 18 TypeScript input text component named "VaultInput" with floating labels.
Props:
```typescript
interface VaultInputProps {
  label: string;
  value: string;
  placeholder?: string;
  type?: 'text' | 'password' | 'url';
  error?: string;
  disabled?: boolean;
  onChange: (val: string) => void;
  onBlur?: () => void;
}
```
Styles: Height 56px, border radius 8px, default border color `#49454F`. Active focus animates border to 2px primary `#D0BCFF`. Error active switches borders and helper label to red `#F2B8B5`.
```

### Card Component Prompt
```
Create a React 18 TypeScript card container component named "VaultCard" with Material 3 shadows.
Props:
```typescript
interface VaultCardProps {
  elevation?: 0 | 1 | 2 | 3 | 4 | 5;
  children: React.ReactNode;
  onClick?: () => void;
}
```
Map elevation properties to tailwind shadow offsets and surface color tints based on Design.md specs.
```

### List Item Component Prompt
```
Create a React 18 TypeScript List Item component named "VaultListItem" supporting touch ripple.
Props:
```typescript
interface VaultListItemProps {
  title: string;
  subtitle?: string;
  leading?: React.ReactNode;
  trailing?: React.ReactNode;
  onClick?: () => void;
}
```
Height: 72px (two-line) or 56px (one-line). Background flat with `#49454F` bottom border separator.
```

### Bottom Sheet Component Prompt
```
Create a React 18 TypeScript Bottom Sheet component named "VaultBottomSheet" trapping focus.
Props:
```typescript
interface VaultBottomSheetProps {
  isOpen: boolean;
  onClose: () => void;
  children: React.ReactNode;
}
```
Slide-up entrance animation using CSS transitions. Centered on desktop, bottom anchored on mobile. Uses `#1C1B1F` background and rounded top corners (16px).
```

### Dialog Component Prompt
```
Create a React 18 TypeScript Dialog component named "VaultDialog" for confirmation steps.
Props:
```typescript
interface VaultDialogProps {
  isOpen: boolean;
  title: string;
  message: string;
  confirmLabel: string;
  cancelLabel: string;
  isDestructive?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}
```
Uses modal backdrop overlay blocking background actions. Focus traps inside dialog. Destructive button uses red styling.
```

### Toast Component Prompt
```
Create a React 18 TypeScript Toast notification component named "VaultToast".
Props:
```typescript
interface VaultToastProps {
  message: string;
  type: 'success' | 'warning' | 'error' | 'info';
  duration?: number;
  onDismiss: () => void;
}
```
Enters via fade-in from top, dismisses after duration (default 3000ms). ARIA polite announcement.
```

### Badge Component Prompt
```
Create a React 18 TypeScript Badge status component named "VaultBadge".
Props:
```typescript
interface VaultBadgeProps {
  value?: string | number;
  type: 'alert' | 'muted';
}
```
Round shape, full radius, small size. Alert uses red `#F2B8B5` styling.
```

### Tab Component Prompt
```
Create a React 18 TypeScript Tab layout component named "VaultTabs".
Props:
```typescript
interface VaultTabsProps {
  tabs: string[];
  activeTab: string;
  onTabChange: (tabName: string) => void;
}
```
Renders bottom active horizontal line indicator (2px height, `#D0BCFF` color) sliding under active tab text.
```

### Navigation Bar Component Prompt
```
Create a React 18 TypeScript Navigation Bar component named "VaultNavigationBar".
Props:
```typescript
interface NavigationBarProps {
  activeModule: 'vault' | 'categories' | 'generator' | 'settings';
  onNavigate: (module: 'vault' | 'categories' | 'generator' | 'settings') => void;
}
```
Renders bottom-docked panel, height 80px, with centered icons and labels. Selected item displays dynamic pill background container.
```

### Empty State Component Prompt
```
Create a React 18 TypeScript Empty State placeholder component named "VaultEmptyState".
Props:
```typescript
interface VaultEmptyStateProps {
  icon: React.ReactNode;
  title: string;
  description: string;
  actionLabel?: string;
  onActionClick?: () => void;
}
```
Centered layout, gray surface-variant icon, title text, body copy, and primary action button.
```

### Skeleton Component Prompt
```
Create a React 18 TypeScript Skeleton loader component named "VaultSkeleton".
Props:
```typescript
interface VaultSkeletonProps {
  width: string;
  height: string;
  circle?: boolean;
}
```
Pulsate opacity animation (30% to 70% opacity over 1.2s intervals) using light gray color background block.
```

### Chip Component Prompt
```
Create a React 18 TypeScript Chip selector component named "VaultChip".
Props:
```typescript
interface VaultChipProps {
  label: string;
  selected: boolean;
  onClick: () => void;
}
```
Pill shape (height 32px), horizontal padding 12px, selected background `#D0BCFF` with text `#381E72`.
```

### Slides Carousel Component Prompt
```
Create a React 18 TypeScript Carousel component named "VaultCarousel" for onboarding slides.
Props:
```typescript
interface Slide {
  image: string;
  title: string;
  subtitle: string;
}
interface VaultCarouselProps {
  slides: Slide[];
}
```
Renders swipe-enabled slider container with bottom aligned active page indicator dots.
```

### Numeric Keypad Component Prompt
```
Create a React 18 TypeScript Keypad component named "VaultNumericKeypad".
Props:
```typescript
interface VaultNumericKeypadProps {
  onDigitPress: (digit: string) => void;
  onDeletePress: () => void;
  onBiometricPress?: () => void;
}
```
Renders a 3x4 grid of rounded buttons (Circle size 64px diameter). Custom accessibility labels for each numeric cell.
```

### PIN Dot Indicators Component Prompt
```
Create a React 18 TypeScript PIN dots progress indicator named "VaultPinDots".
Props:
```typescript
interface VaultPinDotsProps {
  length: number;
  maxLength: number;
}
```
Renders `maxLength` circular dots horizontally. Dots are filled with `#D0BCFF` up to the active `length` index.
```

### Password Strength Meter Component Prompt
```
Create a React 18 TypeScript Strength Meter component named "VaultStrengthMeter".
Props:
```typescript
interface VaultStrengthMeterProps {
  score: 0 | 1 | 2 | 3 | 4; // Weak, Medium, Good, Strong
}
```
Renders a 6px horizontal progress bar split into 4 segments. Color shifts dynamically (red, amber, blue, green).
```

### Floating Action Button (FAB) Component Prompt
```
Create a React 18 TypeScript Floating Action Button component named "VaultFAB".
Props:
```typescript
interface VaultFABProps {
  onClick: () => void;
}
```
Renders a circular 56px action button styled with Material 3 rounded square curvature, elevated shadows, and plus sign icon.
```

---

## 8. Design Token Prompt

```
Generate the design token values for SecureVault.
Output the tokens in Kotlin classes for Jetpack Compose (Android).
Requirements:
1. Define a Color companion object with hex colors:
   - Primary: 0xFFD0BCFF
   - PrimaryContainer: 0xFF4F378B
   - Secondary: 0xFFCCC2DC
   - Surface: 0xFF1C1B1F
   - SurfaceVariant: 0xFF49454F
   - Background: 0xFF141218
   - Error: 0xFFF2B8B5
   - Warning: 0xFFE3A857
   - Success: 0xFF81C784
   - Info: 0xFF64B5F6
   - OnPrimary: 0xFF381E72
   - OnSurface: 0xFFE6E1E5
   - OnBackground: 0xFFE6E1E5
   - OnError: 0xFF601410
2. Define typography scales:
   - DisplayLarge (57.sp size, 64.sp line height)
   - HeadlineMedium (28.sp size, 36.sp line height)
   - TitleLarge (22.sp size, 28.sp line height)
   - BodyLarge (16.sp size, 24.sp line height)
   - LabelLarge (14.sp size, 20.sp line height)
   - CodeMonospace (14.sp size, 20.sp line height, using FontFamily.Monospace)
3. Define spacing steps:
   - SpacingXS = 4.dp
   - SpacingS = 8.dp
   - SpacingM = 16.dp
   - SpacingL = 24.dp
   - SpacingXL = 32.dp
   - SpacingXXL = 48.dp
4. Define shape corners:
   - ShapeNone = RoundedCornerShape(0.dp)
   - ShapeSmall = RoundedCornerShape(4.dp)
   - ShapeMedium = RoundedCornerShape(8.dp)
   - ShapeLarge = RoundedCornerShape(16.dp)
   - ShapeFull = CircleShape
5. Define elevation levels:
   - ElevationNone = 0.dp
   - ElevationLow = 1.dp
   - ElevationMedium = 3.dp
   - ElevationFAB = 6.dp
   - ElevationDialog = 8.dp
   - ElevationSheet = 12.dp
Ensure all components inherit from these token classes directly.
```
