"""Release evidence must reject accidental duplicates and missing screenshots."""
import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

spec = importlib.util.spec_from_file_location('ui_review', Path(__file__).resolve().parents[1] / 'android/scripts/ui_review.py')
ui_review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui_review)


class UiReviewTest(unittest.TestCase):
    def verify(self, files, required):
        with TemporaryDirectory() as directory:
            target = Path(directory)
            for name, data in files.items():
                path = target / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            ui_review.verify_screenshots(target, set(required))

    def test_identical_final_reading_screens_are_allowed(self):
        files = {'sample-reader-click-pages.png': b'last page',
                 'sample-reader-swipe-pages.png': b'last page', 'settings.png': b'settings'}
        self.verify(files, files)

    def test_other_duplicate_groups_and_third_stale_frame_are_rejected(self):
        for files in ({'first.png': b'stale', 'second.png': b'stale'},
                      {'sample-reader-click-pages.png': b'stale',
                       'sample-reader-swipe-pages.png': b'stale', 'settings.png': b'stale'}):
            with self.subTest(files=list(files)), self.assertRaisesRegex(AssertionError, 'stale or duplicate'):
                self.verify(files, files)

    def test_missing_or_ambiguous_evidence_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'Missing'):
            self.verify({'first.png': b'first'}, {'first.png', 'missing.png'})
        with self.assertRaisesRegex(AssertionError, 'Multiple files'):
            self.verify({'a/first.png': b'first', 'b/first.png': b'other'}, {'first.png'})
