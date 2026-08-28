# Play Console — paste-ready listing

Everything below is written to be copied straight into the Play Console field
of the same name. Character limits are noted where Google enforces them.

---

## Store listing

**App name** (30 max — this is 22)

```
Lumen: 3D Merge Puzzle
```

**Short description** (80 max — this is 71)

```
A calm 3D merge puzzle. Slide glowing tiles, fuse them, chase the light.
```

**Full description** (4000 max)

```
Lumen is a merge puzzle rendered in real 3D.

Swipe and every tile on the board slides as a solid object — rounded glass
catching the light, throwing a soft shadow onto the slab beneath it. Matching
tiles fuse into one, pop, and scatter a burst of sparks. Tilt your phone and the
whole scene leans with you.

It is the puzzle you already know how to play, rebuilt to be worth looking at.

CALM BY DESIGN
No timers. No lives. No energy bar telling you to come back in four hours.
Nothing flashes for your attention. The palette is deliberately low-saturation
and the aurora behind the board drifts slowly enough that a long session stays
easy on the eyes.

TWO WAYS TO PLAY
Classic — the board fills, the moves run out, the run ends. Your score stands.
Zen — the board never truly jams. When you run out of room, the smallest tile
quietly dissolves and you keep going. For when you want to think, not lose.

MADE TO RESPECT YOU
No ads. Not one.
No in-app purchases.
No account, no sign-in, no email address.
No analytics and no tracking of any kind.
The app does not even request internet permission, so it physically cannot send
your data anywhere.

BUILT LIGHT
Under 3 MB. Every tile, shadow, spark and background is generated on your device
rather than shipped as artwork, so the whole game installs in seconds and runs
smoothly on modest hardware.

DETAILS
- Real-time 3D lighting, reflections and soft contact shadows
- Merge animations with weight — tiles slide, land, and settle
- Gentle motion parallax driven by your phone's tilt
- Precise haptics that get heavier as the numbers climb
- One-step undo when a swipe goes wrong
- Your board and best score are kept between sessions
- Works offline, always
```

---

## Graphics checklist

| Asset | Requirement | Status |
|---|---|---|
| App icon | 512x512 PNG, 32-bit | `store/play-icon-512.png` |
| Feature graphic | 1024x500 PNG/JPG | `store/play-feature-1024x500.png` |
| Phone screenshots | 2–8, min 320px, 9:16 | from the emulator run |

---

## Data safety form

Answer these exactly:

- **Does your app collect or share any of the required user data types?** → **No**
- **Is all of the user data collected by your app encrypted in transit?** → not
  asked once you answer No above
- **Do you provide a way for users to request that their data is deleted?** → not
  asked once you answer No above

The stored board and best score stay on-device and are never transmitted, which
Google does not classify as collection.

## Content rating questionnaire

- Category: **Game**
- Violence, sexuality, language, controlled substances, gambling, user
  interaction, sharing location, personal info: **No** to all
- Expected result: **Everyone / PEGI 3**

## Ads declaration

**Does your app contain ads?** → **No**

## Other fields

- **Category:** Games → Puzzle
- **Tags:** Puzzle, Casual, Brain games
- **Contact email:** junaidshahid725@gmail.com
- **Privacy policy URL:** must be a public URL — publish `store/PRIVACY.md` to
  GitHub Pages or a Gist and paste that link
- **Target audience:** 13+ is simplest; choosing under-13 pulls the app into
  Families policy and adds requirements

---

## Release process, in order

1. **Create the developer account** — $25 one-time, payable by card. This is
   yours to do; account creation and payment cannot be delegated.
2. **Generate an upload keystore** — see `store/SIGNING.md`. Back it up. Losing
   it costs you the ability to update the app.
3. **Add the four repository secrets** so CI can build the signed bundle.
4. **Push to main** — the workflow produces `lumen-release-aab`.
5. **Closed test with 12 testers for 14 continuous days.** This applies to new
   personal developer accounts and is the longest pole in the whole process.
   Recruit the 12 before you start, or the clock does not begin.
6. **Apply for production access**, then submit for review.

### The rejection risk worth naming

Play policy on repetitive content is applied hard to merge-puzzle clones. What
keeps Lumen on the right side of it is that the presentation is genuinely
original — an own 3D renderer, own art direction, own mark — and that it carries
no ads or dark patterns. Two things to hold to:

- Do not put "2048" in the app name. Use it once in the full description at
  most, if at all. Naming a title after someone else's game invites both a
  policy flag and a trademark complaint.
- Lead the screenshots with the 3D board. The visual difference is the argument
  for why this is not the thousandth clone.
