#!/usr/bin/env python3
"""Safety regression for explicit vendor clocks and restrictive user slot rules."""
import base64
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'common'))
import font_slot_overrides as policy
import font_role_shadow as roles
import font_role_policy as staged


def rules(**fields):
    return policy.validate({'schema': policy.SCHEMA, 'revision': 1, **fields})


def topology():
    return {'schema': 'device-font-topology-v1', 'state': 'ready', 'buildKey': 'test', 'romKind': 'generic',
            'slots': {f'/system/fonts/{name}.ttf': {'families': family, 'metrics': {'coverage': {} if name == 'Mystery' else {'hasLatin': True, 'hasDigits': True}}}
                      for name, family in [('Text', ['sans-serif']), ('Other', ['sans-serif']), ('Emoji', ['emoji']), ('Mystery', [])]}}


class SlotPolicyTest(unittest.TestCase):
    def test_vendor_clocks_are_exact_and_protected(self):
        for name in ('RedMagicDigit.ttf', 'RedMagicDigit-Regular.ttf', 'ZDigit.ttf', 'ZDigitVF.ttf', 'ZDigitVF-Bold.otf'):
            info = roles._classification('/system/fonts/'+name, {'families': ['sans-serif']})
            self.assertEqual((info['role'], info['action']), ('clock', 'preserve'), name)
            self.assertTrue(staged.is_clock_slot(name))
        for name in ('Digit.ttf', 'Digits-Regular.ttf', 'MyZDigit.ttf', 'RedMagicDigitSomething.ttf'):
            self.assertFalse(policy.VENDOR_CLOCK.fullmatch(name))
        self.assertEqual(roles.ROLE_REVISION, 3)
        self.assertEqual(roles._classification('/system/fonts/RobotoMono.ttf', {})['role'], 'monospace')

    def test_whitelist_cannot_unlock_protected_or_unknown(self):
        data = topology()
        allowed = list(data['slots'])
        _, plan = roles.build(data, slot_overrides=rules(includeOnly=allowed, excludePaths=['/system/fonts/Other.ttf']))
        self.assertEqual(plan['slots']['/system/fonts/Text.ttf']['action'], 'replace')
        self.assertEqual(plan['slots']['/system/fonts/Other.ttf']['action'], 'preserve')
        self.assertEqual(plan['slots']['/system/fonts/Emoji.ttf']['action'], 'preserve')
        self.assertEqual(plan['slots']['/system/fonts/Mystery.ttf']['action'], 'review')

    def test_prefix_boundary_and_alias_normalization(self):
        value = rules(excludePathPrefixes=['/product/fonts/vendor'])
        self.assertTrue(policy.exclusion('/system/product/fonts/vendor/A.ttf', value))
        self.assertFalse(policy.exclusion('/product/fonts/vendor2/A.ttf', value))
        self.assertEqual(policy.logical_path('/system/product/fonts/A.ttf'), '/product/fonts/A.ttf')

    def test_bad_rules_fail_closed(self):
        for path in ('../A.ttf', '/system/fonts/../A.ttf', '/etc/passwd', '/system/fonts/a\nb.ttf'):
            with self.assertRaises(ValueError): rules(includeOnly=[path])
        with self.assertRaises(ValueError): policy.validate({'schema': policy.SCHEMA, 'revision': 99})

    def test_effective_identity_ignores_order_and_duplicates(self):
        a, b = '/system/fonts/A.ttf', '/system/fonts/B.ttf'
        self.assertEqual(policy.digest(rules(includeOnly=[a,b,a])), policy.digest(rules(includeOnly=[b,a])))
        self.assertNotEqual(policy.digest(rules()), policy.digest(rules(excludePaths=[a])))

    def test_preview_save_backup_and_corrupt_repair(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); config = root/'config'; config.mkdir()
            path = config/'font-slot-overrides.json'
            data = topology()
            (config/'device_font_topology.json').write_text(json.dumps(data))
            encoded = base64.b64encode(json.dumps(rules(excludePaths=['/system/fonts/Text.ttf'])).encode()).decode()
            preview = policy.command(root, 'preview', encoded)
            self.assertFalse(path.exists())
            with self.assertRaises(ValueError): policy.command(root, 'save', encoded, 'wrong-token')
            policy.command(root, 'save', encoded, preview['token'])
            self.assertEqual(policy.load(path)['excludePaths'], ['/system/fonts/Text.ttf'])
            self.assertEqual(policy.load(config/'font-slot-overrides.previous.json'), rules())
            path.write_text('broken')
            with self.assertRaises(ValueError): policy.load(path)
            preview = policy.command(root, 'preview', encoded)
            policy.command(root, 'save', encoded, preview['token'])
            self.assertEqual(policy.load(config/'font-slot-overrides.previous.json'), rules())

    def test_topology_change_invalidates_review_token(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root/'config').mkdir()
            path = root/'config/device_font_topology.json'; data = topology(); path.write_text(json.dumps(data))
            encoded = base64.b64encode(json.dumps(rules()).encode()).decode()
            token = policy.command(root, 'preview', encoded)['token']
            data['buildKey'] = 'other-ROM'; path.write_text(json.dumps(data))
            with self.assertRaises(ValueError): policy.command(root, 'save', encoded, token)

    def test_staged_exclusions_do_not_touch_live_font(self):
        with tempfile.TemporaryDirectory() as temp:
            module = Path(temp); (module/'config').mkdir(); stage = module/'staging'; (stage/'system/fonts').mkdir(parents=True)
            inventory = topology(); (module/'config/device_font_inventory.json').write_text(json.dumps(inventory))
            policy.write(module/'config/font-slot-overrides.json', rules(excludePaths=['/system/fonts/Other.ttf']))
            for name in ('Text', 'Other', 'Emoji', 'Mystery', 'ZDigitVF'):
                (stage/f'system/fonts/{name}.ttf').write_bytes(b'font')
            live = module/'.ziyu-payload'; (live/'system/fonts').mkdir(parents=True)
            (live/'system/fonts/Other.ttf').write_bytes(b'LIVE')
            result = staged.cleanup(module, stage)
            self.assertTrue((stage/'system/fonts/Text.ttf').exists())
            for name in ('Other','Emoji','Mystery','ZDigitVF'):
                self.assertFalse((stage/f'system/fonts/{name}.ttf').exists())
            self.assertEqual((live/'system/fonts/Other.ttf').read_bytes(), b'LIVE')
            self.assertIn('/system/fonts/Other.ttf', result['preservedStockPaths'])


if __name__ == '__main__':
    unittest.main()
