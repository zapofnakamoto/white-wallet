"""Tests for dev/screenshots.py. Run: python3 -m unittest discover -s dev -p 'test_*.py'"""

import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import screenshots as sc


def image(width: int, height: int, color: tuple[int, int, int, int]) -> bytes:
    return sc.write_png(width, height, [bytes(color) * width for _ in range(height)])


class PngTest(unittest.TestCase):
    def test_what_is_written_reads_back(self):
        rows = [bytes((x, y, 7, 255)) for y in range(3) for x in range(4)]
        data = sc.write_png(4, 3, [b"".join(rows[y * 4 : (y + 1) * 4]) for y in range(3)])
        width, height, read = sc.read_png(data)
        self.assertEqual((4, 3), (width, height))
        self.assertEqual(bytes((2, 1, 7, 255)), read[1][8:12])


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.out = Path(tempfile.mkdtemp()) / "acme"
        (self.out / "current").mkdir(parents=True)

    def put(self, folder: str, name: str, data: bytes):
        (self.out / folder).mkdir(exist_ok=True)
        (self.out / folder / name).write_bytes(data)

    def report(self) -> str:
        out = io.StringIO()
        with redirect_stdout(out):
            sc.main([str(self.out)])
        return out.getvalue()

    def test_without_an_earlier_render_it_says_so(self):
        self.put("current", "ScanDoneFound.light.3000ms.png", image(2, 2, (0, 0, 0, 255)))
        self.assertIn("no earlier render to compare with", self.report())
        self.assertIn("ScanDoneFound.light.3000ms.png", (self.out / "index.html").read_text())

    def test_the_same_images_changed_nothing(self):
        for folder in ("previous", "current"):
            self.put(folder, "A.light.3000ms.png", image(2, 2, (0, 0, 0, 255)))
        self.assertIn("0 changed, 0 new, 0 removed", self.report())
        self.assertEqual("", (self.out / "changed.txt").read_text())

    def test_a_changed_image_is_listed_with_a_picture_of_what_differs(self):
        self.put("previous", "A.light.3000ms.png", image(2, 2, (0, 0, 0, 255)))
        self.put("current", "A.light.3000ms.png", image(2, 2, (255, 255, 255, 255)))
        self.put("current", "B.light.3000ms.png", image(2, 2, (0, 0, 0, 255)))
        self.put("previous", "C.light.3000ms.png", image(2, 2, (0, 0, 0, 255)))
        self.assertIn("1 changed, 1 new, 1 removed", self.report())
        self.assertEqual("A.light.3000ms.png\n", (self.out / "changed.txt").read_text())
        _, _, diff = sc.read_png((self.out / "diff" / "A.light.3000ms.png").read_bytes())
        self.assertEqual(bytes(sc.CHANGED), diff[0][:4])

    def test_the_same_pixels_encoded_otherwise_are_unchanged(self):
        # Fedora and Ubuntu render the same pixels but compress them differently.
        rows = [bytes((x, 0, 0, 255)) * 3 for x in range(3)]
        self.put("previous", "A.light.3000ms.png", sc.write_png(3, 3, rows))
        self.put("current", "A.light.3000ms.png", sc.write_png(3, 3, rows, level=1))
        self.assertNotEqual((self.out / "previous" / "A.light.3000ms.png").read_bytes(), (self.out / "current" / "A.light.3000ms.png").read_bytes())
        self.assertIn("0 changed", self.report())

    def test_images_of_different_sizes_count_as_changed(self):
        self.put("previous", "A.light.3000ms.png", image(2, 2, (0, 0, 0, 255)))
        self.put("current", "A.light.3000ms.png", image(2, 3, (0, 0, 0, 255)))
        self.assertIn("1 changed", self.report())

    def test_the_page_groups_images_by_screen(self):
        for name in ("A.light.3000ms.png", "A.dark.3000ms.png", "B.light.3000ms.png"):
            self.put("current", name, image(1, 1, (0, 0, 0, 255)))
        self.report()
        page = (self.out / "index.html").read_text()
        self.assertIn("<h2>A</h2>", page)
        self.assertIn("acme", page)
        self.assertLess(page.index("A.dark.3000ms.png"), page.index("<h2>B</h2>"))


if __name__ == "__main__":
    unittest.main()
