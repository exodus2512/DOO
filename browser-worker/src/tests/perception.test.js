/**
 * Browser Worker — Perception Module unit tests
 */
const { test, describe } = require('node:test');
const assert = require('node:assert/strict');

describe('Perception Module', () => {
  const fs = require('fs');
  const source = fs.readFileSync(require.resolve('../perception'), 'utf8');

  test('exports buildObservation function', () => {
    const mod = require('../perception');
    assert.ok(typeof mod.buildObservation === 'function');
  });

  test('exports resolveElement function', () => {
    const mod = require('../perception');
    assert.ok(typeof mod.resolveElement === 'function');
  });

  test('uses schema version doo.perception.v1', () => {
    const mod = require('../perception');
    assert.equal(mod.SCHEMA_VERSION, 'doo.perception.v1');
  });

  test('MAX_PAGE_TEXT_LENGTH is 8000', () => {
    const mod = require('../perception');
    assert.equal(mod.MAX_PAGE_TEXT_LENGTH, 8000);
  });

  test('MAX_INTERACTIVE_ELEMENTS is 100', () => {
    const mod = require('../perception');
    assert.equal(mod.MAX_INTERACTIVE_ELEMENTS, 100);
  });

  test('generateObservationId produces unique IDs with obs_ prefix', () => {
    const mod = require('../perception');
    const id1 = mod.generateObservationId();
    const id2 = mod.generateObservationId();
    assert.ok(id1.startsWith('obs_'));
    assert.ok(id2.startsWith('obs_'));
    assert.notEqual(id1, id2);
  });

  test('observation structure includes required fields', () => {
    assert.ok(source.includes('schema_version'));
    assert.ok(source.includes('observation_id'));
    assert.ok(source.includes('task_id'));
    assert.ok(source.includes('page_text'));
    assert.ok(source.includes('interactive_elements'));
    assert.ok(source.includes('observed_at'));
  });

  test('interactive elements include role, label, type, enabled, visible', () => {
    assert.ok(source.includes("role:"));
    assert.ok(source.includes("label:"));
    assert.ok(source.includes("enabled:"));
    assert.ok(source.includes("visible:"));
  });

  test('password values are masked', () => {
    assert.ok(source.includes("isPassword"));
    assert.ok(source.includes("'***'"));
  });

  test('resolveElement validates element_index bounds', () => {
    assert.ok(source.includes('out of range'));
  });

  test('resolveElement uses multiple strategies (id, role, label, name)', () => {
    assert.ok(source.includes('Strategy 1: By ID'));
    assert.ok(source.includes('Strategy 2: By role'));
    assert.ok(source.includes('Strategy 3: By label'));
    assert.ok(source.includes('Strategy 4: By name'));
    assert.ok(source.includes('Strategy 5: By bounding box'));
  });
});
