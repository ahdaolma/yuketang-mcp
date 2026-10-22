"""Validate collected screenshot evidence without assuming every state looks different."""
import hashlib


def verify_screenshots(target, required):
    screenshots = [path for path in target.rglob('*.png') if path.name in required]
    found = {path.name for path in screenshots}
    assert required <= found, f'Missing UI review screenshots: {required - found}'
    assert len(screenshots) == len(required), 'Multiple files for a required screenshot'
    groups = {}
    for path in screenshots:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        groups.setdefault(digest, set()).add(path.name)
    # Both tests finish on the same final screen with the tools closed. Reading
    # mode changes its gestures, not its visible chrome. They may legitimately
    # be identical when the app clock also lands in the same minute.
    equivalent = {frozenset({'sample-reader-click-pages.png', 'sample-reader-swipe-pages.png'})}
    unexpected = [sorted(names) for names in groups.values()
                  if len(names) > 1 and frozenset(names) not in equivalent]
    assert not unexpected, f'UI screenshots captured stale or duplicate frames: {unexpected}'
