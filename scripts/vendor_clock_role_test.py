#!/usr/bin/env python3
import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'common'))
from font_role_shadow import _classification, ROLE_REVISION
from font_role_policy import is_clock_slot, is_code_monospace


class VendorClockTest(unittest.TestCase):
    def test_named_vendor_clocks(self):
        for name in ('RedMagicDigit.ttf', 'RedMagicDigit-Regular.ttf', 'ZDigit.ttf', 'ZDigitVF.ttf'):
            classified = _classification('/system/fonts/'+name, {'families': ['sans-serif']})
            self.assertEqual((classified['role'], classified['action']), ('clock','preserve'))
            self.assertTrue(is_clock_slot(name))

    def test_other_digits_and_monospace_keep_existing_roles(self):
        self.assertGreaterEqual(ROLE_REVISION, 3)
        self.assertEqual(_classification('/system/fonts/OrdinaryDigit.ttf', {'families': ['sans-serif']})['role'], 'ui-sans')
        self.assertTrue(is_code_monospace('RobotoMono.ttf'))
        data = {'families':['sans-serif'], 'slotName':'OSans-Solid-Digits.ttf'}
        self.assertEqual(_classification('/system/fonts/OSans-Solid-Digits.ttf', data, coloros=True)['role'], 'numeric')


if __name__ == '__main__': unittest.main()
