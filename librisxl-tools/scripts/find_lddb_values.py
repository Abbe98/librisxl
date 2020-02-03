#!/usr/bin/env python3
import json


keys = []
by_type = False
leaf_filter = False


def process_record(line):
    graph = json.loads(line)['@graph']
    rec_id = graph[0]['@id'].rsplit('/', 1)[-1]
    return tuple((rec_id, '.'.join(path), value)
            for i, thing in enumerate(graph[1:])
            for path, value in
            find_paths(graph, thing, keys, trail=_start_trail(i, thing)))


def _start_trail(i, thing):
    return [_typed_key('instanceOf' if i else '', thing)]


def _typed_key(k, o):
    if by_type and isinstance(o, dict) and '@type' in o:
        return f"{k}[{o['@type']}]"
    else:
        return k


def find_paths(graph, data, keys, trail=[]):
    if isinstance(data, list):
        for item in data:
            yield from find_paths(graph, item, keys, trail)
        return

    if not isinstance(data, dict):
        return

    for k, vs in data.items():
        if not vs:
            continue
        if not isinstance(vs, list):
            vs = [vs]
        for v in vs:
            tk = _typed_key(k, v)
            subtrail = trail + [tk]
            if k in keys and (not leaf_filter or
                    eval(leaf_filter, globals(), locals())):
                yield subtrail, v
            else:
                yield from find_paths(graph, v, keys, subtrail)


class JsonSetReprEncoder(json.JSONEncoder):
    def default(self, o):
        return repr(o) if isinstance(o, set) else super().default(o)


if __name__ == '__main__':
    from collections import defaultdict, Counter
    from multiprocessing import Pool
    import sys
    from time import time

    args = sys.argv[1:]

    keys = []
    keep_examples = 0
    by_type = False
    for it in args:
        if it.isdigit():
            keep_examples = int(it)
        elif it in {'-t', '--by-type'}:
            by_type = True
        elif leaf_filter is True:
            leaf_filter = it
        elif it == '--filter':
            leaf_filter = True
        else:
            keys.append(it)

    if not keys:
        print('Provide keys to find values for!', file=sys.stderr)
        sys.exit()

    if leaf_filter:
        leaf_filter = compile(leaf_filter, 'CmdLineArgument', 'eval')

    paths_values = defaultdict(lambda: defaultdict(set if keep_examples else int))

    pool = Pool()
    results = pool.imap_unordered(process_record, sys.stdin, chunksize=8192)
    try:
        t_last = 0
        for i, result in enumerate(results):

            for rec_id, path, value in result:
                if isinstance(value, list):
                    value = '\t'.join(value)
                if keep_examples:
                    examples = paths_values[path][value]
                    if len(examples) < keep_examples:
                        examples.add(rec_id)
                    elif len(examples) == keep_examples:
                        examples.add('...')
                else:
                    paths_values[path][value] += 1

            t_now = time()
            if (t_now - t_last) > 2:
                t_last = t_now
                values = sum(len(v) for v in paths_values.values())
                print(f"\033cRecords: {i + 1:,}, Results: {values:,}",
                        file=sys.stderr)

    finally:
        print(json.dumps(paths_values,
            indent=2, ensure_ascii=False, cls=JsonSetReprEncoder))
