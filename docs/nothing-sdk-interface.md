# Nothing Glyph Matrix SDK: service interface (interoperability notes)

Purpose: let apps built with Nothing's Glyph Matrix SDK draw through OpenGlyph
(EU Directive 2009/24/EC art. 6). Only the app↔service interface is recorded here, in our
own words. No SDK code is copied, and the `.aar` is not in this repository.

## Findings so far (2026-10-18, `javap -p -constants` on SDK 2.0)

- **Binder interface:** `com.nothing.thirdparty.IGlyphService`, descriptor
  `"com.nothing.thirdparty.IGlyphService"`. The service lives in package `com.nothing.thirdparty`.
- **Transactions** (AIDL order, `FIRST_CALL_TRANSACTION` = 1):

  | code | method |
  |---|---|
  | 1 | `void setFrameColors(int[])` |
  | 2 | `void openSession()` |
  | 3 | `void closeSession()` |
  | 4 | `boolean register(String)` |
  | 5 | `boolean registerSDK(String, String)` |
  | 6 | `boolean registerMatrixSDK(String)` |
  | 7 | `void setMatrixColors(int[])` |
  | 8 | `void setGlyphMatrixTimeout(boolean)` |
  | 9 | `void setAppMatrixColors(int[])` |
  | 10 | `void closeAppMatrix()` |

## Binding, frames, toys (found 2026-10-18)

- **Binding:** the SDK binds `com.nothing.thirdparty/com.nothing.thirdparty.GlyphService` with action
  `com.nothing.thirdparty.bind_glyphservice`. Apps declare
  `<uses-permission android:name="com.nothing.ketchum.permission.ENABLE"/>` (README).
- **Frames:** `int[625]` (25x25, row-major), values **0..4095** (the SDK scales object brightness
  0..255 into 0..4095). Same scale as OpenGlyph's `Frame.MAX`.
  - Toys call `setMatrixColors` (7); apps call `setAppMatrixColors` (9) and `closeAppMatrix` (10).
  - `registerMatrixSDK(device)` (6) is the device registration (`"23112"` family for Phone (3)).
- **Toys** (README + `GlyphToy` constants): service with action `com.nothing.glyph.TOY` and meta-data
  `com.nothing.glyph.toy.{name,image,summary,introduction,longpress,aod_support}`. The host binds the
  service while the toy is selected; `onBind` returns a `Messenger`. Events are `Message.what = 1`,
  Bundle key `"data"`, values `change` (long press), `action_down`, `action_up`, `aod`.
- Toys manager deep link (README): `com.nothing.thirdparty/.matrix.toys.manager.ToysManagerActivity`.

## OpenGlyph implementation

- `OpenGlyphCompat` (package `com.nothing.thirdparty`): own AIDL declaration of `IGlyphService`
  with the same descriptor and method order; forwards frames to OpenGlyph (`IOpenGlyph`).
- OpenGlyph discovers `com.nothing.glyph.TOY` services and offers them as toys next to the built-in
  ones (Glyph Button: tap = next toy, long press = `change`, press/release = `action_down/up`).
