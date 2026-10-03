"""Build JTV's text fonts: the existing Anek Latin subset + Anek Devanagari (Devanagari block), merged per weight.

Anek Devanagari is instanced at wght 400/600/700, wdth 100 (static), subset to the Hindi letters of U+0900-097F (+ZWJ/ZWNJ,
dotted circle), and its 'pres' ligatures are dropped: in this font they are only stylistic half-form + consonant
ligatures (क्त → क् + त); conjuncts that need a ligature to avoid a visible virama come from akhn/cjct/rkrf/blwf,
which are kept. That halves the size (≈320 KB → ≈130 KB per weight). The Latin font's metrics and names are kept,
so English text lays out exactly as before.
"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "ftlib"))
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset
from fontTools.merge import Merger, Options as MergeOptions

os.chdir(HERE)
os.makedirs("out", exist_ok=True)
DEVA = list(range(0x0900, 0x0980)) + [0x200C, 0x200D, 0x25CC]
# Hindi only: drop letters used just by Marathi, Sindhi, Kashmiri, Sanskrit/Vedic etc. (they fall back to the system font).
DEVA = [c for c in DEVA if not (0x0972 <= c <= 0x097F) and c not in (
    0x0904, 0x090C, 0x0929, 0x0931, 0x0934, 0x0944, 0x0946, 0x094A, 0x094E, 0x094F,
    0x0951, 0x0952, 0x0953, 0x0954, 0x0955, 0x0956, 0x0957, 0x0960, 0x0961, 0x0962, 0x0963, 0x0971)]
WEIGHTS = {400: "regular", 600: "semibold", 700: "bold"}


def do_subset(font, glyph_names):
    o = subset.Options()
    o.layout_features = ["*"]
    o.hinting = False
    o.notdef_outline = True
    o.name_IDs = []
    o.glyph_names = glyph_names
    s = subset.Subsetter(o)
    s.populate(unicodes=DEVA)
    s.subset(font)


def drop_pres(font):
    g = font["GSUB"].table
    idx = sorted({i for fr in g.FeatureList.FeatureRecord if fr.FeatureTag == "pres" for i in fr.Feature.LookupListIndex})
    for i in idx:
        lk = g.LookupList.Lookup[i]
        for st in lk.SubTable:
            s = st.ExtSubTable if lk.LookupType == 7 else st
            if hasattr(s, "ligatures"):
                s.ligatures = {}


for w, name in WEIGHTS.items():
    st = instancer.instantiateVariableFont(TTFont("AnekDevanagari.ttf"), {"wght": w, "wdth": 100}, updateFontNames=False)
    do_subset(st, glyph_names=True)
    st.save("out/_a.ttf")
    st = TTFont("out/_a.ttf")
    drop_pres(st)
    st.save("out/_b.ttf")
    st = TTFont("out/_b.ttf")
    do_subset(st, glyph_names=True)  # prune glyphs only the dropped ligatures produced
    st.save(f"out/deva_{name}_named.ttf")
    print(name, "deva", len(st.getGlyphOrder()), "glyphs")

    mo = MergeOptions()
    mo.drop_tables = ["STAT", "gasp", "prep"]
    merged = Merger(options=mo).merge([f"jtv_anek_{name}.ttf", f"out/deva_{name}_named.ttf"])
    lat = TTFont(f"jtv_anek_{name}.ttf")
    for attr in ("ascent", "descent", "lineGap"):
        setattr(merged["hhea"], attr, getattr(lat["hhea"], attr))
    for attr in ("sTypoAscender", "sTypoDescender", "sTypoLineGap", "usWinAscent", "usWinDescent", "usWeightClass", "usWidthClass"):
        setattr(merged["OS/2"], attr, getattr(lat["OS/2"], attr))
    merged["name"] = lat["name"]
    merged.save(f"out/named_jtv_anek_{name}.ttf")  # with glyph names, for shaping checks
    merged["post"].formatType = 3.0  # drop glyph names in the shipped file
    merged.save(f"out/jtv_anek_{name}.ttf")
    print(name, "merged", os.path.getsize(f"out/jtv_anek_{name}.ttf"), "latin-only was", os.path.getsize(f"jtv_anek_{name}.ttf"))
