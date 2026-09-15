#!/usr/bin/env python3
"""Verify a LOWA big-doc export without opening or modifying user files.
Usage: python3 verify-big-export.py /tmp/exported-300.docx --pages 300
"""
import argparse
import json
from zipfile import ZipFile
from xml.etree import ElementTree as ET

ap = argparse.ArgumentParser()
ap.add_argument('file')
ap.add_argument('--pages', type=int, default=150)
args = ap.parse_args()
ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
with ZipFile(args.file) as archive:
    root = ET.fromstring(archive.read('word/document.xml'))
text = ''.join(node.text or '' for node in root.findall('.//w:t', ns))
assert '第1节 核查事项' in text and f'第{args.pages}节 核查事项' in text, 'first/last section missing'
assert text.count('目标公司') == 0, 'visible old text remains'
assert text.count('标的公司') == args.pages, 'replacement count mismatch'
for tag, count in [('tbl', min(30, args.pages)), ('drawing', min(20, args.pages)),
                   ('del', args.pages), ('ins', args.pages)]:
    assert len(root.findall(f'.//w:{tag}', ns)) == count, f'{tag} count mismatch'
body_paragraphs = [p for p in root.findall('w:body/w:p', ns)
                   if p.find('w:pPr/w:pStyle', ns) is not None
                   and p.find('w:pPr/w:pStyle', ns).get('{'+ns['w']+'}val') == 'Normal'
                   and ''.join(t.text or '' for t in p.findall('.//w:t', ns)).strip()]
samples = [body_paragraphs[0], body_paragraphs[len(body_paragraphs)//2], body_paragraphs[-1]]
assert len({ET.tostring(p.find('w:pPr', ns)) for p in samples}) == 1, 'body formatting differs at start/middle/end'
print(json.dumps({'pagesFixture': args.pages, 'textChars': len(text), 'replacements': args.pages,
                  'tables': min(30, args.pages), 'images': min(20, args.pages),
                  'trackedChangesPreserved': True, 'sampledBodyFormattingConsistent': True, 'firstAndLastSectionPresent': True}))
