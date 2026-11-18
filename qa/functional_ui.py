"""Regress playback, favorites, settings and recent history using the installed App UI."""

import sys

# Set this before importing QA modules, including when run without -B.
sys.dont_write_bytecode = True

import argparse
import json
import time
from release_ui import find, tv_highlight, QA_OUTPUT
from upgrade_seed import close_panels, source_action, open_channels, select_channel, add_device_arguments, prepare_release_device
from controlled_ui import verify_moving_picture


def settings_value(root, label):
    if label == "启动频道":
        values = [value for value in ("上次观看", "列表首个") if find(root, value) is not None]
        assert len(values) == 1, "Startup setting value is missing or ambiguous"
        return values[0]
    rows = [node for node in root.iter("node")
            if node.get("checkable") == "true" and find(node, label) is not None]
    assert len(rows) == 1, f"Switch state is missing or ambiguous: {label}"
    return rows[0].get("checked") == "true"


def main():
    p = argparse.ArgumentParser()
    add_device_arguments(p)
    p.add_argument('--source', help="Fixture source; defaults to this run's proxy-guide checkpoint")
    a = p.parse_args()
    source = a.source
    if source is None:
        checkpoint = QA_OUTPUT / 'controlled' / (a.device + '-proxy-source.json')
        if not checkpoint.is_file():
            p.error('prepare the proxy-guide fixture or pass the name of an existing fixture with --source')
        prepared = json.loads(checkpoint.read_text(encoding='utf-8'))
        assert prepared['serial'] == a.serial, 'Source checkpoint belongs to another device'
        source = prepared['name']
    d, _, checksum = prepare_release_device(a.serial, a.device)
    tv = a.device == 'tv'
    down = 'KEYCODE_DPAD_DOWN'
    up = 'KEYCODE_DPAD_UP'
    right = 'KEYCODE_DPAD_RIGHT'
    left = 'KEYCODE_DPAD_LEFT'
    ok = 'KEYCODE_DPAD_CENTER'
    back = 'KEYCODE_BACK'
    results = []
    failure = None

    def mark(label):
        d.screenshot(a.device, 'functional-' + label)
        results.append(label)
        print(label + ' passed', flush=True)

    def controls_action(labels):
        if tv:
            root = d.tree()
            if not any((find(root, x, 'content-desc') is not None or find(root, x) is not None for x in labels)):
                d.key('KEYCODE_INFO')
                root = d.tree()
            label = next((x for x in labels if find(root, x, 'content-desc') is not None or find(root, x) is not None), None)
            assert label, labels
            current = tv_highlight(d)
            positions = {'频道': 0, '暂停': 1, '播放': 1, '收藏节目': 2, '取消收藏': 2, '更多': 3}
            target = positions[label]
            start = next((positions[x] for x in current if x in positions), 0)
            d.choose_with_remote(label, left if start > target else right, limit=5)
        else:
            root = d.tree()
            found = next(((x, find(root, x, 'content-desc')) for x in labels if find(root, x, 'content-desc') is not None), None)
            if found is None:
                d.player_control('更多')
                root = d.tree()
                found = next(((x, find(root, x, 'content-desc')) for x in labels if find(root, x, 'content-desc') is not None), None)
            assert found, labels
            label, node = found
            d.tap(node)
        return label

    def menu(label):
        close_panels(d)
        d.more()
        d.menu(label)
    try:
        source_action(d, source)
        open_channels(d)
        select_channel(d, 'QA History')
        time.sleep(3)
        verify_moving_picture(d)
        open_channels(d)
        select_channel(d, 'QA Release')
        time.sleep(3)
        verify_moving_picture(d)
        close_panels(d)
        mark('channel-change-and-play')
        original = controls_action(('收藏节目', '取消收藏'))
        opposite = '取消收藏' if original == '收藏节目' else '收藏节目'
        d.wait_for_player_control(opposite)
        mark('favorite-toggle')
        controls_action((opposite,))
        d.wait_for_player_control(original)
        mark('favorite-restored')
        if tv:
            d.key(back)
        first = controls_action(('暂停', '播放'))
        second = '播放' if first == '暂停' else '暂停'
        d.wait_for_player_control(second)
        controls_action((second,))
        d.wait_for_player_control(first)
        if tv:
            d.key(back)
        mark('pause-resume')
        menu('重连当前线路')
        time.sleep(3)
        close_panels(d)
        verify_moving_picture(d)
        mark('reconnect')
        menu('设置')
        for label in ('启动频道', '视频铺满屏幕'):
            root = d.tree()
            original_value = settings_value(root, label)
            if tv:
                d.choose_with_remote(label)
                assert settings_value(d.tree(), label) != original_value
                mark('setting-' + label + '-changed')
                d.key(ok)
            else:
                d.click(label)
                assert settings_value(d.tree(), label) != original_value
                mark('setting-' + label + '-changed')
                d.click(label)
            assert settings_value(d.tree(), label) == original_value
        close_panels(d)
        mark('settings-roundtrip')
        open_channels(d)
        if tv:
            d.key(left)
            d.choose_with_remote('最近', right, limit=5)
        else:
            d.click('最近')
        root = d.tree()
        assert find(root, 'QA Release') is not None and find(root, 'QA History') is not None
        mark('recent-history')
        close_panels(d)
    except Exception as error:
        failure = repr(error)
        raise
    finally:
        target = QA_OUTPUT / 'functional'
        target.mkdir(exist_ok=True)
        (target / (a.device + '-playback.json')).write_text(json.dumps(
            {"sha256": checksum, "passedSteps": results, "failure": failure, "passed": failure is None},
            ensure_ascii=False, indent=2), encoding='utf-8')
if __name__ == '__main__':
    main()
