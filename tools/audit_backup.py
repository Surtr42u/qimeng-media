"""便携 v2 备份与数据库快照只读对比；输出计数，不输出私人元数据。

只检查源备份条目在目标的存在与明确可比字段，不把目标增量视为重复。
可变关联的差异需要结合导入后的编辑解释，不自动认定为丢失或修复。
"""
from collections import Counter, defaultdict
import json


def pick_asset(source, by_id, by_name):
    # 对齐 httpapi.pickPortableAsset：本库身份优先，跨库逐层唯一消歧。
    if source.get('assetId') in by_id:
        return source['assetId']
    rows = by_name.get(source['fileName'], [])
    if len(rows) == 1:
        return rows[0]['asset_id']
    rows = [r for r in rows if source.get('relPath') is not None and r['rel_path'] == source['relPath']]
    if len(rows) == 1:
        return rows[0]['asset_id']
    rows = [r for r in rows if source.get('libraryName') is not None and r['library_name'] == source['libraryName']]
    return rows[0]['asset_id'] if len(rows) == 1 else None


def compare_backup(data, envelope):
    if (envelope.get('format') != 'qimeng_backup' or envelope.get('schemaVersion') != 2
            or not isinstance(envelope.get('data', {}).get('viewEvents'), list)):
        raise ValueError('only-portable-v2-supported')
    source = envelope['data']
    by_id = {a['asset_id']: a for a in data['assets']}
    libraries = {r['id']: r['name'] for r in data['libraries']}
    by_name = defaultdict(list)
    for asset in data['assets']:
        by_name[asset['file_name']].append(dict(asset, library_name=libraries[asset['library_id']]))
    mapping = {f['recordKey']: pick_asset(f, by_id, by_name) for f in source.get('mediaFiles', [])}
    if len(mapping) != len(source.get('mediaFiles', [])):
        raise ValueError('duplicate-record-key')
    targets = Counter(mapping.values())
    mapping = {k: v for k, v in mapping.items() if v is not None and targets[v] == 1}
    counts = {}

    def count(name, entries, predicate):
        entries = list(entries)
        matched = sum(bool(predicate(r)) for r in entries)
        counts[name] = {'source': len(entries), 'matched': matched, 'differentOrMissing': len(entries) - matched}

    count('mediaMappings', source.get('mediaFiles', []), lambda f: f['recordKey'] in mapping)
    count('mediaSizeAndType', source.get('mediaFiles', []), lambda f:
          f['recordKey'] in mapping and all(by_id[mapping[f['recordKey']]][k] == f[v]
          for k, v in (('size_bytes', 'sizeBytes'), ('media_type', 'mediaType'))))
    identities = {r['event_id']: r['stable_id'] for r in data['portable_event_identity']}
    events = defaultdict(list)
    for event in data['view_events']:
        key = event['client_event_id'] if event['client_event_id'] is not None else identities.get(event['id'])
        events[key].append(event)

    def event_matches(event):
        rows = events.get(event['eventId'], [])
        if len(rows) != 1 or event['recordKey'] not in mapping:
            return False
        row = rows[0]
        return (row['asset_id'] == mapping[event['recordKey']]
                and all(row[k] == event.get(v) for k, v in (
                    ('kind', 'kind'), ('session_id', 'sessionId'), ('started_at', 'startedAt'),
                    ('seconds', 'seconds'), ('day', 'day'))))

    source_ids = [e['eventId'] for e in source['viewEvents']]
    source_id_set = set(source_ids)
    if len(source_id_set) != len(source_ids):
        raise ValueError('duplicate-source-event-identity')
    count('eventsExact', source['viewEvents'], event_matches)
    authors = {r['id']: r for r in data['authors']}
    tags = {r['name'] for r in data['tags']}
    author_refs = {(r['asset_id'], r['author_id']) for r in data['asset_authors']}
    tag_names = {r['id']: r['name'] for r in data['tags']}
    tag_refs = {(r['asset_id'], tag_names.get(r['tag_id'])) for r in data['asset_tags']}
    count('authors', source.get('authors', []), lambda r:
          r['authorId'] in authors and authors[r['authorId']]['display_name'] == r['displayName'])
    count('tags', source.get('tags', []), lambda r: r['name'] in tags)
    count('authorRefs', source.get('authorMediaRefs', []), lambda r:
          (mapping.get(r['recordKey']), r['authorId']) in author_refs)
    count('tagRefs', source.get('mediaTagRefs', []), lambda r:
          (mapping.get(r['recordKey']), r['tagName']) in tag_refs)
    favorites = {r['asset_id'] for r in data['favorites']}
    count('favorites', source.get('favorites', []), lambda k: mapping.get(k) in favorites)
    count('followedAuthors', source.get('followedAuthorIds', []), lambda k:
          k in authors and bool(authors[k]['followed']))
    timeline = {(r['asset_id'], r['time_millis'], r['name']) for r in data['timeline_tags']}
    count('timelineTags', source.get('timelineTags', []), lambda r:
          (mapping.get(r['recordKey']), r['timeMillis'], r['name']) in timeline)
    likes = {(r['asset_id'], r['day']) for r in data['likes']}
    count('lastLikeDay', [r for r in source.get('likes', []) if r.get('lastLikeDate')], lambda r:
          (mapping.get(r['recordKey']), r['lastLikeDate']) in likes)
    settings = {r['key']: r['value'] for r in data['kv_settings']}
    # TXT keep 合并可能合法改变全文，差异仅提示人工解释，不擅自判断覆盖/修复。
    fragments = {r['filename']: r['content'] for r in json.loads(settings.get('imported_txt_sources', '[]'))}
    count('txtFragmentsExact', source.get('txtFragments', []), lambda r:
          fragments.get(r['filename']) == r['content'])
    prefs = source.get('appPrefs') or {}
    if prefs.get('recommendationPrefs') is not None:
        count('recommendationPrefs', [prefs['recommendationPrefs']], lambda r:
              json.loads(settings.get('recommend_prefs', 'null')) == r)
    return {'status': 'compared', 'compared': True, 'schemaVersion': 2, 'sections': counts,
            'differences': sum(r['differentOrMissing'] for r in counts.values()),
            'exportedAtMillis': envelope.get('exportedAtMillis'),
            'targetOnlyEvents': sum(len(v) for k, v in events.items() if k not in source_id_set),
            'notes': ['target-increments-allowed', 'mutable-differences-require-review',
                      'legacy-aggregate-sections-not-replayed-in-v2',
                      'media-content-hashes-not-checked', 'provenance-and-device-settings-not-compared']}
