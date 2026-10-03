#!/usr/bin/env python3
"""Capture the signed v2 App, including real flashing screens; no root or fake tasks."""
import time
import android_ui_smoke as base


class FlashSmokeRun(base.SmokeRun):
    def tap_label(self, label):
        root = self.hierarchy()
        found = [n for n in root.iter('node')
                 if n.get('package') == self.package and label in base.labels(n)]
        if not found:
            raise RuntimeError('Missing visible control: ' + label)
        x, y = base.center(found[0])
        self.adb('shell', 'input', 'tap', str(x), str(y))

    def wait_text(self, text):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            self.assert_running()
            root = self.hierarchy()
            labels = {s for n in root.iter('node') if n.get('package') == self.package for s in base.labels(n)}
            if any(text in s for s in labels):
                return root
            time.sleep(.4)
        raise RuntimeError('Screen did not show: ' + text)

    def run(self):
        super().run()
        root = self.hierarchy()
        x, y = base.center(base.tab_target(root, '首页', self.package))
        self.adb('shell', 'input', 'tap', str(x), str(y))
        root = self.wait_page('首页', '当前字体')
        home_labels = {s for n in root.iter('node') for s in base.labels(n)}
        if any('全局粗细微调' in s for s in home_labels) and not any('恢复原始' in s for s in home_labels):
            raise RuntimeError('Global weight control is visible without its restore action')
        self.tap_label('任务中心')
        self.wait_text('字体刷写')
        for theme, mode in [('light', 'no'), ('dark', 'yes')]:
            self.adb('shell', 'cmd', 'uimode', 'night', mode)
            time.sleep(1.5)
            self.wait_text('字体刷写')
            for tab, marker in [('刷写', '最近任务'), ('问题', '条警告'), ('日志', '搜索任务')]:
                self.tap_label(tab)
                root = self.wait_text(marker)
                time.sleep(.7)
                self.capture('flash-' + theme + '-' + {'刷写': 'tasks', '问题': 'issues', '日志': 'logs'}[tab], root)
        self.assert_running()


if __name__ == '__main__':
    base.SmokeRun = FlashSmokeRun
    raise SystemExit(base.main())
