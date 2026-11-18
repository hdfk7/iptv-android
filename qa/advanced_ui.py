"""Regress advanced settings and expose unreachable TV confirmation controls."""

import sys

# Set this before importing QA modules, including when run without -B.
sys.dont_write_bytecode = True

import argparse
import json
from release_ui import find, QA_OUTPUT
from upgrade_seed import close_panels, type_in_field, close_keyboard, add_device_arguments, prepare_release_device

def main():
    parser = argparse.ArgumentParser()
    add_device_arguments(parser)
    args = parser.parse_args()
    d, entry, checksum = prepare_release_device(args.serial, args.device)
    tv = args.device == 'tv'
    down = 'KEYCODE_DPAD_DOWN'
    up = 'KEYCODE_DPAD_UP'
    left = 'KEYCODE_DPAD_LEFT'
    right = 'KEYCODE_DPAD_RIGHT'
    ok = 'KEYCODE_DPAD_CENTER'
    back = 'KEYCODE_BACK'
    results = []
    failure = None

    def step(name):
        results.append(name)
        d.screenshot(args.device, 'functional-' + name)
        print(name + ' passed', flush=True)

    def menu_settings():
        close_panels(d)
        d.more()
        d.menu('设置')

    def advanced():
        menu_settings()
        d.menu('高级设置')
        d.wait_for('播放超时后自动换线')

    def edit_timeout(value):
        if tv:
            d.key(down, down, ok)
        else:
            d.click('播放超时后自动换线')
        d.wait_for('确定')
        type_in_field(d, value)
        close_keyboard(d)
        if tv:
            d.key(down, right, ok)
        else:
            d.click('确定')

    def save_advanced():
        if tv:
            d.key(down, down, down, ok)
        else:
            d.click('保存')
        d.wait_for('检查应用更新')
    try:
        advanced()
        edit_timeout('0')
        root = d.tree()
        if find(root, '请输入有效的正数，最多保留三位小数') is None:
            d.screenshot(args.device, 'advanced-confirm-unreachable')
            raise AssertionError('Timeout confirmation did not run; inspect remote focus after the keyboard closes')
        step('reject-zero-timeout')
        if tv:
            d.key(left, ok)
        else:
            d.click('取消')
        d.wait_for('播放超时后自动换线')
        if tv:
            d.key(ok)
        else:
            d.click('播放超时后自动换线')
        d.wait_for('确定')
        type_in_field(d, '17')
        close_keyboard(d)
        if tv:
            d.key(down, right, ok)
        else:
            d.click('确定')
        d.wait_for('17')
        save_advanced()
        advanced()
        d.wait_for('17')
        step('advanced-save-reopen')
        if tv:
            d.key(down, down, down, down, down, left, left, ok)
        else:
            d.click('恢复默认值')
        d.wait_for('15')
        if tv:
            d.key(right, right, ok)
        else:
            d.click('保存')
        d.wait_for('检查应用更新')
        step('advanced-defaults')
        close_panels(d)
    except Exception as error:
        failure = repr(error)
        raise
    finally:
        target = QA_OUTPUT / 'functional'
        target.mkdir(parents=True, exist_ok=True)
        (target / (args.device + '-advanced.json')).write_text(json.dumps({'sha256': checksum, 'passedSteps': results, 'failure': failure, 'passed': failure is None}, ensure_ascii=False, indent=2), encoding='utf-8')
        close_panels(d)
if __name__ == '__main__':
    main()
