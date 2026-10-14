"""Where Mo keeps its data.

By default everything lives next to the code (the repo layout). Set
MO_DATA_ROOT to keep card images and data somewhere else, for example in the
Android app's own storage, where the code folder is read-only.
"""

import os
from pathlib import Path

CODE_ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = Path(os.environ.get('MO_DATA_ROOT') or CODE_ROOT)
APP_DATA = DATA_ROOT / 'app' / 'data'
