"""Cross-process exclusion for private executor metadata, not business data."""
import os
import time
from contextlib import contextmanager

from . import pathguard


class StateLockError(RuntimeError):
    pass


@contextmanager
def lock_file(path):
    if pathguard.is_linkish(path):
        raise StateLockError("state lock cannot be a link")
    try:
        handle = path.open("a+b")
    except OSError:
        raise StateLockError("state lock unavailable") from None
    with handle:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b"0")
            handle.flush()
        deadline = time.monotonic() + 2
        while True:
            try:
                handle.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise StateLockError("state lock busy") from None
                time.sleep(.02)
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
