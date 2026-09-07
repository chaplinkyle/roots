#!/usr/bin/env python3
"""Validate service YAML shapes against Google's public Cloud Run v1 discovery schema.

Requires PyYAML. This is offline shape validation after downloading the schema;
it does not validate account permissions, annotation semantics or provision resources.
"""
import argparse
import json
from pathlib import Path
import urllib.request
import yaml


def validate(value, schema, definitions, path='$'):
    if '$ref' in schema:
        return validate(value, definitions[schema['$ref']], definitions, path)
    kind = schema.get('type')
    if kind == 'object':
        if not isinstance(value, dict):
            raise ValueError(path + ': expected object')
        properties = schema.get('properties', {})
        for name, item in value.items():
            field = properties.get(name, schema.get('additionalProperties'))
            if field is None:
                raise ValueError(path + ': unrecognized property ' + name)
            validate(item, field, definitions, path + '.' + name)
    elif kind == 'array':
        if not isinstance(value, list):
            raise ValueError(path + ': expected array')
        for index, item in enumerate(value):
            validate(item, schema['items'], definitions, path + f'[{index}]')
    elif kind in ('string', 'boolean', 'integer', 'number'):
        expected = {'string': str, 'boolean': bool, 'integer': int, 'number': (int, float)}[kind]
        if not isinstance(value, expected) or (kind in ('integer', 'number') and isinstance(value, bool)):
            raise ValueError(path + ': expected ' + kind)
    if 'enum' in schema and value not in schema['enum']:
        raise ValueError(path + ': unsupported enum value')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('service', type=Path)
    parser.add_argument('--discovery', type=Path, help='Previously downloaded Cloud Run v1 discovery JSON')
    args = parser.parse_args()
    if args.discovery:
        data = args.discovery.read_bytes()
    else:
        with urllib.request.urlopen('https://run.googleapis.com/$discovery/rest?version=v1', timeout=30) as response:
            data = response.read()
    discovery = json.loads(data)
    document = yaml.safe_load(args.service.read_text(encoding='utf-8'))
    if document.get('kind') != 'Service' or document.get('apiVersion') != 'serving.knative.dev/v1':
        raise ValueError('Expected a Cloud Run v1 Service')
    validate(document, discovery['schemas']['Service'], discovery['schemas'])
    print('PASS: Cloud Run v1 service schema shapes; account-side deployment remains unverified')


if __name__ == '__main__':
    main()
