"""C13: model-provided edge titles survive both SVG and editable exports."""
import json
import importlib.util
from argparse import Namespace
import os
import tempfile
import unittest
import xml.etree.ElementTree as ET

from test_cli import _EXAMPLES, _CLI, run_cli

spec = importlib.util.spec_from_file_location("litviz_cli", _CLI)
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


class RelationLabelsTest(unittest.TestCase):
    def test_edge_title_alias_survives_svg_and_drawio_without_changing_source(self):
        with tempfile.TemporaryDirectory() as directory:
            with open(os.path.join(_EXAMPLES, 'relationship.json'), encoding='utf-8') as f:
                semantic_map = json.load(f)
            semantic_map['nodes'][0].pop('note')
            semantic_map['nodes'][0]['subtitle'] = '原告与债权人'
            semantic_map['edges'] = [
                {'id': 'contract', 'from': 'jia', 'to': 'yi', 'title': '签订购销合同', 'accent': True},
                {'from': 'yi', 'to': 'bing', 'title': '不应覆盖', 'label': '追偿权'},
                {'from': 'jia', 'to': 'bing', 'title': '明确留空', 'label': ''},
            ]
            path = os.path.join(directory, 'source.json')
            original = json.dumps(semantic_map, ensure_ascii=False)
            with open(path, 'w', encoding='utf-8') as f:
                f.write(original)
            result, code, stderr = run_cli(['render', '--map', path,
                                           '--out', os.path.join(directory, 'diagram'),
                                           '--formats', 'svg,drawio'])
            self.assertEqual(code, 0, stderr)
            files = {item['format']: item['path'] for item in result['files']}
            svg = ET.parse(files['svg']).getroot()
            text = ''.join(svg.itertext())
            self.assertIn('签订购销合同', text)
            self.assertIn('原告与债权人', text)
            self.assertIn('追偿权', text)
            self.assertNotIn('不应覆盖', text)
            self.assertNotIn('明确留空', text)
            drawing = ET.parse(files['drawio'])
            self.assertIn('原告与债权人', ''.join(c.get('value', '') for c in drawing.findall('.//mxCell')))
            cells = drawing.findall('.//mxCell[@edge="1"]')
            self.assertEqual([cell.get('value') for cell in cells], ['签订购销合同', '追偿权', ''])
            with open(path, encoding='utf-8') as f:
                self.assertEqual(f.read(), original)

    def test_canonical_fields_win_and_aliases_do_not_change_source_bytes(self):
        semantic_map = {
            'layout': 'graphviz_relation',
            'nodes': [{'id': 'a', 'title': '甲', 'note': '保留注释', 'subtitle': '旧注释'}],
            'edges': [
                {'id': 'old', 'title': '旧标签', 'label': '', 'accent': True, 'emphasis': False},
                {'title': '标签', 'accent': False},
                {'accent': 'red'},  # Unknown values remain invalid rather than guessing emphasis.
            ]
        }
        with tempfile.TemporaryDirectory() as directory:
            path = os.path.join(directory, 'source.json')
            original = json.dumps(semantic_map, ensure_ascii=False)
            with open(path, 'w', encoding='utf-8') as f:
                f.write(original)
            args = Namespace(map=path)
            with cli._canonical_relation_map(args):
                with open(args.map, encoding='utf-8') as f:
                    canonical = json.load(f)
                self.assertEqual(canonical['nodes'][0], {'id': 'a', 'title': '甲', 'note': '保留注释'})
                self.assertEqual(canonical['edges'][0], {'label': '', 'emphasis': False})
                self.assertEqual(canonical['edges'][1], {'label': '标签', 'emphasis': False})
                self.assertEqual(canonical['edges'][2], {'accent': 'red'})
            self.assertEqual(args.map, path)
            with open(path, encoding='utf-8') as f:
                self.assertEqual(f.read(), original)


if __name__ == '__main__':
    unittest.main()
