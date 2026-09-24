#!/usr/bin/env python3
"""Generate native translations from a shared, placeholder-normalized dictionary."""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
LANGUAGES = json.loads((ROOT / 'localization/languages.json').read_text())
FORMAT = re.compile(r'%(?:(\d+)\$)?(?:l{1,2})?[@dsuf]')


def normalize(value):
    arguments = {}
    counter = 0
    def replace(match):
        nonlocal counter
        index = int(match[1]) - 1 if match[1] else counter
        counter += 1
        arguments[index] = match[0]
        return '{' + str(index) + '}'
    return FORMAT.sub(replace, value), arguments


def properties(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        key, value = line.split('=', 1)
        result[key] = value.replace(r'\n', '\n').replace(r'\:', ':').replace(r'\=', '=').replace(r'\\', '\\')
    return result


def android_text(element):
    return ''.join(element.itertext()).replace(r"\'", "'").replace(r'\"', '"').replace(r'\n', '\n')


def sources():
    for path in sorted((ROOT / 'apple').rglob('*.xcstrings')):
        if '.build' in path.parts:
            continue
        data = json.loads(path.read_text())
        for key, entry in data['strings'].items():
            unit = entry.get('localizations', {}).get('en', {}).get('stringUnit')
            if unit:
                yield path, key, unit['value']
    for module in ['app', 'wear', 'recording']:
        path = ROOT / f'android/{module}/src/main/res/values/strings.xml'
        for element in ET.parse(path).getroot():
            if element.tag == 'string':
                yield path, element.attrib['name'], android_text(element)
            elif element.tag == 'plurals':
                for item in element:
                    yield path, element.attrib['name'] + '.' + item.attrib['quantity'], android_text(item)
    path = ROOT / 'windows/app/src/main/resources/i18n/strings_en.properties'
    for key, value in properties(path).items():
        yield path, key, value


def inventory():
    return sorted({normalize(value)[0] for _, _, value in sources()})


def translated(value, table):
    key, arguments = normalize(value)
    result = table[key]
    expected = set(re.findall(r'\{\d+\}', key))
    actual = set(re.findall(r'\{\d+\}', result))
    if expected != actual or ('${applicationName}' in value) != ('${applicationName}' in result):
        raise ValueError(f'Format arguments differ: {key!r} -> {result!r}')
    return re.sub(r'\{(\d+)\}', lambda m: arguments[int(m[1])], result)


def generate(check):
    expected = set(inventory())
    unchanged = set(json.loads((ROOT / 'localization/unchanged.json').read_text()))
    errors = []
    count = 0
    def write(path, content):
        nonlocal count
        count += 1
        if check:
            if not path.exists() or path.read_text() != content:
                errors.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    tables = {}
    for language in LANGUAGES[2:]:
        tag = language['tag']
        table = json.loads((ROOT / f'localization/translations/{tag}.json').read_text())
        table.update({key: key for key in unchanged})
        if set(table) != expected:
            raise ValueError(f'{tag}: missing={expected-set(table)}, extra={set(table)-expected}')
        tables[tag] = table
    for path in sorted({path for path, _, _ in sources() if path.suffix == '.xcstrings'}):
        data = json.loads(path.read_text())
        for entry in data['strings'].values():
            unit = entry.get('localizations', {}).get('en', {}).get('stringUnit')
            if not unit:
                continue
            for tag, table in tables.items():
                entry['localizations'][tag] = {'stringUnit': {'state': 'translated', 'value': translated(unit['value'], table)}}
        write(path, json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    for module in ['app', 'wear', 'recording']:
        base = ROOT / f'android/{module}/src/main/res/values/strings.xml'
        for language in LANGUAGES[2:]:
            table = tables[language['tag']]
            root = ET.parse(base).getroot()
            for element in root:
                units = list(element) if element.tag == 'plurals' else [element]
                for unit in units:
                    unit.text = translated(android_text(unit), table).replace('\\', '\\\\').replace("'", r"\'").replace('"', r'\"').replace('\n', r'\n')
                # Count-neutral translations avoid missing grammatical forms in Arabic and Russian.
                if element.tag == 'plurals':
                    other = next(item for item in element if item.attrib['quantity'] == 'other')
                    for item in list(element):
                        element.remove(item)
                    element.append(other)
            ET.indent(root, space='    ')
            write(base.parent.parent / f"values-{language['android']}/strings.xml", '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by scripts/localize.py; edit localization/translations instead. -->\n' + ET.tostring(root, encoding='unicode') + '\n')
    base = ROOT / 'windows/app/src/main/resources/i18n/strings_en.properties'
    for tag, table in tables.items():
        lines = ['# Generated by scripts/localize.py; edit localization/translations instead.']
        for key, value in properties(base).items():
            result = translated(value, table).replace('\\', '\\\\').replace('\n', r'\n')
            lines.append(key + '=' + result)
        write(base.with_name(f'strings_{tag}.properties'), '\n'.join(lines) + '\n')
    if errors:
        raise ValueError('Generated resources are stale: ' + ', '.join(errors))
    print(f'Localization: {len(LANGUAGES)} languages, {len(expected)} source messages, {count} native files verified' if check else f'Generated {count} native files')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', action='store_true')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.inventory:
        print(json.dumps(inventory(), ensure_ascii=False, indent=2))
    else:
        generate(args.check)
