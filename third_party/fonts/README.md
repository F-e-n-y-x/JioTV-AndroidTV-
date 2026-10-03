# Bundled fonts

`android/app/src/main/res/font/jtv_anek_*.ttf` are built from two typefaces of the Anek family
(https://github.com/EkType/Anek), both Copyright 2021 The Anek Project Authors and licensed under the
SIL Open Font License 1.1:

- **Anek Latin** (`AnekLatin-OFL.txt`): static instances, subset to Latin plus punctuation.
- **Anek Devanagari** (`AnekDevanagari-OFL.txt`, from google/fonts `ofl/anekdevanagari`): static instances at
  weights 400/600/700 (width 100), subset to the Hindi letters of the Devanagari block, with the stylistic
  `pres` ligatures removed to keep the size down. Conjuncts are still formed (akhn/cjct/rkrf/blwf/half).

`jtv_anek_regular`, `jtv_anek_semibold` and `jtv_anek_bold` each contain the Latin and the Devanagari glyphs
of one weight, merged into one file, so Hindi text uses the same family as English on every Android version
(Compose picks fonts per weight, not per character). They keep the Latin font's metrics and names, so English
layout is unchanged. `jtv_anek_wide_bold` is Latin only (numbers).

`build_devanagari.py` rebuilds the three merged files with fontTools from `AnekDevanagari[wdth,wght].ttf` and
the Latin-only subsets. The subset and merged files are modified versions and keep the original licence.
