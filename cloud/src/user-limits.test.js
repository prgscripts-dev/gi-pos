const test = require('node:test');
const assert = require('node:assert/strict');
const { enforceUserLimit, parseUserLimit } = require('./user-limits');

test('treats an empty user limit as unlimited', () => {
  assert.equal(parseUserLimit(''), null);
  assert.doesNotThrow(() => enforceUserLimit({ max_users: null }, new Array(20).fill({ active: true })));
});

test('accepts a positive limited capacity', () => {
  assert.equal(parseUserLimit('5'), 5);
  assert.doesNotThrow(() => enforceUserLimit({ max_users: 2 }, [{ active: true }, { active: true }]));
});

test('rejects an active directory above account capacity', () => {
  assert.throws(
    () => enforceUserLimit({ max_users: 1 }, [{ active: true }, { active: true }]),
    /allows 1 active user/,
  );
  assert.throws(
    () => enforceUserLimit({ max_users: 1 }, JSON.stringify([{ active: true }, { active: true }])),
    /allows 1 active user/,
  );
});
