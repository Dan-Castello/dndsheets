# -*- coding: utf-8 -*-
"""Repairs character sheets that grew by re-encoding.

Before the fix, SheetLoader read sheets with the platform charset (windows-1252 on Windows) and wrote them back as UTF-8, so a
string with an accent ("Daño") became mojibake and tripled in size on every load: three sheets reached 229 MB each.

    python -X utf8 tools/repair_sheet_mojibake.py <sheet.json> <repaired.json>

Each long string value that still looks like mojibake is decoded back (utf-8 bytes read as cp1252) as many times as it takes;
if that is not possible (the damage lost bytes) the value is replaced by a short fallback ("Damage"). The input is never
modified. Pretty-printed sheets keep one string per line, which is what lets this stream the file.
"""
import io
import re
import sys

STRING_LINE = re.compile(r'^(\s*"[A-Za-z]+": ")(.*)(",?)\s*$')
MARKERS = ('Ã', 'Â', 'â€')   # "Ã", "Â", "â€"
BACKSLASH = chr(92)
QUOTE = chr(34)


def unmangle(text):
    for _ in range(40):
        if not any(marker in text for marker in MARKERS):
            return text
        try:
            text = text.encode('cp1252').decode('utf-8')
        except (UnicodeEncodeError, UnicodeDecodeError):
            return None
    return None


def escape(text):
    return text.replace(BACKSLASH, BACKSLASH + BACKSLASH).replace(QUOTE, BACKSLASH + QUOTE)


def main(src, dst):
    fixed = replaced = 0
    with io.open(src, encoding='utf-8', errors='replace') as reader, io.open(dst, 'w', encoding='utf-8', newline='\n') as writer:
        for line in reader:
            match = STRING_LINE.match(line)
            if match and any(marker in match.group(2) for marker in MARKERS):
                repaired = unmangle(match.group(2))
                if repaired is None:
                    repaired = 'Damage'
                    replaced += 1
                else:
                    fixed += 1
                line = match.group(1) + escape(repaired) + match.group(3) + chr(10)
            writer.write(line)
    print('decoded %d value(s), replaced %d with a fallback' % (fixed, replaced))


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
