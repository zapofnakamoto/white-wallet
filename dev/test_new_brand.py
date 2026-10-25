"""Tests for dev/new_brand.py. Run: python3 -m unittest discover -s dev -p 'test_*.py'"""

import argparse
import random
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

import new_brand as nb


def svg(body: str, view_box: str = "0 0 100 50") -> ET.Element:
    return ET.fromstring(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view_box}">{body}</svg>')


class ApplicationIdTest(unittest.TestCase):
    def test_valid_ids_pass_and_example_ids_warn(self):
        self.assertEqual([], nb.check_application_id("com.acme.cash"))
        self.assertEqual(1, len(nb.check_application_id("com.example.acme")))

    def test_invalid_original_and_keyword_ids_fail(self):
        for bad in ("acme", "com.acme.", "1com.acme", "com.acme-cash.app", "com.example.whitewallet", "com.new.cash"):
            with self.subTest(bad), self.assertRaises(nb.BrandError):
                nb.check_application_id(bad)

    def test_hosts(self):
        for good in ("mint.acme.com", "localhost:8111", "a.b"):
            nb.check_host(good)
        for bad in ("https://mint.acme.com", "mint.acme.com/path", "mint acme", ""):
            with self.subTest(bad), self.assertRaises(nb.BrandError):
                nb.check_host(bad)
        self.assertEqual("http://localhost:8111/.well-known/lnurlp/_", nb.mint_url("localhost:8111"))
        self.assertEqual("https://mint.acme.com/.well-known/lnurlp/_", nb.mint_url("mint.acme.com"))


class PaletteTest(unittest.TestCase):
    def test_button_text_always_reads_on_the_primary_color(self):
        rng = random.Random(7)
        for _ in range(500):
            primary = "#%06X" % rng.randrange(0x1000000)
            light, dark = nb.palette(primary)
            with self.subTest(primary):
                self.assertGreaterEqual(nb.contrast(light["brand_on_primary"], light["brand_primary"]), 4.5)
                self.assertGreaterEqual(nb.contrast(dark["brand_on_primary"], dark["brand_primary"]), 4.5)
                self.assertEqual(light["brand_primary"], light["brand_launcher_background"])

    def test_a_pale_primary_is_flagged(self):
        light, dark = nb.palette("#FFF59D")
        self.assertTrue(any("light theme" in w for w in nb.color_warnings(light, dark)))
        light, dark = nb.palette("#0B6E4F")
        self.assertEqual([], nb.color_warnings(light, dark))

    def test_bad_colors_fail(self):
        with self.assertRaises(nb.BrandError):
            nb.palette("green")


class LogoTest(unittest.TestCase):
    def test_shapes_become_paths_with_their_paint(self):
        logo = nb.convert_svg(svg(
            '<rect width="10" height="10" fill="#f00"/><circle cx="5" cy="5" r="2" style="fill:white;fill-opacity:.5"/>'
            '<path d="M0 0L1 1" fill="none" stroke="black" stroke-width="2"/><polygon points="0,0 1,0 1,1" fill-rule="evenodd"/>'
        ))
        paths = [e for e in logo.children if e.tag == "path"]
        self.assertEqual(4, len(paths))
        self.assertEqual("#FF0000", paths[0].get(nb.a("fillColor")))
        self.assertEqual("0.5", paths[1].get(nb.a("fillAlpha")))
        self.assertIsNone(paths[2].get(nb.a("fillColor")))
        self.assertEqual("2", paths[2].get(nb.a("strokeWidth")))
        self.assertEqual("evenOdd", paths[3].get(nb.a("fillType")))
        self.assertEqual((0, 0, 100, 50), (logo.x, logo.y, logo.width, logo.height))

    def test_group_transforms_become_groups(self):
        logo = nb.convert_svg(svg('<g transform="translate(3 4)" fill="#123456"><path d="M0 0h1v1z"/></g>'))
        group = logo.children[0]
        self.assertEqual(("3", "4"), (group.get(nb.a("translateX")), group.get(nb.a("translateY"))))
        self.assertEqual("#123456", group[0].get(nb.a("fillColor")))

    def test_unsupported_svg_features_fail_with_a_way_out(self):
        for body in ('<text>A</text>', '<path d="M0 0" fill="url(#g)"/>', '<g transform="skewX(10)"><path d="M0 0h1"/></g>',
                     '<g transform="translate(1) scale(2)"><path d="M0 0h1"/></g>'):
            with self.subTest(body), self.assertRaises(nb.BrandError):
                nb.convert_svg(svg(body))

    def test_the_launcher_icon_fits_the_safe_circle(self):
        logo = nb.convert_svg(svg('<rect width="100" height="50"/>'))
        icon = ET.fromstring(nb.launcher_foreground(logo, "#FFFFFF").split("-->", 1)[1])
        group = icon[0]
        scale = float(group.get(nb.a("scaleX")))
        left, top = float(group.get(nb.a("translateX"))), float(group.get(nb.a("translateY")))
        # The logo's corners lie within the 64dp circle around the canvas center.
        for x, y in ((0, 0), (100, 0), (0, 50), (100, 50)):
            dx, dy = left + x * scale - 54, top + y * scale - 54
            self.assertLessEqual((dx * dx + dy * dy) ** 0.5, 32.0001)
        self.assertEqual("#FFFFFF", group[0].get(nb.a("fillColor")))

    def test_a_logo_painted_with_a_color_reference_counts_as_multi_color(self):
        root = ET.fromstring('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:viewportWidth="24" android:viewportHeight="24">'
                             '<path android:pathData="M0 0h24v24z" android:fillColor="#FFFFFF"/><path android:pathData="M1 1h2v2z" android:fillColor="@color/brand_primary"/></vector>')
        self.assertEqual(2, len(nb.logo_paints(nb.convert_vector(root).children)))

    def test_a_vector_drawable_is_taken_as_is(self):
        root = ET.fromstring('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:viewportWidth="24" android:viewportHeight="24">'
                             '<path android:pathData="M0 0h24v24z" android:fillColor="#000000"/></vector>')
        logo = nb.convert_vector(root)
        self.assertEqual((24, 24, 1), (logo.width, logo.height, len(logo.children)))


