# Interior sprite raw templates

The PNGs under `raw/` are **one-off AI-generated image assets** (magenta
`#FF00FF`-keyed pixel-art frame strips). They are committed as the frozen
source of truth for the bubble-interior pixel sprite sheet and **cannot be
regenerated**: there is no prompt/seed/pipeline in this repository that
reproduces them, so treat them like hand-drawn art — never delete or
"re-derive" them, and never hand-edit them (any edit permanently changes the
derived sheet).

| raw file | size | layout | subject |
| --- | --- | --- | --- |
| `taco_frames_raw.png` | 1536x1024 | 8 horizontal cells | taco (8 frames) |
| `donut_frames_raw.png` | 1536x1024 | 4 horizontal cells | donut |
| `duck_frames_raw.png` | 1536x1024 | 4 horizontal cells | rubber duck |
| `disco_ball_frames_raw.png` | 1536x1024 | 4 horizontal cells | disco ball |
| `fish_frames_raw.png` | 1536x1024 | 4 horizontal cells | fish |
| `book_frames_raw.png` | 1536x1024 | 4 horizontal cells | book |
| `cat_frames_raw.png` | 1536x1024 | 4 horizontal cells | cat |
| `lava_blob_frames_raw.png` | 1536x1024 | 6 horizontal cells | lava-lamp blob |
| `glyph_set_raw.png` | 1024x1024 | 4x4 grid of 16 | matrix glyphs |

What IS deterministic and re-runnable is the post-processing step
`tools/gen_interior_sprites.py` (magenta keying, erosion-robust bbox,
de-fringe, 64x64 nearest downscale, 16-color quantization, sheet assembly),
which reads these frozen inputs and writes

* `src/main/resources/assets/bubbleshield/textures/interior/interior_pixel.png`
  (512x512, 8x8 grid of 64px cells, NEAREST — deliberately no `.png.mcmeta`),
* `src/main/resources/assets/bubbleshield/textures/interior/interior_soft.png`
  (+ `.png.mcmeta` for LINEAR/clamp) — this one is fully procedural (numpy)
  and does not depend on the raw templates at all.

Cell (ordinal) allocation of `interior_pixel.png` — mirrored by
`com.bubbleshield.interior.InteriorThemes` sprite ordinals:

```
 0..7   taco        20..27 fish (4 frames x2)   48..51 cat
 8..11  donut       28..31 book                 52..57 lava_blob
12..15  duck        32..47 glyph (16)           58..63 reserved (transparent)
16..19  disco_ball
```

Soft-sheet ordinals (4x4 grid of 128px cells, row-major):
`glow_dot star_soft ring shard / streak petal_soft smoke_wisp veil /
spore tendril ripple ribbon / flake light_shaft dome_gradient arc_bolt`.