class BrandDirectoryTest(unittest.TestCase):
    def test_every_language_gets_the_same_brand_terms(self):
        english = [name for name, _ in nb.brand_terms("values")]
        self.assertIn("onboarding_service", english)
        for qualifier in nb.languages():
            with self.subTest(qualifier):
                self.assertEqual(english, [name for name, _ in nb.brand_terms(qualifier)])

    def test_writes_a_complete_brand(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "acme"
            logo = Path(tmp) / "logo.svg"
            logo.write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path d="M0 0h10v10z"/></svg>')
            options = nb.parse_args(["--name", "Acme Cash", "--id", "com.acme.cash", "--mint", "mint.acme.com", "--primary", "#0B6E4F", "--logo", str(logo), "--lock", "--unit", "sats", "--lock-unit",
                                     "--max-accounts", "1", "--max-addresses", "0", "--note-strategy", "manual", "--no-address",
                                     "--collect-at-home", "always", "--collect-fee-cap", "5", "--notes-view", "hidden", "--hide-mints", "--no-strategy-choice", "--developer-options", "unlock",
                                     "--trusted-mint", "mint.example:02" + "ab" * 32])
            nb.write_brand(options, out)
            expected = {"brand.properties", "zapstore.yaml", "res/values/brand_colors.xml", "res/values-night/brand_colors.xml",
                        "res/drawable/ic_launcher_foreground.xml", "res/drawable/ic_brand_logo.xml", "res/mipmap-anydpi/ic_launcher.xml"}
            expected |= {f"res/{q}/strings.xml" for q in nb.languages()}
            self.assertEqual(expected, {str(p.relative_to(out)) for p in out.rglob("*") if p.is_file()})
            props = (out / "brand.properties").read_text()
            for line in ("appName=Acme Cash", "applicationId=com.acme.cash", "allowServiceChange=false", "defaultAmountUnit=sats", "allowUnitChange=false", "supportUrl=https://mint.acme.com",
                         "requireAddress=false", "maxAccounts=1", "maxAddresses=0", "noteStrategies=manual\n", "defaultNoteStrategy=manual",
                         "collectAtHomeMint=always", "collectFeeCap=5", "notesView=hidden", "showMints=false", "developerOptions=unlock",
                         "trustedMints=mint.example:02" + "ab" * 32):
                self.assertIn(line, props)
            for xml in out.rglob("*.xml"):
                ET.parse(xml)  # well-formed
            # The brand-term strings stay commented out, so the defaults apply until the brand rewrites them.
            self.assertEqual([], ET.parse(out / "res/values/strings.xml").getroot().findall("string"))


if __name__ == "__main__":
    unittest.main()
